#include "glx_hook.h"
#include "imgui_gui.h"
#include "jni_bridge.h"
#include "jvmti_context.h"
#include "log.h"
#include "movement_input_transformer.h"
#include "packet_transformer.h"
#include "world_render_transformer.h"

#include <GL/gl.h>
#include <GL/glx.h>

#include <funchook.h>
#include <dlfcn.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>

// GL 1.4+ constants not always in Mesa's gl.h
#ifndef GL_BLEND_SRC_RGB
#define GL_BLEND_SRC_RGB   0x80C9
#define GL_BLEND_DST_RGB   0x80C8
#define GL_BLEND_SRC_ALPHA 0x80CB
#define GL_BLEND_DST_ALPHA 0x80CA
#endif

namespace gnu {

namespace {

using GlxSwapBuffersFn = void (*)(Display*, GLXDrawable);

GlxSwapBuffersFn g_original_swap = nullptr;
funchook_t* g_hook = nullptr;
bool g_installed = false;

using PFNGLBLENDFUNCSEPARATEPROC = void (*)(GLenum, GLenum, GLenum, GLenum);
using PFNGLACTIVETEXTUREPROC = void (*)(GLenum);
PFNGLBLENDFUNCSEPARATEPROC pglBlendFuncSeparate = nullptr;
PFNGLACTIVETEXTUREPROC pglActiveTexture = nullptr;

std::chrono::steady_clock::time_point g_last_frame;
std::chrono::steady_clock::time_point g_last_tick;
bool g_imgui_ready = false;

void hooked_swap_buffers(Display* dpy, GLXDrawable drawable) {
    GLint viewport[4] = {0, 0, 0, 0};
    glGetIntegerv(GL_VIEWPORT, viewport);
    if (viewport[2] < 1 || viewport[3] < 1) {
        g_original_swap(dpy, drawable);
        return;
    }

    if (!g_imgui_ready) {
        if (!ImGuiGui::init_if_needed()) {
            g_original_swap(dpy, drawable);
            return;
        }
        pglBlendFuncSeparate = reinterpret_cast<PFNGLBLENDFUNCSEPARATEPROC>(
            glXGetProcAddress(reinterpret_cast<const GLubyte*>("glBlendFuncSeparate")));
        pglActiveTexture = reinterpret_cast<PFNGLACTIVETEXTUREPROC>(
            glXGetProcAddress(reinterpret_cast<const GLubyte*>("glActiveTexture")));
        g_last_frame = std::chrono::steady_clock::now();
        g_imgui_ready = true;
        log_line("GUI_ imgui initialized in glXSwapBuffers");
    }

    // Java bootstrap on the render thread. The Java class is DEFINED here (load
    // once), then initOnRenderThread() is retried each frame until it reports
    // done — it resolves Minecraft via reflection (FMLClientHandler anchor) and
    // returns false until the client singleton exists. The payload contains no
    // compile-time net.minecraft.* refs, so it links regardless of obfuscation.
    static std::atomic<bool> java_class_loaded{false};
    static std::atomic<bool> java_init_done{false};
    if (!java_init_done.load(std::memory_order_relaxed)) {
        JNIEnv* jenv = JvmtiContext::instance().attach_current_thread();
        if (jenv) {
            JniBridge& b = JniBridge::instance();
            if (!java_class_loaded.load(std::memory_order_relaxed)) {
                // Capture render-thread context loader (Forge LaunchClassLoader or Lunar game loader).
                if (b.capture_context_classloader(jenv)
                        && b.add_jar_to_classloader(jenv, JniBridge::resolve_sibling_jar())
                        && b.load_native_bootstrap(jenv)) {
                    if (!MovementInputTransformer::install(jenv))
                        log_line("MIO_TRANSFORM_ install failed; Eagle forceSneak patch inactive");
                    if (!PacketTransformerInstall::install(jenv))
                        log_line("PKT_TRANSFORM_ install failed; packet modules inactive");
                    if (!WorldRenderTransformerInstall::install(jenv))
                        log_line("RND_TRANSFORM_ install failed; world render modules inactive");
                    java_class_loaded.store(true, std::memory_order_relaxed);
                } else {
                    log_line("NATIVE_ render: classloader/jar/bootstrap setup failed");
                }
            }
            if (java_class_loaded.load(std::memory_order_relaxed)) {
                if (b.call_java_init_on_render_thread(jenv)) {
                    java_init_done.store(true, std::memory_order_relaxed);
                    log_line("NATIVE_ render: Java init complete");
                }
            }
        }
    }

    // ===== Save GL state MC 1.8.9 depends on =====
    GLint saved_viewport[4];
    GLint saved_scissor_box[4];
    GLboolean saved_scissor_test = glIsEnabled(GL_SCISSOR_TEST);
    GLboolean saved_depth_test = glIsEnabled(GL_DEPTH_TEST);
    GLboolean saved_cull_face = glIsEnabled(GL_CULL_FACE);
    GLboolean saved_blend = glIsEnabled(GL_BLEND);
    GLboolean saved_tex2d = glIsEnabled(GL_TEXTURE_2D);
    GLint saved_blend_src, saved_blend_dst, saved_blend_src_a, saved_blend_dst_a;
    GLint saved_matrix_mode, saved_active_texture;

    glGetIntegerv(GL_VIEWPORT, saved_viewport);
    glGetIntegerv(GL_SCISSOR_BOX, saved_scissor_box);
    glGetIntegerv(GL_BLEND_SRC_RGB, &saved_blend_src);
    glGetIntegerv(GL_BLEND_DST_RGB, &saved_blend_dst);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &saved_blend_src_a);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &saved_blend_dst_a);
    glGetIntegerv(GL_MATRIX_MODE, &saved_matrix_mode);
    glGetIntegerv(0x84E0 /* GL_ACTIVE_TEXTURE */, &saved_active_texture);

    glMatrixMode(GL_PROJECTION);
    glPushMatrix();
    glMatrixMode(GL_MODELVIEW);
    glPushMatrix();

    {
        auto now = std::chrono::steady_clock::now();
        float dt = std::chrono::duration<float>(now - g_last_frame).count();
        if (dt <= 0.0f) dt = 1.0f / 60.0f;
        g_last_frame = now;

        // Fire Java module tick at 20 Hz regardless of framerate.
        JNIEnv* env = JvmtiContext::instance().attach_current_thread();
        if (env) {
            using namespace std::chrono;
            constexpr auto tick_interval =
                duration_cast<steady_clock::duration>(duration<double>(1.0 / 20.0));
            if (now - g_last_tick >= tick_interval) {
                g_last_tick = now;
                JniBridge::instance().call_tick(env);
            }
        }

        // Mouse position from X11 (matches the GL viewport space). evdev deltas
        // are in a different coordinate space under XWayland and are badly
        // offset; query only while the menu is open to avoid a per-frame X
        // round-trip. evdev still supplies the button state.
        //
        // NB: we must query against the ROOT window, never the GLXDrawable from
        // glXSwapBuffers — that drawable is not a valid X Window, so
        // XQueryPointer raises BadWindow (request_code 38), which LWJGL's global
        // X error handler escalates into a fatal exception and crashes MC. For a
        // fullscreen window at the screen origin, root_x/root_y equal the GL
        // viewport coordinates.
        if (ImGuiGui::menu_visible()) {
            Window root = DefaultRootWindow(dpy);
            Window root_ret, child_ret;
            int root_x = 0, root_y = 0, win_x = 0, win_y = 0;
            unsigned int mask_ret = 0;
            if (XQueryPointer(dpy, root, &root_ret, &child_ret, &root_x, &root_y,
                              &win_x, &win_y, &mask_ret))
                ImGuiGui::set_mouse_position(root_x, root_y);
        }

        // ImGui frame (input is applied on this render thread). HUD draws even
        // when the menu is closed so enabled-module list and toasts stay visible.
        bool hud_draw = false;
        if (env)
            hud_draw = JniBridge::instance().hud_should_draw(env);
        const bool menu_draw = ImGuiGui::menu_visible();
        if (menu_draw || hud_draw) {
            ImGuiGui::begin_frame(saved_viewport[2], saved_viewport[3], dt);
            if (hud_draw)
                ImGuiGui::draw_hud_overlay(env);
            if (menu_draw)
                ImGuiGui::draw();
            ImGuiGui::end_frame();
        }
    }

    // ===== Restore GL state =====
    glMatrixMode(GL_MODELVIEW);
    glPopMatrix();
    glMatrixMode(GL_PROJECTION);
    glPopMatrix();
    glMatrixMode(saved_matrix_mode);

    if (pglActiveTexture) pglActiveTexture(saved_active_texture);

    glViewport(saved_viewport[0], saved_viewport[1], saved_viewport[2], saved_viewport[3]);
    glScissor(saved_scissor_box[0], saved_scissor_box[1], saved_scissor_box[2], saved_scissor_box[3]);

    if (saved_scissor_test) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
    if (saved_depth_test) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
    if (saved_cull_face) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
    if (saved_blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
    if (saved_tex2d) glEnable(GL_TEXTURE_2D); else glDisable(GL_TEXTURE_2D);

    if (pglBlendFuncSeparate)
        pglBlendFuncSeparate(saved_blend_src, saved_blend_dst, saved_blend_src_a, saved_blend_dst_a);

    g_original_swap(dpy, drawable);
}

// RainClient probe order: RTLD_DEFAULT, then vendor DSOs; prefer a vendor
// address that differs from RTLD_DEFAULT (libglvnd dispatch stub avoidance).
GlxSwapBuffersFn resolve_swap_buffers() {
    auto* default_fn = reinterpret_cast<GlxSwapBuffersFn>(dlsym(RTLD_DEFAULT, "glXSwapBuffers"));
    log_line(std::string("GUI_ RTLD_DEFAULT glXSwapBuffers = ")
             + (default_fn ? std::to_string(reinterpret_cast<uintptr_t>(default_fn)) : "NULL"));

    const char* dso_names[] = {
        "libGL.so.1", "libGLX.so.0", "libGLX_nvidia.so.0",
        "libGLX_nvidia.so.595.71.05", nullptr
    };
    GlxSwapBuffersFn target_fn = nullptr;
    for (const char** name = dso_names; *name; ++name) {
        void* h = dlopen(*name, RTLD_NOLOAD | RTLD_LAZY);
        if (!h) continue;
        auto* fn = reinterpret_cast<GlxSwapBuffersFn>(dlsym(h, "glXSwapBuffers"));
        log_line(std::string("GUI_ ") + *name + " glXSwapBuffers = "
                 + (fn ? std::to_string(reinterpret_cast<uintptr_t>(fn)) : "NULL"));
        if (fn && fn != default_fn)
            target_fn = fn;
        dlclose(h);
    }
    return target_fn ? target_fn : default_fn;
}

} // namespace

bool GlxHook::install() {
    if (g_installed) {
        log_line("GUI_ glx hook already installed");
        return true;
    }

    g_original_swap = resolve_swap_buffers();
    if (!g_original_swap) {
        log_line("GUI_ glXSwapBuffers not found in any DSO");
        return false;
    }

    g_hook = funchook_create();
    if (!g_hook) {
        log_line("GUI_ funchook_create failed");
        return false;
    }
    if (funchook_prepare(g_hook, reinterpret_cast<void**>(&g_original_swap),
                         reinterpret_cast<void*>(hooked_swap_buffers)) != 0) {
        log_line(std::string("GUI_ funchook_prepare failed: ") + funchook_error_message(g_hook));
        return false;
    }
    if (funchook_install(g_hook, 0) != 0) {
        log_line(std::string("GUI_ funchook_install failed: ") + funchook_error_message(g_hook));
        return false;
    }

    g_installed = true;
    ImGuiGui::start_input();
    log_line("GUI_ glXSwapBuffers hook installed");
    return true;
}

void GlxHook::uninstall() {
    if (g_hook) {
        funchook_uninstall(g_hook, 0);
        funchook_destroy(g_hook);
        g_hook = nullptr;
    }
    // Skip ImGui_ImplOpenGL3_Shutdown: GL context usually gone at teardown.
    ImGuiGui::shutdown_context();
    g_imgui_ready = false;
    g_installed = false;
    log_line("GUI_ glx hook uninstalled");
}

} // namespace gnu

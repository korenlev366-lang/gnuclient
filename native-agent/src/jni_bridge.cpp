#include "jni_bridge.h"
#include "imgui_gui.h"
#include "jvmti_context.h"
#include "log.h"

#include <X11/X.h>
#include <X11/Xlib.h>

#include <dlfcn.h>
#include <filesystem>
#include <unistd.h>

namespace gnu {

namespace {

extern "C" jboolean JNICALL jni_is_left_mouse_down(JNIEnv*, jclass) {
    return ImGuiGui::is_left_down() ? JNI_TRUE : JNI_FALSE;
}

extern "C" jboolean JNICALL jni_is_shift_down(JNIEnv*, jclass) {
    return ImGuiGui::is_shift_down() ? JNI_TRUE : JNI_FALSE;
}

extern "C" jboolean JNICALL jni_is_menu_open(JNIEnv*, jclass) {
    return ImGuiGui::menu_open() ? JNI_TRUE : JNI_FALSE;
}

extern "C" void JNICALL jni_toggle_menu(JNIEnv*, jclass) {
    ImGuiGui::toggle_menu();
}

extern "C" void JNICALL jni_set_menu_open(JNIEnv*, jclass, jboolean open) {
    ImGuiGui::set_menu_open(open == JNI_TRUE);
}

void do_x11_click(int hold_us) {
    Display* dpy = XOpenDisplay(nullptr);
    if (!dpy) {
        log_line("JNI_ nativeClick: XOpenDisplay failed");
        return;
    }

    XButtonEvent ev{};
    ev.button = Button1;
    ev.same_screen = True;
    ev.subwindow = DefaultRootWindow(dpy);

    // Walk to the deepest child under the pointer (LWJGL canvas), Rain/Phantom path.
    while (ev.subwindow) {
        ev.window = ev.subwindow;
        XQueryPointer(dpy, ev.window,
                      &ev.root, &ev.subwindow,
                      &ev.x_root, &ev.y_root,
                      &ev.x, &ev.y,
                      &ev.state);
    }

    ev.type = ButtonPress;
    XSendEvent(dpy, PointerWindow, True, ButtonPressMask, reinterpret_cast<XEvent*>(&ev));
    XFlush(dpy);

    if (hold_us > 0)
        usleep(static_cast<useconds_t>(hold_us));

    ev.type = ButtonRelease;
    XSendEvent(dpy, PointerWindow, True, ButtonReleaseMask, reinterpret_cast<XEvent*>(&ev));
    XFlush(dpy);
    XCloseDisplay(dpy);
}

extern "C" void JNICALL jni_native_click(JNIEnv*, jclass, jint hold_ms) {
    int hold_us = hold_ms > 0 ? static_cast<int>(hold_ms) * 1000 : 0;
    do_x11_click(hold_us);
}

extern "C" jint JNICALL jni_consume_mouse_delta_x(JNIEnv*, jclass) {
    return static_cast<jint>(ImGuiGui::consume_mouse_delta_x());
}

extern "C" jint JNICALL jni_consume_mouse_delta_y(JNIEnv*, jclass) {
    return static_cast<jint>(ImGuiGui::consume_mouse_delta_y());
}

extern "C" jlong JNICALL jni_last_mouse_move_age_ms(JNIEnv*, jclass) {
    return static_cast<jlong>(ImGuiGui::last_mouse_move_age_ms());
}

namespace fs = std::filesystem;

bool check_and_clear(JNIEnv* env, const char* where) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        log_line(std::string("JNI_ exception at ") + where);
        return true;
    }
    return false;
}

std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (!js)
        return std::string();
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string out = chars ? chars : "";
    if (chars)
        env->ReleaseStringUTFChars(js, chars);
    env->DeleteLocalRef(js);
    return out;
}

} // namespace

JniBridge& JniBridge::instance() {
    static JniBridge inst;
    return inst;
}

std::string JniBridge::resolve_sibling_jar() {
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(&JniBridge::resolve_sibling_jar), &info) && info.dli_fname) {
        fs::path so_path(info.dli_fname);
        return (so_path.parent_path() / "gnu-client.jar").string();
    }
    return "gnu-client.jar";
}

bool JniBridge::capture_launch_classloader(JNIEnv* env) {
    jclass launch = env->FindClass("net/minecraft/launchwrapper/Launch");
    if (check_and_clear(env, "FindClass(Launch)") || launch == nullptr)
        return false;
    jfieldID fid = env->GetStaticFieldID(
        launch, "classLoader", "Lnet/minecraft/launchwrapper/LaunchClassLoader;");
    if (check_and_clear(env, "GetStaticFieldID(classLoader)") || fid == nullptr) {
        env->DeleteLocalRef(launch);
        return false;
    }
    jobject cl = env->GetStaticObjectField(launch, fid);
    check_and_clear(env, "GetStaticObjectField(classLoader)");
    env->DeleteLocalRef(launch);
    if (cl == nullptr)
        return false;
    if (launch_classloader_)
        env->DeleteGlobalRef(launch_classloader_);
    launch_classloader_ = env->NewGlobalRef(cl);
    env->DeleteLocalRef(cl);
    log_line("JNI_ LaunchClassLoader captured");
    return launch_classloader_ != nullptr;
}

bool JniBridge::capture_context_classloader(JNIEnv* env) {
    if (launch_classloader_)
        return true;
    jclass thread_cls = env->FindClass("java/lang/Thread");
    if (check_and_clear(env, "FindClass(Thread)") || !thread_cls)
        return false;
    jmethodID cur = env->GetStaticMethodID(
        thread_cls, "currentThread", "()Ljava/lang/Thread;");
    jmethodID get_ctx = env->GetMethodID(
        thread_cls, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    if (check_and_clear(env, "Thread methods") || !cur || !get_ctx)
        return false;
    jobject thread = env->CallStaticObjectMethod(thread_cls, cur);
    if (check_and_clear(env, "currentThread") || !thread)
        return false;
    jobject cl = env->CallObjectMethod(thread, get_ctx);
    env->DeleteLocalRef(thread);
    if (check_and_clear(env, "getContextClassLoader") || !cl)
        return false;
    launch_classloader_ = env->NewGlobalRef(cl);
    env->DeleteLocalRef(cl);
    log_line("JNI_ render-thread context classloader captured");
    return launch_classloader_ != nullptr;
}

bool JniBridge::add_jar_to_classloader(JNIEnv* env, const std::string& jar_path) {
    if (!launch_classloader_) {
        log_line("JNI_ add_jar: no classloader captured");
        return false;
    }

    jobject url_loader = launch_classloader_;
    jclass urlcl_cls = env->FindClass("java/net/URLClassLoader");
    if (check_and_clear(env, "FindClass(URLClassLoader)") || !urlcl_cls)
        return false;

    if (!env->IsInstanceOf(launch_classloader_, urlcl_cls)) {
        // Lunar/vanilla loaders may not be URLClassLoader — walk parents (Timewarp uses
        // the game's loader chain; addURL works on the first URLClassLoader ancestor).
        jclass cl_cls = env->FindClass("java/lang/ClassLoader");
        jmethodID get_parent = env->GetMethodID(cl_cls, "getParent", "()Ljava/lang/ClassLoader;");
        jobject cur = launch_classloader_;
        url_loader = nullptr;
        for (int depth = 0; depth < 16 && cur; ++depth) {
            if (env->IsInstanceOf(cur, urlcl_cls)) {
                url_loader = cur;
                break;
            }
            if (!get_parent)
                break;
            jobject parent = env->CallObjectMethod(cur, get_parent);
            if (check_and_clear(env, "getParent"))
                break;
            if (cur != launch_classloader_)
                env->DeleteLocalRef(cur);
            cur = parent;
        }
        if (cur && cur != launch_classloader_ && cur != url_loader)
            env->DeleteLocalRef(cur);
        if (!url_loader) {
            log_line("JNI_ add_jar: no URLClassLoader in loader chain");
            return false;
        }
        log_line("JNI_ add_jar: using URLClassLoader ancestor");
    }
    jclass file_cls = env->FindClass("java/io/File");
    if (check_and_clear(env, "FindClass(File)") || !file_cls)
        return false;
    jmethodID file_ctor = env->GetMethodID(file_cls, "<init>", "(Ljava/lang/String;)V");
    jmethodID file_to_uri = env->GetMethodID(file_cls, "toURI", "()Ljava/net/URI;");
    if (check_and_clear(env, "File methods") || !file_ctor || !file_to_uri)
        return false;

    jstring path_str = env->NewStringUTF(jar_path.c_str());
    jobject file_obj = env->NewObject(file_cls, file_ctor, path_str);
    if (check_and_clear(env, "new File") || !file_obj)
        return false;
    jobject uri_obj = env->CallObjectMethod(file_obj, file_to_uri);
    if (check_and_clear(env, "File.toURI") || !uri_obj)
        return false;
    jclass uri_cls = env->GetObjectClass(uri_obj);
    jmethodID uri_to_url = env->GetMethodID(uri_cls, "toURL", "()Ljava/net/URL;");
    jobject url_obj = env->CallObjectMethod(uri_obj, uri_to_url);
    if (check_and_clear(env, "URI.toURL") || !url_obj)
        return false;

    // URLClassLoader.addURL(URL) — protected; JNI bypasses access checks.
    jmethodID add_url = env->GetMethodID(urlcl_cls, "addURL", "(Ljava/net/URL;)V");
    if (check_and_clear(env, "GetMethodID(addURL)") || !add_url)
        return false;
    env->CallVoidMethod(url_loader, add_url, url_obj);
    if (check_and_clear(env, "addURL invoke"))
        return false;

    env->DeleteLocalRef(path_str);
    env->DeleteLocalRef(file_obj);
    env->DeleteLocalRef(uri_obj);
    env->DeleteLocalRef(url_obj);
    log_line("JNI_ added jar to LaunchClassLoader: " + jar_path);
    return true;
}

jclass JniBridge::load_class(JNIEnv* env, const char* binary_name) {
    if (!env || !launch_classloader_ || !binary_name)
        return nullptr;
    jclass cl_cls = env->GetObjectClass(launch_classloader_);
    jmethodID load_class = env->GetMethodID(
        cl_cls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (check_and_clear(env, "GetMethodID(loadClass)") || !load_class)
        return nullptr;
    jstring cls_name = env->NewStringUTF(binary_name);
    jobject cls_obj = env->CallObjectMethod(launch_classloader_, load_class, cls_name);
    env->DeleteLocalRef(cls_name);
    if (check_and_clear(env, "LaunchClassLoader.loadClass") || !cls_obj)
        return nullptr;
    return static_cast<jclass>(cls_obj);
}

bool JniBridge::load_native_bootstrap(JNIEnv* env) {
    if (!launch_classloader_) {
        log_line("JNI_ load: no LaunchClassLoader");
        return false;
    }
    jclass cls_obj = env->FindClass("gnu/client/runtime/NativeBootstrap");
    if (check_and_clear(env, "FindClass(NativeBootstrap)"))
        cls_obj = nullptr;
    if (!cls_obj) {
        log_line("JNI_ FindClass NativeBootstrap failed; falling back to LaunchClassLoader.loadClass");
        cls_obj = load_class(env, "gnu.client.runtime.NativeBootstrap");
    }
    if (!cls_obj) {
        log_line("JNI_ NativeBootstrap not found on LaunchClassLoader");
        return false;
    }
    cls_ = static_cast<jclass>(env->NewGlobalRef(cls_obj));
    env->DeleteLocalRef(cls_obj);

    JNINativeMethod native_methods[] = {
        {const_cast<char*>("isLeftMouseDown"), const_cast<char*>("()Z"),
         reinterpret_cast<void*>(&jni_is_left_mouse_down)},
        {const_cast<char*>("nativeClick"), const_cast<char*>("(I)V"),
         reinterpret_cast<void*>(&jni_native_click)},
        {const_cast<char*>("isShiftDown"), const_cast<char*>("()Z"),
         reinterpret_cast<void*>(&jni_is_shift_down)},
        {const_cast<char*>("consumeMouseDeltaX"), const_cast<char*>("()I"),
         reinterpret_cast<void*>(&jni_consume_mouse_delta_x)},
        {const_cast<char*>("consumeMouseDeltaY"), const_cast<char*>("()I"),
         reinterpret_cast<void*>(&jni_consume_mouse_delta_y)},
        {const_cast<char*>("lastMouseMoveAgeMs"), const_cast<char*>("()J"),
         reinterpret_cast<void*>(&jni_last_mouse_move_age_ms)},
        {const_cast<char*>("toggleMenuNative"), const_cast<char*>("()V"),
         reinterpret_cast<void*>(&jni_toggle_menu)},
        {const_cast<char*>("setMenuOpenNative"), const_cast<char*>("(Z)V"),
         reinterpret_cast<void*>(&jni_set_menu_open)},
        {const_cast<char*>("isMenuOpenNative"), const_cast<char*>("()Z"),
         reinterpret_cast<void*>(&jni_is_menu_open)},
    };
    jint reg = env->RegisterNatives(cls_, native_methods, 9);
    log_line("JNI_ RegisterNatives isLeftMouseDown: " + std::to_string(reg));
    if (reg != JNI_OK || check_and_clear(env, "RegisterNatives(NativeBootstrap)")) {
        log_line("JNI_ RegisterNatives NativeBootstrap.isLeftMouseDown failed");
        return false;
    }

    auto sm = [&](const char* n, const char* sig) {
        jmethodID m = env->GetStaticMethodID(cls_, n, sig);
        if (env->ExceptionCheck()) env->ExceptionClear();
        return m;
    };

    mid_init_render_ = sm("initOnRenderThread", "()Z");
    mid_tick_ = sm("tick", "()V");
    mid_render_ = sm("render", "()V");
    mid_reload_scripts_ = sm("reloadScriptsFromGui", "()V");

    mid_mod_count_ = sm("guiModuleCount", "()I");
    mid_mod_name_ = sm("guiModuleName", "(I)Ljava/lang/String;");
    mid_mod_desc_ = sm("guiModuleDesc", "(I)Ljava/lang/String;");
    mid_mod_cat_ = sm("guiModuleCategory", "(I)I");
    mid_mod_enabled_ = sm("guiModuleEnabled", "(I)Z");
    mid_mod_bind_only_ = sm("guiModuleBindOnly", "(I)Z");
    mid_toggle_ = sm("guiToggle", "(I)V");
    mid_mod_key_label_ = sm("guiModuleKeyLabel", "(I)Ljava/lang/String;");
    mid_start_rebind_ = sm("startRebind", "(Ljava/lang/String;)V");
    mid_gui_rebind_pending_ = sm("guiIsRebindPending", "(I)Z");
    mid_set_key_by_name_ = sm("setModuleKeyCode", "(Ljava/lang/String;I)V");
    mid_get_key_by_name_ = sm("getModuleKeyCode", "(Ljava/lang/String;)I");

    mid_set_count_ = sm("guiSettingCount", "(I)I");
    mid_set_name_ = sm("guiSettingName", "(II)Ljava/lang/String;");
    mid_set_type_ = sm("guiSettingType", "(II)I");
    mid_set_bool_ = sm("guiSettingBool", "(II)Z");
    mid_set_bool_w_ = sm("guiSetBool", "(IIZ)V");
    mid_set_float_ = sm("guiSettingFloat", "(II)F");
    mid_set_min_ = sm("guiSettingMin", "(II)F");
    mid_set_max_ = sm("guiSettingMax", "(II)F");
    mid_set_step_ = sm("guiSettingStep", "(II)F");
    mid_set_float_w_ = sm("guiSetFloat", "(IIF)V");
    mid_set_mode_ = sm("guiSettingMode", "(II)I");
    mid_set_mode_count_ = sm("guiSettingModeCount", "(II)I");
    mid_set_mode_name_ = sm("guiSettingModeName", "(III)Ljava/lang/String;");
    mid_set_mode_w_ = sm("guiSetMode", "(III)V");

    mid_hud_should_draw_ = sm("hudShouldDraw", "()Z");
    mid_hud_show_array_ = sm("hudShowArray", "()Z");
    mid_hud_show_notifications_ = sm("hudShowNotifications", "()Z");
    mid_hud_enabled_count_ = sm("hudEnabledModuleCount", "()I");
    mid_hud_enabled_name_ = sm("hudEnabledModuleName", "(I)Ljava/lang/String;");
    mid_hud_notif_count_ = sm("hudNotificationCount", "()I");
    mid_hud_notif_text_ = sm("hudNotificationText", "(I)Ljava/lang/String;");
    mid_hud_notif_enabled_ = sm("hudNotificationEnabled", "(I)Z");
    mid_hud_notif_alpha_ = sm("hudNotificationAlpha", "(I)F");

    if (!mid_init_render_) {
        log_line("JNI_ NativeBootstrap.initOnRenderThread()V missing");
        return false;
    }

    // Class + ids cached; init runs later on the render thread.
    bootstrapped_ = true;
    log_line("JNI_ NativeBootstrap loaded and cached");
    return true;
}

bool JniBridge::call_java_init_on_render_thread(JNIEnv* env) {
    if (!cls_ || !mid_init_render_ || !env)
        return false;
    jboolean done = env->CallStaticBooleanMethod(cls_, mid_init_render_);
    if (check_and_clear(env, "NativeBootstrap.initOnRenderThread"))
        return false;
    return done == JNI_TRUE;
}

void JniBridge::call_tick(JNIEnv* env) {
    if (!bootstrapped_ || !mid_tick_) return;
    env->CallStaticVoidMethod(cls_, mid_tick_);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void JniBridge::call_render(JNIEnv* env) {
    if (!bootstrapped_ || !mid_render_) return;
    env->CallStaticVoidMethod(cls_, mid_render_);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void JniBridge::reload_scripts(JNIEnv* env) {
    if (!bootstrapped_ || !mid_reload_scripts_) return;
    env->CallStaticVoidMethod(cls_, mid_reload_scripts_);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

std::string JniBridge::call_string(JNIEnv* env, jmethodID mid, int a, int b, int c) {
    if (!cls_ || !mid) return std::string();
    jstring js;
    // Argument count is encoded by which method; we always pass the needed ints.
    js = static_cast<jstring>(env->CallStaticObjectMethod(cls_, mid, a, b, c));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return std::string(); }
    return jstring_to_std(env, js);
}

int JniBridge::gui_module_count(JNIEnv* env) {
    if (!cls_ || !mid_mod_count_) return 0;
    jint v = env->CallStaticIntMethod(cls_, mid_mod_count_);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

std::string JniBridge::gui_module_name(JNIEnv* env, int i) {
    if (!cls_ || !mid_mod_name_) return std::string();
    jstring js = static_cast<jstring>(env->CallStaticObjectMethod(cls_, mid_mod_name_, i));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return std::string(); }
    return jstring_to_std(env, js);
}

std::string JniBridge::gui_module_desc(JNIEnv* env, int i) {
    if (!cls_ || !mid_mod_desc_) return std::string();
    jstring js = static_cast<jstring>(env->CallStaticObjectMethod(cls_, mid_mod_desc_, i));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return std::string(); }
    return jstring_to_std(env, js);
}

int JniBridge::gui_module_category(JNIEnv* env, int i) {
    if (!cls_ || !mid_mod_cat_) return 0;
    jint v = env->CallStaticIntMethod(cls_, mid_mod_cat_, i);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

bool JniBridge::gui_module_enabled(JNIEnv* env, int i) {
    if (!cls_ || !mid_mod_enabled_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_mod_enabled_, i);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

bool JniBridge::gui_module_bind_only(JNIEnv* env, int i) {
    if (!cls_ || !mid_mod_bind_only_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_mod_bind_only_, i);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

void JniBridge::gui_toggle(JNIEnv* env, int i) {
    if (!cls_ || !mid_toggle_) return;
    env->CallStaticVoidMethod(cls_, mid_toggle_, i);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

std::string JniBridge::gui_module_key_label(JNIEnv* env, int i) {
    if (!cls_ || !mid_mod_key_label_) return std::string("NONE");
    jstring js = static_cast<jstring>(env->CallStaticObjectMethod(cls_, mid_mod_key_label_, i));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return std::string("NONE"); }
    return jstring_to_std(env, js);
}

void JniBridge::start_rebind(JNIEnv* env, const char* module_name) {
    if (!cls_ || !mid_start_rebind_ || !module_name) return;
    jstring name = env->NewStringUTF(module_name);
    if (!name) return;
    env->CallStaticVoidMethod(cls_, mid_start_rebind_, name);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

bool JniBridge::gui_is_rebind_pending(JNIEnv* env, int i) {
    if (!cls_ || !mid_gui_rebind_pending_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_gui_rebind_pending_, i);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

void JniBridge::set_module_key_code(JNIEnv* env, const char* module_name, int key_code) {
    if (!cls_ || !mid_set_key_by_name_ || !module_name) return;
    jstring name = env->NewStringUTF(module_name);
    if (!name) return;
    env->CallStaticVoidMethod(cls_, mid_set_key_by_name_, name, key_code);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

int JniBridge::get_module_key_code(JNIEnv* env, const char* module_name) {
    if (!cls_ || !mid_get_key_by_name_ || !module_name) return -1;
    jstring name = env->NewStringUTF(module_name);
    if (!name) return -1;
    jint v = env->CallStaticIntMethod(cls_, mid_get_key_by_name_, name);
    env->DeleteLocalRef(name);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    return v;
}

int JniBridge::gui_setting_count(JNIEnv* env, int i) {
    if (!cls_ || !mid_set_count_) return 0;
    jint v = env->CallStaticIntMethod(cls_, mid_set_count_, i);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

std::string JniBridge::gui_setting_name(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_name_) return std::string();
    jstring js = static_cast<jstring>(env->CallStaticObjectMethod(cls_, mid_set_name_, i, s));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return std::string(); }
    return jstring_to_std(env, js);
}

int JniBridge::gui_setting_type(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_type_) return -1;
    jint v = env->CallStaticIntMethod(cls_, mid_set_type_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return -1; }
    return v;
}

bool JniBridge::gui_setting_bool(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_bool_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_set_bool_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

void JniBridge::gui_set_bool(JNIEnv* env, int i, int s, bool v) {
    if (!cls_ || !mid_set_bool_w_) return;
    env->CallStaticVoidMethod(cls_, mid_set_bool_w_, i, s, v ? JNI_TRUE : JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

float JniBridge::gui_setting_float(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_float_) return 0.0f;
    jfloat v = env->CallStaticFloatMethod(cls_, mid_set_float_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0.0f; }
    return v;
}

float JniBridge::gui_setting_min(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_min_) return 0.0f;
    jfloat v = env->CallStaticFloatMethod(cls_, mid_set_min_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0.0f; }
    return v;
}

float JniBridge::gui_setting_max(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_max_) return 1.0f;
    jfloat v = env->CallStaticFloatMethod(cls_, mid_set_max_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 1.0f; }
    return v;
}

float JniBridge::gui_setting_step(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_step_) return 0.0f;
    jfloat v = env->CallStaticFloatMethod(cls_, mid_set_step_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0.0f; }
    return v;
}

void JniBridge::gui_set_float(JNIEnv* env, int i, int s, float v) {
    if (!cls_ || !mid_set_float_w_) return;
    env->CallStaticVoidMethod(cls_, mid_set_float_w_, i, s, v);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

int JniBridge::gui_setting_mode(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_mode_) return 0;
    jint v = env->CallStaticIntMethod(cls_, mid_set_mode_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

int JniBridge::gui_setting_mode_count(JNIEnv* env, int i, int s) {
    if (!cls_ || !mid_set_mode_count_) return 0;
    jint v = env->CallStaticIntMethod(cls_, mid_set_mode_count_, i, s);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

std::string JniBridge::gui_setting_mode_name(JNIEnv* env, int i, int s, int m) {
    return call_string(env, mid_set_mode_name_, i, s, m);
}

void JniBridge::gui_set_mode(JNIEnv* env, int i, int s, int v) {
    if (!cls_ || !mid_set_mode_w_) return;
    env->CallStaticVoidMethod(cls_, mid_set_mode_w_, i, s, v);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

bool JniBridge::hud_should_draw(JNIEnv* env) {
    if (!cls_ || !mid_hud_should_draw_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_hud_should_draw_);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

bool JniBridge::hud_show_array(JNIEnv* env) {
    if (!cls_ || !mid_hud_show_array_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_hud_show_array_);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

bool JniBridge::hud_show_notifications(JNIEnv* env) {
    if (!cls_ || !mid_hud_show_notifications_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_hud_show_notifications_);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

int JniBridge::hud_enabled_module_count(JNIEnv* env) {
    if (!cls_ || !mid_hud_enabled_count_) return 0;
    jint v = env->CallStaticIntMethod(cls_, mid_hud_enabled_count_);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

std::string JniBridge::hud_enabled_module_name(JNIEnv* env, int i) {
    if (!cls_ || !mid_hud_enabled_name_) return std::string();
    jstring js = static_cast<jstring>(env->CallStaticObjectMethod(cls_, mid_hud_enabled_name_, i));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return std::string(); }
    return jstring_to_std(env, js);
}

int JniBridge::hud_notification_count(JNIEnv* env) {
    if (!cls_ || !mid_hud_notif_count_) return 0;
    jint v = env->CallStaticIntMethod(cls_, mid_hud_notif_count_);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0; }
    return v;
}

std::string JniBridge::hud_notification_text(JNIEnv* env, int i) {
    if (!cls_ || !mid_hud_notif_text_) return std::string();
    jstring js = static_cast<jstring>(env->CallStaticObjectMethod(cls_, mid_hud_notif_text_, i));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return std::string(); }
    return jstring_to_std(env, js);
}

bool JniBridge::hud_notification_enabled(JNIEnv* env, int i) {
    if (!cls_ || !mid_hud_notif_enabled_) return false;
    jboolean v = env->CallStaticBooleanMethod(cls_, mid_hud_notif_enabled_, i);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return v == JNI_TRUE;
}

float JniBridge::hud_notification_alpha(JNIEnv* env, int i) {
    if (!cls_ || !mid_hud_notif_alpha_) return 0.0f;
    jfloat v = env->CallStaticFloatMethod(cls_, mid_hud_notif_alpha_, i);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return 0.0f; }
    return v;
}

} // namespace gnu

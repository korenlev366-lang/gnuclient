#pragma once

#include <jni.h>

#include <string>

namespace gnu {

// Bridges the native agent to the Forge LaunchClassLoader and the Java payload
// (gnu.client.runtime.NativeBootstrap). All jclass/jmethodID handles are
// resolved once during bootstrap and cached as global refs. Per-frame calls
// (tick/render and GUI accessors) use only cached ids — no reflection.
class JniBridge {
public:
    static JniBridge& instance();

    bool capture_launch_classloader(JNIEnv* env);

    // Capture the CURRENT thread's context classloader. On the render thread this
    // is the real game LaunchClassLoader (the one with initialized Forge/Loader
    // state and deobfuscated MC classes), which can differ from Launch.classLoader.
    bool capture_context_classloader(JNIEnv* env);

    bool add_jar_to_classloader(JNIEnv* env, const std::string& jar_path);

    // Load a class by binary name through the captured LaunchClassLoader.
    // Returns a local jclass reference or nullptr on failure.
    jclass load_class(JNIEnv* env, const char* binary_name);

    // Load gnu.client.runtime.NativeBootstrap through the LaunchClassLoader and
    // cache its class + all static method ids. Does NOT run Java init: Minecraft
    // is not reachable from the worker thread. Returns true if the class loaded.
    bool load_native_bootstrap(JNIEnv* env);

    // Called from the render thread (glXSwapBuffers) each frame until it returns
    // true. Invokes NativeBootstrap.initOnRenderThread(), which resolves Minecraft
    // via reflection and runs init once. Returns true once init has completed.
    bool call_java_init_on_render_thread(JNIEnv* env);

    void call_tick(JNIEnv* env);
    void call_render(JNIEnv* env);
    void reload_scripts(JNIEnv* env);

    bool bootstrapped() const { return bootstrapped_; }
    static std::string resolve_sibling_jar();

    // ---- GUI model accessors (cached-id JNI calls) ----
    int gui_module_count(JNIEnv* env);
    std::string gui_module_name(JNIEnv* env, int i);
    std::string gui_module_desc(JNIEnv* env, int i);
    int gui_module_category(JNIEnv* env, int i);
    bool gui_module_enabled(JNIEnv* env, int i);
    bool gui_module_bind_only(JNIEnv* env, int i);
    void gui_toggle(JNIEnv* env, int i);
    std::string gui_module_key_label(JNIEnv* env, int i);
    void start_rebind(JNIEnv* env, const char* module_name);
    bool gui_is_rebind_pending(JNIEnv* env, int i);
    void set_module_key_code(JNIEnv* env, const char* module_name, int key_code);
    int get_module_key_code(JNIEnv* env, const char* module_name);

    int gui_setting_count(JNIEnv* env, int i);
    std::string gui_setting_name(JNIEnv* env, int i, int s);
    int gui_setting_type(JNIEnv* env, int i, int s); // 0 bool, 1 slider, 2 mode

    bool gui_setting_bool(JNIEnv* env, int i, int s);
    void gui_set_bool(JNIEnv* env, int i, int s, bool v);

    float gui_setting_float(JNIEnv* env, int i, int s);
    float gui_setting_min(JNIEnv* env, int i, int s);
    float gui_setting_max(JNIEnv* env, int i, int s);
    void gui_set_float(JNIEnv* env, int i, int s, float v);

    int gui_setting_mode(JNIEnv* env, int i, int s);
    int gui_setting_mode_count(JNIEnv* env, int i, int s);
    std::string gui_setting_mode_name(JNIEnv* env, int i, int s, int m);
    void gui_set_mode(JNIEnv* env, int i, int s, int v);

    // ---- HUD overlay accessors ----
    bool hud_should_draw(JNIEnv* env);
    bool hud_show_array(JNIEnv* env);
    bool hud_show_notifications(JNIEnv* env);
    int hud_enabled_module_count(JNIEnv* env);
    std::string hud_enabled_module_name(JNIEnv* env, int i);
    int hud_notification_count(JNIEnv* env);
    std::string hud_notification_text(JNIEnv* env, int i);
    bool hud_notification_enabled(JNIEnv* env, int i);
    float hud_notification_alpha(JNIEnv* env, int i);

private:
    JniBridge() = default;

    std::string call_string(JNIEnv* env, jmethodID mid, int a, int b, int c);

    jobject launch_classloader_ = nullptr; // global ref
    jclass cls_ = nullptr;                 // NativeBootstrap (global ref)

    jmethodID mid_init_render_ = nullptr;
    jmethodID mid_tick_ = nullptr;
    jmethodID mid_render_ = nullptr;
    jmethodID mid_reload_scripts_ = nullptr;

    jmethodID mid_mod_count_ = nullptr;
    jmethodID mid_mod_name_ = nullptr;
    jmethodID mid_mod_desc_ = nullptr;
    jmethodID mid_mod_cat_ = nullptr;
    jmethodID mid_mod_enabled_ = nullptr;
    jmethodID mid_mod_bind_only_ = nullptr;
    jmethodID mid_toggle_ = nullptr;
    jmethodID mid_mod_key_label_ = nullptr;
    jmethodID mid_start_rebind_ = nullptr;
    jmethodID mid_gui_rebind_pending_ = nullptr;
    jmethodID mid_set_key_by_name_ = nullptr;
    jmethodID mid_get_key_by_name_ = nullptr;

    jmethodID mid_set_count_ = nullptr;
    jmethodID mid_set_name_ = nullptr;
    jmethodID mid_set_type_ = nullptr;
    jmethodID mid_set_bool_ = nullptr;
    jmethodID mid_set_bool_w_ = nullptr;
    jmethodID mid_set_float_ = nullptr;
    jmethodID mid_set_min_ = nullptr;
    jmethodID mid_set_max_ = nullptr;
    jmethodID mid_set_float_w_ = nullptr;
    jmethodID mid_set_mode_ = nullptr;
    jmethodID mid_set_mode_count_ = nullptr;
    jmethodID mid_set_mode_name_ = nullptr;
    jmethodID mid_set_mode_w_ = nullptr;

    jmethodID mid_hud_should_draw_ = nullptr;
    jmethodID mid_hud_show_array_ = nullptr;
    jmethodID mid_hud_show_notifications_ = nullptr;
    jmethodID mid_hud_enabled_count_ = nullptr;
    jmethodID mid_hud_enabled_name_ = nullptr;
    jmethodID mid_hud_notif_count_ = nullptr;
    jmethodID mid_hud_notif_text_ = nullptr;
    jmethodID mid_hud_notif_enabled_ = nullptr;
    jmethodID mid_hud_notif_alpha_ = nullptr;

    bool bootstrapped_ = false;
};

} // namespace gnu

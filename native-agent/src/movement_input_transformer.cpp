#include "movement_input_transformer.h"
#include "entity_player_transformer.h"
#include "packet_transformer.h"
#include "world_render_transformer.h"

#include "jni_bridge.h"
#include "jvmti_context.h"
#include "log.h"

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

namespace gnu {
namespace {

constexpr const char* kTransformName = "transform";
constexpr const char* kTransformSig = "(Ljava/lang/String;[B)[B";

constexpr const char* kTargets[] = {
    "net/minecraft/client/settings/KeyboardInput",
    "net/minecraft/client/settings/MovementInputFromOptions",
    "net/minecraft/util/MovementInputFromOptions",
    "bhd", // KeyboardInput (Notch 1.8.9)
    "bhc", // MovementInputFromOptions (Notch 1.8.9)
    "bew", // MovementInput (Notch 1.8.9)
};

constexpr const char* kEspTargets[] = {
    "net/minecraft/client/entity/EntityPlayerSP",
    "bli", // EntityPlayerSP (Notch 1.8.9)
};

std::atomic<bool> g_installed{false};
std::atomic<int> g_seen_logs{0};
std::atomic<int> g_transform_hits{0};
jclass g_transformer_class = nullptr;
jmethodID g_transform_method = nullptr;

bool is_target_name(const char* name) {
    if (!name)
        return false;
    for (const char* target : kTargets) {
        if (std::strcmp(name, target) == 0)
            return true;
    }
    return false;
}

bool is_esp_target_name(const char* name) {
    if (!name)
        return false;
    for (const char* target : kEspTargets) {
        if (std::strcmp(name, target) == 0)
            return true;
    }
    return false;
}

std::string signature_for(const char* internal_name) {
    return std::string("L") + internal_name + ";";
}

bool is_target_signature(const char* sig) {
    if (!sig)
        return false;
    for (const char* target : kTargets) {
        if (signature_for(target) == sig)
            return true;
    }
    return false;
}

bool is_esp_target_signature(const char* sig) {
    if (!sig)
        return false;
    for (const char* target : kEspTargets) {
        if (signature_for(target) == sig)
            return true;
    }
    return false;
}

void log_jni_exception(JNIEnv* env, const char* where) {
    if (!env || !env->ExceptionCheck())
        return;
    env->ExceptionDescribe();
    env->ExceptionClear();
    log_line(std::string("MIO_TRANSFORM_ JNI exception at ") + where);
}

void JNICALL on_class_file_load(jvmtiEnv* jvmti, JNIEnv* env, jclass, jobject,
                                const char* name, jobject, jint class_data_len,
                                const unsigned char* class_data,
                                jint* new_class_data_len,
                                unsigned char** new_class_data) {
    if (!jvmti || !env || !class_data || class_data_len <= 0 || !new_class_data_len
        || !new_class_data) {
        return;
    }

    if (g_transform_hits.load(std::memory_order_relaxed) == 0) {
        int n = g_seen_logs.fetch_add(1, std::memory_order_relaxed);
        if (n < 100) {
            log_line(std::string("MIO_TRANSFORM_ seen class[") + std::to_string(n)
                     + "] " + (name ? name : "(null)"));
        }
    }

    try_patch_network_manager(jvmti, env, name, class_data_len, class_data,
                              new_class_data_len, new_class_data);
    if (new_class_data && *new_class_data)
        return;

    try_patch_entity_renderer(jvmti, env, name, class_data_len, class_data,
                              new_class_data_len, new_class_data);
    if (new_class_data && *new_class_data)
        return;

    try_patch_entity_player_sp(jvmti, env, name, class_data_len, class_data,
                               new_class_data_len, new_class_data);
    if (new_class_data && *new_class_data)
        return;

    if (!is_target_name(name))
        return;

    g_transform_hits.fetch_add(1, std::memory_order_relaxed);
    log_line(std::string("MIO_TRANSFORM_ hook target ") + name
             + " len=" + std::to_string(class_data_len));

    if (!g_transformer_class || !g_transform_method) {
        log_line("MIO_TRANSFORM_ transformer method unavailable; pass-through");
        return;
    }

    jstring class_name = env->NewStringUTF(name);
    jbyteArray input = env->NewByteArray(class_data_len);
    if (!class_name || !input) {
        log_jni_exception(env, "alloc args");
        return;
    }
    env->SetByteArrayRegion(input, 0, class_data_len,
                            reinterpret_cast<const jbyte*>(class_data));
    if (env->ExceptionCheck()) {
        log_jni_exception(env, "SetByteArrayRegion");
        env->DeleteLocalRef(class_name);
        env->DeleteLocalRef(input);
        return;
    }

    auto output = static_cast<jbyteArray>(
        env->CallStaticObjectMethod(g_transformer_class, g_transform_method, class_name, input));
    env->DeleteLocalRef(class_name);
    env->DeleteLocalRef(input);
    if (env->ExceptionCheck()) {
        log_jni_exception(env, "MovementInputTransformer.transform");
        return;
    }
    if (!output) {
        log_line(std::string("MIO_TRANSFORM_ Java transformer pass-through for ") + name);
        return;
    }

    const jsize out_len = env->GetArrayLength(output);
    if (out_len <= 0) {
        env->DeleteLocalRef(output);
        return;
    }

    unsigned char* buffer = nullptr;
    jvmtiError err = jvmti->Allocate(out_len, &buffer);
    if (err != JVMTI_ERROR_NONE || !buffer) {
        log_line("MIO_TRANSFORM_ jvmti Allocate failed err=" + std::to_string(err));
        env->DeleteLocalRef(output);
        return;
    }

    env->GetByteArrayRegion(output, 0, out_len, reinterpret_cast<jbyte*>(buffer));
    env->DeleteLocalRef(output);
    if (env->ExceptionCheck()) {
        log_jni_exception(env, "GetByteArrayRegion");
        jvmti->Deallocate(buffer);
        return;
    }

    *new_class_data_len = out_len;
    *new_class_data = buffer;
    log_line(std::string("MIO_TRANSFORM_ patched ") + name
             + " new_len=" + std::to_string(out_len));
}

} // namespace

bool MovementInputTransformer::install(JNIEnv* env) {
    if (g_installed.load(std::memory_order_acquire))
        return true;
    if (!env) {
        log_line("MIO_TRANSFORM_ install failed: null JNIEnv");
        return false;
    }

    JvmtiContext& ctx = JvmtiContext::instance();
    jvmtiEnv* jvmti = ctx.jvmti();
    if (!jvmti || !ctx.has_retransform()) {
        log_line("MIO_TRANSFORM_ install failed: JVMTI retransform unavailable");
        return false;
    }

    jclass local_transformer =
        JniBridge::instance().load_class(env, "gnu.client.runtime.MovementInputTransformer");
    if (!local_transformer || env->ExceptionCheck()) {
        log_jni_exception(env, "loadClass(MovementInputTransformer)");
        return false;
    }
    g_transformer_class = static_cast<jclass>(env->NewGlobalRef(local_transformer));
    env->DeleteLocalRef(local_transformer);
    if (!g_transformer_class) {
        log_line("MIO_TRANSFORM_ install failed: NewGlobalRef transformer");
        return false;
    }

    g_transform_method = env->GetStaticMethodID(g_transformer_class, kTransformName, kTransformSig);
    if (!g_transform_method || env->ExceptionCheck()) {
        log_jni_exception(env, "GetStaticMethodID(transform)");
        return false;
    }

    jvmtiEventCallbacks callbacks{};
    callbacks.ClassFileLoadHook = &on_class_file_load;
    jvmtiError err = jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
    if (err != JVMTI_ERROR_NONE) {
        log_line("MIO_TRANSFORM_ SetEventCallbacks failed err=" + std::to_string(err));
        return false;
    }

    err = jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
    if (err != JVMTI_ERROR_NONE) {
        log_line("MIO_TRANSFORM_ enable ClassFileLoadHook failed err=" + std::to_string(err));
        return false;
    }

    jint class_count = 0;
    jclass* classes = nullptr;
    err = jvmti->GetLoadedClasses(&class_count, &classes);
    if (err != JVMTI_ERROR_NONE || !classes) {
        log_line("MIO_TRANSFORM_ GetLoadedClasses failed err=" + std::to_string(err));
        return false;
    }

    std::vector<jclass> targets;
    auto add_target = [&](jclass cl, const char* label) {
        if (!cl)
            return;
        for (jclass existing : targets) {
            if (existing == cl)
                return;
        }
        targets.push_back(cl);
        log_line(std::string("MIO_TRANSFORM_ found loaded target ") + label);
    };

    for (jint i = 0; i < class_count; ++i) {
        char* sig = nullptr;
        if (jvmti->GetClassSignature(classes[i], &sig, nullptr) == JVMTI_ERROR_NONE && sig) {
            if (is_target_signature(sig) || is_esp_target_signature(sig))
                add_target(classes[i], sig);
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
        }
    }

    // KeyboardInput overrides updatePlayerMoveState without calling super — must patch
    // directly. Often already loaded before GetLoadedClasses sees MovementInputFromOptions.
    const char* eager_load[] = {
        "net.minecraft.client.settings.KeyboardInput",
        "net.minecraft.client.settings.MovementInputFromOptions",
        "net.minecraft.util.MovementInputFromOptions",
        "net.minecraft.client.entity.EntityPlayerSP",
        nullptr,
    };
    for (const char** name = eager_load; *name; ++name) {
        jclass cl = JniBridge::instance().load_class(env, *name);
        if (cl && !env->ExceptionCheck())
            add_target(cl, *name);
        else if (env->ExceptionCheck())
            env->ExceptionClear();
    }

    if (!targets.empty()) {
        g_transform_hits.store(0, std::memory_order_release);
        err = jvmti->RetransformClasses(static_cast<jint>(targets.size()), targets.data());
        if (err != JVMTI_ERROR_NONE) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
            log_line("MIO_TRANSFORM_ RetransformClasses failed err=" + std::to_string(err));
            return false;
        }
        log_line("MIO_TRANSFORM_ RetransformClasses OK count="
                 + std::to_string(targets.size()) + " hook_hits="
                 + std::to_string(g_transform_hits.load(std::memory_order_relaxed)));
    } else {
        log_line("MIO_TRANSFORM_ no loaded target class found; first 100 future class names will log");
    }

    jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    g_installed.store(true, std::memory_order_release);
    return true;
}

} // namespace gnu

#include "packet_transformer.h"

#include "jni_bridge.h"
#include "jvmti_context.h"
#include "log.h"

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

namespace gnu {
namespace {

constexpr const char* kTargetNames[] = {
    "net/minecraft/network/NetworkManager",
    "ek", // Notch 1.8.9 (Lunar/Badlion/Vanilla)
};
constexpr const char* kTransformName = "transform";
constexpr const char* kTransformSig = "(Ljava/lang/String;[B)[B";

std::atomic<bool> g_installed{false};
std::atomic<int> g_transform_hits{0};

jclass g_transformer_class = nullptr;
jmethodID g_transform_method = nullptr;

bool is_target_name(const char* name) {
    if (!name)
        return false;
    for (const char* target : kTargetNames) {
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
    for (const char* target : kTargetNames) {
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
    log_line(std::string("PKT_TRANSFORM_ JNI exception at ") + where);
}

bool ensure_transformer_loaded(JNIEnv* env) {
    if (g_transformer_class && g_transform_method)
        return true;
    if (!env)
        return false;

    jclass local_transformer =
        JniBridge::instance().load_class(env, "gnu.client.runtime.PacketTransformer");
    if (!local_transformer || env->ExceptionCheck()) {
        log_jni_exception(env, "loadClass(PacketTransformer)");
        return false;
    }
    g_transformer_class = static_cast<jclass>(env->NewGlobalRef(local_transformer));
    env->DeleteLocalRef(local_transformer);
    if (!g_transformer_class) {
        log_line("PKT_TRANSFORM_ ensure failed: NewGlobalRef transformer");
        return false;
    }

    g_transform_method = env->GetStaticMethodID(g_transformer_class, kTransformName, kTransformSig);
    if (!g_transform_method || env->ExceptionCheck()) {
        log_jni_exception(env, "GetStaticMethodID(transform)");
        return false;
    }
    return true;
}

} // namespace

void try_patch_network_manager(jvmtiEnv* jvmti, JNIEnv* env, const char* name,
                               jint class_data_len, const unsigned char* class_data,
                               jint* new_class_data_len, unsigned char** new_class_data) {
    if (!jvmti || !env || !name || !class_data || class_data_len <= 0 || !new_class_data_len
        || !new_class_data) {
        return;
    }
    if (!is_target_name(name))
        return;

    g_transform_hits.fetch_add(1, std::memory_order_relaxed);
    log_line(std::string("PKT_TRANSFORM_ hook target ") + name
             + " len=" + std::to_string(class_data_len));

    if (!ensure_transformer_loaded(env)) {
        log_line("PKT_TRANSFORM_ transformer method unavailable; pass-through");
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
        log_jni_exception(env, "PacketTransformer.transform");
        return;
    }
    if (!output) {
        log_line(std::string("PKT_TRANSFORM_ Java transformer pass-through for ") + name);
        return;
    }

    const jsize out_len = env->GetArrayLength(output);
    if (out_len <= 0) {
        env->DeleteLocalRef(output);
        return;
    }

    unsigned char* out_buf = nullptr;
    jvmtiError err = jvmti->Allocate(out_len, &out_buf);
    if (err != JVMTI_ERROR_NONE || !out_buf) {
        log_line("PKT_TRANSFORM_ jvmti Allocate failed err=" + std::to_string(err));
        env->DeleteLocalRef(output);
        return;
    }

    env->GetByteArrayRegion(output, 0, out_len, reinterpret_cast<jbyte*>(out_buf));
    env->DeleteLocalRef(output);

    if (env->ExceptionCheck()) {
        log_jni_exception(env, "GetByteArrayRegion");
        jvmti->Deallocate(out_buf);
        return;
    }

    *new_class_data_len = out_len;
    *new_class_data = out_buf;
    log_line("PKT_TRANSFORM_ patched NetworkManager bytes=" + std::to_string(class_data_len)
             + " new_len=" + std::to_string(out_len));
}

bool PacketTransformerInstall::install(JNIEnv* env) {
    if (g_installed.load(std::memory_order_acquire))
        return true;
    if (!env) {
        log_line("PKT_TRANSFORM_ install failed: null JNIEnv");
        return false;
    }

    JvmtiContext& ctx = JvmtiContext::instance();
    jvmtiEnv* jvmti = ctx.jvmti();
    if (!jvmti || !ctx.has_retransform()) {
        log_line("PKT_TRANSFORM_ install failed: JVMTI retransform unavailable");
        return false;
    }

    if (!ensure_transformer_loaded(env)) {
        log_line("PKT_TRANSFORM_ install failed: load PacketTransformer");
        return false;
    }

    jint class_count = 0;
    jclass* classes = nullptr;
    jvmtiError err = jvmti->GetLoadedClasses(&class_count, &classes);
    if (err != JVMTI_ERROR_NONE || !classes) {
        log_line("PKT_TRANSFORM_ GetLoadedClasses failed err=" + std::to_string(err));
        return false;
    }

    std::vector<jclass> targets;
    for (jint i = 0; i < class_count; ++i) {
        char* sig = nullptr;
        if (jvmti->GetClassSignature(classes[i], &sig, nullptr) == JVMTI_ERROR_NONE && sig) {
            if (is_target_signature(sig)) {
                targets.push_back(classes[i]);
                log_line(std::string("PKT_TRANSFORM_ found loaded target ") + sig);
            }
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
        }
    }

    if (!targets.empty()) {
        g_transform_hits.store(0, std::memory_order_release);
        err = jvmti->RetransformClasses(static_cast<jint>(targets.size()), targets.data());
        if (err != JVMTI_ERROR_NONE) {
            jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
            log_line("PKT_TRANSFORM_ RetransformClasses failed err=" + std::to_string(err));
            return false;
        }
        log_line("PKT_TRANSFORM_ RetransformClasses OK count="
                 + std::to_string(targets.size()) + " hook_hits="
                 + std::to_string(g_transform_hits.load(std::memory_order_relaxed)));
    } else {
        log_line("PKT_TRANSFORM_ no loaded NetworkManager yet; hook will apply on first load");
    }

    jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    g_installed.store(true, std::memory_order_release);
    return true;
}

} // namespace gnu

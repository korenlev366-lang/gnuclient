#include "entity_living_base_transformer.h"

#include "jni_bridge.h"
#include "jvmti_context.h"
#include "log.h"

#include <atomic>
#include <cstring>
#include <string>

namespace gnu {
namespace {

constexpr const char* kTargetNames[] = {
    "net/minecraft/entity/EntityLivingBase",
    "pr",
};
constexpr const char* kTransformName = "transform";
constexpr const char* kTransformSig = "(Ljava/lang/String;[B)[B";

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

void log_jni_exception(JNIEnv* env, const char* where) {
    if (!env || !env->ExceptionCheck())
        return;
    env->ExceptionDescribe();
    env->ExceptionClear();
    log_line(std::string("ELB_TRANSFORM_ JNI exception at ") + where);
}

bool ensure_transformer_loaded(JNIEnv* env) {
    if (g_transformer_class && g_transform_method)
        return true;
    if (!env)
        return false;

    jclass local_transformer =
        JniBridge::instance().load_class(env, "gnu.client.runtime.EntityLivingBaseTransformer");
    if (!local_transformer || env->ExceptionCheck()) {
        log_jni_exception(env, "loadClass(EntityLivingBaseTransformer)");
        return false;
    }
    g_transformer_class = static_cast<jclass>(env->NewGlobalRef(local_transformer));
    env->DeleteLocalRef(local_transformer);
    if (!g_transformer_class) {
        log_line("ELB_TRANSFORM_ ensure failed: NewGlobalRef transformer");
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

void try_patch_entity_living_base(jvmtiEnv* jvmti, JNIEnv* env, const char* name,
                                  jint class_data_len, const unsigned char* class_data,
                                  jint* new_class_data_len, unsigned char** new_class_data) {
    if (!jvmti || !env || !name || !class_data || class_data_len <= 0 || !new_class_data_len
        || !new_class_data) {
        return;
    }
    if (!is_target_name(name))
        return;

    g_transform_hits.fetch_add(1, std::memory_order_relaxed);
    log_line(std::string("ELB_TRANSFORM_ hook target ") + name
             + " len=" + std::to_string(class_data_len));

    if (!ensure_transformer_loaded(env)) {
        log_line("ELB_TRANSFORM_ transformer method unavailable; pass-through");
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
        log_jni_exception(env, "EntityLivingBaseTransformer.transform");
        return;
    }
    if (!output) {
        log_line(std::string("ELB_TRANSFORM_ Java transformer pass-through for ") + name);
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
        log_line("ELB_TRANSFORM_ jvmti Allocate failed err=" + std::to_string(err));
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
    log_line("ELB_TRANSFORM_ patched EntityLivingBase bytes=" + std::to_string(class_data_len)
             + " new_len=" + std::to_string(out_len));
}

} // namespace gnu

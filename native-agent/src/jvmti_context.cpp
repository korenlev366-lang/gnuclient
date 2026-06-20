#include "jvmti_context.h"
#include "log.h"

#include <string>

namespace gnu {

JvmtiContext& JvmtiContext::instance() {
    static JvmtiContext inst;
    return inst;
}

bool JvmtiContext::initialize(JavaVM* vm) {
    vm_ = vm;

    jint res = vm_->GetEnv(reinterpret_cast<void**>(&jvmti_), JVMTI_VERSION_1_2);
    if (res != JNI_OK || jvmti_ == nullptr) {
        log_line("NATIVE_ jvmti GetEnv(JVMTI_VERSION_1_2) failed res=" + std::to_string(res));
        return false;
    }

    jvmtiCapabilities redefine{};
    redefine.can_redefine_classes = 1;
    jvmtiError err = jvmti_->AddCapabilities(&redefine);
    log_line("NATIVE_ AddCapabilities(can_redefine_classes) -> " + std::to_string(err));
    if (err != JVMTI_ERROR_NONE) {
        log_line("NATIVE_ required capability can_redefine_classes unavailable");
        return false;
    }

    jvmtiCapabilities retransform{};
    retransform.can_retransform_classes = 1;
    err = jvmti_->AddCapabilities(&retransform);
    has_retransform_ = (err == JVMTI_ERROR_NONE);
    log_line("NATIVE_ AddCapabilities(can_retransform_classes) -> " + std::to_string(err)
             + (has_retransform_ ? " (available)" : " (unavailable)"));

    log_line("NATIVE_ jvmti context initialized");
    return true;
}

JNIEnv* JvmtiContext::attach_current_thread() {
    if (!vm_)
        return nullptr;
    JNIEnv* env = nullptr;
    jint res = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (vm_->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK)
            return nullptr;
    } else if (res != JNI_OK) {
        return nullptr;
    }
    return env;
}

void JvmtiContext::detach_current_thread() {
    if (vm_)
        vm_->DetachCurrentThread();
}

} // namespace gnu

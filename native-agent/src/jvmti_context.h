#pragma once

#include <jni.h>
#include <jvmti.h>

namespace gnu {

// Owns the process-wide JavaVM + jvmtiEnv and the capabilities negotiated at
// attach time. Mirrors RainClient's JvmtiAgent capability handshake.
class JvmtiContext {
public:
    static JvmtiContext& instance();

    // Called from Agent_OnAttach. Acquires jvmtiEnv (JVMTI_VERSION_1_2) and
    // requests can_redefine_classes (required) + can_retransform_classes
    // (optional). Returns false only if the required capability is unavailable.
    bool initialize(JavaVM* vm);

    JavaVM* vm() const { return vm_; }
    jvmtiEnv* jvmti() const { return jvmti_; }
    bool has_retransform() const { return has_retransform_; }

    // Attach the calling (native) thread to the JVM and return its JNIEnv.
    JNIEnv* attach_current_thread();
    void detach_current_thread();

private:
    JvmtiContext() = default;

    JavaVM* vm_ = nullptr;
    jvmtiEnv* jvmti_ = nullptr;
    bool has_retransform_ = false;
};

} // namespace gnu

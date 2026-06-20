#pragma once

#include <jni.h>
#include <jvmti.h>

namespace gnu {

class PacketTransformerInstall {
public:
    static bool install(JNIEnv* env);
};

/** Called from the shared ClassFileLoadHook; patches NetworkManager when matched. */
void try_patch_network_manager(jvmtiEnv* jvmti, JNIEnv* env, const char* name,
                               jint class_data_len, const unsigned char* class_data,
                               jint* new_class_data_len, unsigned char** new_class_data);

} // namespace gnu

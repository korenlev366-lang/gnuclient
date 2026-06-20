#pragma once

#include <jni.h>
#include <jvmti.h>

namespace gnu {

void try_patch_entity_player_sp(jvmtiEnv* jvmti, JNIEnv* env, const char* name,
                                jint class_data_len, const unsigned char* class_data,
                                jint* new_class_data_len, unsigned char** new_class_data);

} // namespace gnu

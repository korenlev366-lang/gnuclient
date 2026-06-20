#pragma once

#include <jni.h>

namespace gnu {

class MovementInputTransformer {
public:
    static bool install(JNIEnv* env);
};

} // namespace gnu

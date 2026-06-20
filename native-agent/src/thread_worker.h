#pragma once

#include <jni.h>

namespace gnu {

// Spawns the detached native worker thread that drives bootstrap. Uses
// std::thread + JavaVM::AttachCurrentThread (RainClient's proven pattern); does
// NOT use JVMTI RunAgentThread.
class ThreadWorker {
public:
    static void start(JavaVM* vm);
};

} // namespace gnu

#include <jni.h>
#include <jvmti.h>

#include <mutex>

#include "jvmti_context.h"
#include "log.h"
#include "thread_worker.h"

namespace {

std::once_flag g_init_flag;

void do_init(JavaVM* vm) {
    gnu::log_line("NATIVE_ Agent_OnAttach entered");
    if (!gnu::JvmtiContext::instance().initialize(vm)) {
        gnu::log_line("NATIVE_ jvmti init failed; aborting agent startup");
        return;
    }
    gnu::ThreadWorker::start(vm);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(
    JavaVM* vm,
    [[maybe_unused]] char* options,
    [[maybe_unused]] void* reserved) {
    std::call_once(g_init_flag, do_init, vm);
    return JNI_OK;
}

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(
    JavaVM* vm,
    [[maybe_unused]] char* options,
    [[maybe_unused]] void* reserved) {
    std::call_once(g_init_flag, do_init, vm);
    return JNI_OK;
}

extern "C" JNIEXPORT void JNICALL Agent_OnUnload([[maybe_unused]] JavaVM* vm) {
    gnu::log_line("NATIVE_ Agent_OnUnload");
}

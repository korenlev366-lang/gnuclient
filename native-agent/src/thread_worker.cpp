#include "thread_worker.h"
#include "glx_hook.h"
#include "jni_bridge.h"
#include "jvmti_context.h"
#include "log.h"

#include <chrono>
#include <thread>

namespace gnu {

void ThreadWorker::start(JavaVM* /*vm*/) {
    std::thread([]() {
        // All JVM classloader work (capture game CL, addURL, define classes,
        // init) happens on the RENDER thread in the glXSwapBuffers hook, because
        // only there is the real game LaunchClassLoader reachable as the thread
        // context loader. The worker just installs the hook.
        if (!GlxHook::install()) {
            log_line("NATIVE_ worker: GLX hook install failed");
            return;
        }
        log_line("NATIVE_ worker: GLX hook installed (Java setup deferred to render thread)");
    }).detach();
}

} // namespace gnu

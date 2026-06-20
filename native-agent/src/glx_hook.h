#pragma once

namespace gnu {

// Installs a funchook trampoline on glXSwapBuffers. Per frame it renders the
// ImGui overlay (when open) and fires the Java module tick at 20 Hz, then calls
// the original swap. Mirrors RainClient's GlxHook.
class GlxHook {
public:
    static bool install();
    static void uninstall();
};

} // namespace gnu

package gnu.client.runtime;

import gnu.client.module.modules.movement.TimerModule;
import gnu.client.runtime.mc.ClientProfile;
import gnu.client.runtime.mc.McAccess;

/**
 * Invoked from a JVMTI hook in {@code EntityRenderer.renderWorld} on the Minecraft
 * render thread (LWJGL context active). The glXSwapBuffers JNI path cannot call
 * {@code GL11} — LWJGL binds context per Java thread, not per OS thread.
 */
public final class WorldRenderHook {

    private WorldRenderHook() {}

    public static void onWorldRender(float partialTicks) {
        if (!McAccess.isResolved() || ClientProfile.current().usesForgeEvents())
            return;
        TimerModule.maintain();
        if (!McAccess.isInGame())
            return;
        VanillaModuleDriver.renderPartial(partialTicks);
    }
}

package gnu.client.runtime;

import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.combat.ReachModule;
import gnu.client.module.modules.combat.WTapModule;
import gnu.client.module.modules.combat.AutoBlockModule;
import gnu.client.module.modules.network.BacktrackModule;
import gnu.client.runtime.mc.McAccess;

/**
 * Module lifecycle for non-Forge clients (Lunar, Badlion, Vanilla). Driven from
 * native {@code glXSwapBuffers} at 20 Hz ({@link NativeBootstrap#tick()}) and
 * per-frame ({@link NativeBootstrap#render()}), mirroring Timewarp's native hook
 * path instead of {@code MinecraftForge.EVENT_BUS}.
 */
public final class VanillaModuleDriver {

    private static boolean prevPhysicalLmb;
    private static boolean runtimeProbeLogged;

    private VanillaModuleDriver() {}

    public static void tick() {
        if (!McAccess.isResolved())
            return;

                NativeBootstrap.handleRebindKeyboard();
        Object mc = McAccess.getMinecraft();
        if (McAccess.currentScreen(mc) != null || NativeBootstrap.isRebindActive())
            return;

        ModuleManager.INSTANCE.handleKeybinds();
        if (!McAccess.isInGame())
            return;

        if (!runtimeProbeLogged) {
            runtimeProbeLogged = true;
            McAccess.logRuntimeProbe();
        }

        boolean lmb = McAccess.isPhysicalLmbDown();
        if (lmb && !prevPhysicalLmb)
            ReachModule.applyIfEnabled();

        ModuleManager.INSTANCE.tickStart();
        ModuleManager.INSTANCE.tick();
        prevPhysicalLmb = lmb;
    }

    /** Called from glXSwapBuffers JNI — LWJGL has no context here; use {@link #renderPartial}. */
    public static void render() {
        // World/overlay GL draws run from WorldRenderHook on the game render thread.
    }

    /** Called from {@link WorldRenderHook} during EntityRenderer.renderWorld. */
    public static void renderPartial(float partialTicks) {
        if (!McAccess.isResolved() || !McAccess.isInGame())
            return;

        ModuleManager.INSTANCE.renderWorld(partialTicks);
        Object scaled = McAccess.createScaledResolution();
        if (scaled != null)
            ModuleManager.INSTANCE.overlay(scaled);
    }

    /** Packet/attack hooks may call this when a swing targets an entity. */
    public static void noteAttack(Object target) {
        if (!McAccess.isInGame() || target == null)
            return;

        Module backtrack = ModuleManager.INSTANCE.getModule("Back Track");
        if (backtrack instanceof BacktrackModule && backtrack.isEnabled())
            ((BacktrackModule) backtrack).noteForgeAttack(target);

        Module wTap = ModuleManager.INSTANCE.getModule("W Tap");
        if (wTap instanceof WTapModule && wTap.isEnabled())
            ((WTapModule) wTap).noteForgeAttack(target);
        Module autoBlock = ModuleManager.INSTANCE.getModule("Auto Block");
        if (autoBlock instanceof AutoBlockModule && autoBlock.isEnabled())
            ((AutoBlockModule) autoBlock).noteAttack(target);
    }
}

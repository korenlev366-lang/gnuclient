package gnu.client.runtime;

import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.combat.ReachModule;
import gnu.client.module.modules.combat.WTapModule;
import gnu.client.module.modules.combat.AutoBlockModule;
import gnu.client.module.modules.network.BacktrackModule;
import gnu.client.module.modules.network.LagrangeModule;
import gnu.client.runtime.mc.McAccess;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Drives module lifecycle on the Forge event bus ({@link NativeBootstrap#init()}).
 *
 * <ul>
 *   <li>{@link TickEvent.ClientTickEvent} START — keybind rebind + {@link ModuleManager#tickStart()}
 *       (Reach applies before click processing)</li>
 *   <li>{@link MouseEvent} — {@link ReachModule#applyIfEnabled()} on left press</li>
 *   <li>{@link TickEvent.ClientTickEvent} END — {@link ModuleManager#tick()}</li>
 *   <li>{@link RenderWorldLastEvent} — {@link ModuleManager#renderWorld(float)}
 *       (3D world-space: ESP, Tracers, ItemESP, BedESP, network ghosts)</li>
 *   <li>{@link RenderGameOverlayEvent.Post} ALL —
 *       {@link ModuleManager#overlay(Object)} (2D: NameTags)</li>
 * </ul>
 *
 * No compile-time {@code net.minecraft.*} references. Per-module guards use
 * {@link McAccess}.
 */
public final class ClientEventListener {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (!McAccess.isResolved())
            return;
                if (event.phase == TickEvent.Phase.START) {
            NativeBootstrap.handleRebindKeyboard();
            if (McAccess.currentScreen(McAccess.getMinecraft()) == null
                    && !NativeBootstrap.isRebindActive()) {
                ModuleManager.INSTANCE.handleKeybinds();
                if (McAccess.isInGame())
                    ModuleManager.INSTANCE.tickStart();
            }
            return;
        }
        if (event.phase != TickEvent.Phase.END)
            return;
        ModuleManager.INSTANCE.tick();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        if (!McAccess.isInGame())
            return;
        if (event.button == 1) {
            // Cancel right-click when AutoBlock is active and holding a sword
            Module autoBlock = ModuleManager.INSTANCE.getModule("Auto Block");
            if (autoBlock instanceof AutoBlockModule && autoBlock.isEnabled()) {
                Object player = McAccess.thePlayer();
                if (player != null) {
                    Object stack = McAccess.invoke(player, "func_70694_bm", new Class<?>[0]);
                    if (stack != null) {
                        Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
                        if (item != null) {
                            Class<?> swordCls = McAccess.gameClass("net.minecraft.item.ItemSword");
                            if (swordCls != null && swordCls.isInstance(item)) {
                                event.setCanceled(true);
                                return;
                            }
                        }
                    }
                }
            }
        }
        if (event.button != 0 || !event.buttonstate)
            return;
        ReachModule.applyIfEnabled();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!McAccess.isInGame())
            return;
        ModuleManager.INSTANCE.renderWorld(event.partialTicks);
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL)
            return;
        if (!McAccess.isInGame())
            return;
        ModuleManager.INSTANCE.overlay(event.resolution);
    }

    @SubscribeEvent
    public void onRenderName(RenderLivingEvent.Specials.Pre<?> event) {
        Module nametags = ModuleManager.INSTANCE.getModule("NameTags");
        if (nametags != null && nametags.isEnabled())
            event.setCanceled(true);
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!McAccess.isInGame())
            return;
        Module backtrack = ModuleManager.INSTANCE.getModule("Back Track");
        if (backtrack instanceof BacktrackModule && backtrack.isEnabled())
            ((BacktrackModule) backtrack).noteForgeAttack(event.target);

        Module wTap = ModuleManager.INSTANCE.getModule("W Tap");
        if (wTap instanceof WTapModule && wTap.isEnabled())
            ((WTapModule) wTap).noteForgeAttack(event.target);
        Module autoBlock = ModuleManager.INSTANCE.getModule("Auto Block");
        if (autoBlock instanceof AutoBlockModule && autoBlock.isEnabled())
            ((AutoBlockModule) autoBlock).noteAttack(event.target);

        Module lagrange = ModuleManager.INSTANCE.getModule("Lagrange");
        if (lagrange instanceof LagrangeModule && lagrange.isEnabled())
            ((LagrangeModule) lagrange).noteForgeAttack(event.target);
    }
}

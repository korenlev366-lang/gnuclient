package gnu.client.runtime;

import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.player.ScaffoldModule;
import gnu.client.runtime.mc.McAccess;

/**
 * OpenMyau-style scaffold item-spoof render hooks.
 *
 * <p>Scaffold keeps {@code currentItem} on the block stack during gameplay
 * (OpenMyau parity). While item-spoof is active, rendering temporarily swaps
 * {@code currentItem} back to the pre-scaffold slot so the hand/hotbar show
 * the original item.
 */
public final class ScaffoldItemSpoofHook {

    private static int savedSlot = -1;
    private static int spoofDepth;

    private ScaffoldItemSpoofHook() {}

    /** True while render hooks temporarily swapped {@code currentItem}. */
    public static boolean isRenderSpoofActive() {
        return spoofDepth > 0;
    }

    public static void beginRenderSlotSpoof() {
        Object player = McAccess.thePlayer();
        ScaffoldModule scaffold = activeScaffold();
        if (player == null || scaffold == null)
            return;
        int spoof = scaffold.getSpoofSlot();
        if (spoof < 0 || spoof > 8)
            return;
        if (spoofDepth == 0) {
            savedSlot = McAccess.getHotbarSlot(player);
            if (savedSlot != spoof)
                McAccess.setHotbarSlot(player, spoof);
            else
                savedSlot = -1;
        }
        spoofDepth++;
    }

    public static void endRenderSlotSpoof() {
        if (spoofDepth <= 0)
            return;
        spoofDepth--;
        if (spoofDepth != 0 || savedSlot < 0)
            return;
        Object player = McAccess.thePlayer();
        if (player != null)
            McAccess.setHotbarSlot(player, savedSlot);
        savedSlot = -1;
    }

    /**
     * Redirect for {@code InventoryPlayer.getCurrentItem()} inside
     * {@code GuiIngame.updateTick}.
     */
    public static Object redirectCurrentItem(Object inventoryPlayer) {
        ScaffoldModule scaffold = activeScaffold();
        if (scaffold != null && inventoryPlayer != null) {
            int spoof = scaffold.getSpoofSlot();
            if (spoof >= 0 && spoof <= 8)
                return McAccess.getStackInSlot(inventoryPlayer, spoof);
        }
        return McAccess.invoke(inventoryPlayer, "func_70448_g", new Class<?>[0]);
    }

    private static ScaffoldModule activeScaffold() {
        Module module = ModuleManager.instance().getModule("Scaffold");
        if (!(module instanceof ScaffoldModule) || !module.isEnabled())
            return null;
        ScaffoldModule scaffold = (ScaffoldModule) module;
        return scaffold.isItemSpoofEnabled() ? scaffold : null;
    }
}

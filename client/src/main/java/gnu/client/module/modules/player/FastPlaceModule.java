package gnu.client.module.modules.player;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.runtime.mc.McAccess;

/**
 * Removes Minecraft's right-click placement delay, but only when
 * the held item is a block (ItemBlock). Other right-click actions
 * (food, potions, bows, tools, etc.) keep vanilla delay.
 *
 * Ported from RainClient {@code FastPlace} — modified to be block-only.
 *
 * SRG (1.8.9):
 *   rightClickDelayTimer = field_71467_ac (Minecraft)
 *   EntityPlayer.getHeldItem() = func_70694_bm
 *   ItemStack.getItem() = func_77973_b
 */
public final class FastPlaceModule extends Module {

    public FastPlaceModule() {
        super("FastPlace", "Removes right-click delay for block placement only", Category.PLAYER);
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {}

    @Override
    public void onTick() {
        Object mc = McAccess.minecraft();
        if (mc == null)
            return;

        // Only clear delay if holding a block (ItemBlock)
        if (!holdingBlock())
            return;

        McAccess.setInt(mc, "field_71467_ac", 0);
    }

    /**
     * Check whether the player's currently held item is a block
     * (instance of net.minecraft.item.ItemBlock).
     *
     * Uses the same reflection pattern as AimAssistModule.holdingWeapon().
     */
    private static boolean holdingBlock() {
        Object player = McAccess.thePlayer();
        if (player == null)
            return false;

        // Get held ItemStack
        Object stack = McAccess.invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null)
            return false;

        // Get Item from ItemStack
        Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
        if (item == null)
            return false;

        // Check instanceof ItemBlock
        Class<?> itemBlock = McAccess.gameClass("net.minecraft.item.ItemBlock");
        return itemBlock != null && itemBlock.isInstance(item);
    }
}

package gnu.client.script;

import gnu.client.runtime.mc.McAccess;

/**
 * Script-facing {@code inventory} accessor — stateless singleton facade over
 * {@link McAccess}. Exposes hotbar slot reads and per-slot stack queries.
 *
 * <p>Player→{@code InventoryPlayer} resolution uses the same SRG field
 * ({@code field_71071_by}) as {@link McAccess#getHotbarSlot(Object)}; no
 * cached inventory reference is retained across calls.
 */
public final class Inventory {

    public static final Inventory INSTANCE = new Inventory();

    private Inventory() {}

    /**
     * ItemStack in the given hotbar/inventory slot, or {@code null} if the
     * slot is empty, the player is null, or the inventory field is unresolved.
     *
     * @param slot 0–35 (0–8 hotbar)
     */
    public Object getStackInSlot(int slot) {
        Object player = McAccess.thePlayer();
        if (player == null)
            return null;
        Object inv = McAccess.getObject(player, "field_71071_by");
        if (inv == null)
            return null;
        return McAccess.getStackInSlot(inv, slot);
    }

    /**
     * Current hotbar index (0–8), or {@code -1} if the player or inventory
     * is unavailable.
     */
    public int getSlot() {
        Object player = McAccess.thePlayer();
        if (player == null)
            return -1;
        return McAccess.getHotbarSlot(player);
    }
}

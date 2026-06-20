package gnu.client.module.modules.visual;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.util.RenderHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Highlights dropped diamond/emerald/gold/iron items (Raven-bS ItemESP parity).
 *
 * <p>Uses item IDs via reflection (not class name matching) so it works in
 * fully obfuscated notch runtimes where class names like {@code EntityItem}
 * are not visible.</p>
 */
public final class ItemEspModule extends Module {

    // Item IDs (vanilla 1.8.9): diamond=264, emerald=388, gold_ingot=266, iron_ingot=265
    private static final int DIAMOND_ID = 264;
    private static final int EMERALD_ID = 388;
    private static final int GOLD_ID = 266;
    private static final int IRON_ID = 265;

    static final class EntityData {
        double lastX;
        double lastY;
        double lastZ;
        double posX;
        double posY;
        double posZ;
        float cr;
        float cg;
        float cb;
    }

    private final BoolSetting diamonds = addSetting(new BoolSetting("Diamonds", true));
    private final BoolSetting emeralds = addSetting(new BoolSetting("Emeralds", true));
    private final BoolSetting gold = addSetting(new BoolSetting("Gold", true));
    private final BoolSetting iron = addSetting(new BoolSetting("Iron", true));
    private final SliderSetting maxDist = addSetting(new SliderSetting("Max Distance", 64.0f, 16.0f, 128.0f));

    private final List<EntityData> cache = new ArrayList<>();

    // Reflection caches
    private Class<?> entityItemClass;
    private Class<?> itemClass;
    private boolean reflectionFailed;

    public ItemEspModule() {
        super("ItemESP", "Highlight valuable dropped items", Category.VISUALS);
    }

    @Override
    public void onEnable() {
        cache.clear();
        reflectionFailed = false;
    }

    @Override
    public void onDisable() {
        cache.clear();
    }

    @Override
    public void onTick() {
        cache.clear();

        Object player = McAccess.thePlayer();
        Object world = McAccess.theWorld();
        if (player == null || world == null)
            return;

        ensureReflectionCache();
        if (reflectionFailed)
            return;

        double px = McAccess.entityPosX(player);
        double py = McAccess.entityPosY(player);
        double pz = McAccess.entityPosZ(player);
        double maxDistSq = maxDist.getValue() * maxDist.getValue();

        List<?> entities = McAccess.getWorldEntitiesFiltered(world);
        for (Object entity : entities) {
            if (entity == null)
                continue;
            if (!entityItemClass.isInstance(entity))
                continue;

            // EntityItem.getEntityItem() -> ItemStack
            Object stack = McAccess.invoke(entity, "func_92059_d", new Class<?>[0]);
            if (stack == null)
                continue;

            // ItemStack.getItem()
            Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
            if (item == null)
                continue;

            int itemId = getItemId(item);
            float[] color = colorForId(itemId);
            if (color == null)
                continue;

            double posX = McAccess.entityPosX(entity);
            double posY = McAccess.entityPosY(entity);
            double posZ = McAccess.entityPosZ(entity);

            double dx = posX - px;
            double dy = posY - py;
            double dz = posZ - pz;
            if (dx * dx + dy * dy + dz * dz > maxDistSq)
                continue;

            EntityData data = new EntityData();
            data.lastX = McAccess.entityLastX(entity);
            data.lastY = McAccess.entityLastY(entity);
            data.lastZ = McAccess.entityLastZ(entity);
            data.posX = posX;
            data.posY = posY;
            data.posZ = posZ;
            data.cr = color[0];
            data.cg = color[1];
            data.cb = color[2];
            cache.add(data);
        }
    }

    @Override
    public void onRender(float partialTicks) {
        if (cache.isEmpty())
            return;

        Object mc = McAccess.getMinecraft();
        if (mc == null)
            return;
        double[] vp = McAccess.getViewerPos(mc, partialTicks);
        double rvpX = vp[0];
        double rvpY = vp[1];
        double rvpZ = vp[2];

        RenderHelper.begin();

        for (EntityData data : cache) {
            double ix = data.lastX + (data.posX - data.lastX) * partialTicks;
            double iy = data.lastY + (data.posY - data.lastY) * partialTicks;
            double iz = data.lastZ + (data.posZ - data.lastZ) * partialTicks;

            double rx = ix - rvpX;
            double ry = iy - rvpY;
            double rz = iz - rvpZ;

            RenderHelper.drawFilledBox(
                    rx - 0.15, ry, rz - 0.15,
                    rx + 0.15, ry + 0.25, rz + 0.15,
                    data.cr, data.cg, data.cb, 0.35f);
            RenderHelper.drawBoundingBox(
                    rx - 0.15, ry, rz - 0.15,
                    rx + 0.15, ry + 0.25, rz + 0.15,
                    data.cr, data.cg, data.cb, 1.0f, 1.5f);
        }

        RenderHelper.end();
    }

    private void ensureReflectionCache() {
        if (entityItemClass != null || reflectionFailed)
            return;
        entityItemClass = McAccess.gameClass("net.minecraft.entity.item.EntityItem");
        itemClass = McAccess.gameClass("net.minecraft.item.Item");
        if (entityItemClass == null || itemClass == null) {
            reflectionFailed = true;
        }
    }

    /**
     * Get the numeric item ID (e.g. 264 for diamond) via reflection.
     * Uses {@code Item.getIdFromItem()} (SRG func_150891_b).
     */
    private int getItemId(Object item) {
        if (item == null || itemClass == null)
            return -1;
        Object result = McAccess.invokeStatic(itemClass, "func_150891_b",
                new Class<?>[] { itemClass }, item);
        if (result instanceof Integer)
            return (Integer) result;
        return -1;
    }

    /**
     * Map item ID to (r,g,b) color, respecting per-item toggle settings.
     * @return float[3] {r, g, b} or null if not a tracked item / toggled off.
     */
    private float[] colorForId(int id) {
        switch (id) {
            case DIAMOND_ID:
                return diamonds.getValue() ? new float[] { 0.3f, 0.9f, 1.0f } : null;
            case EMERALD_ID:
                return emeralds.getValue() ? new float[] { 0.3f, 1.0f, 0.3f } : null;
            case GOLD_ID:
                return gold.getValue() ? new float[] { 1.0f, 0.85f, 0.1f } : null;
            case IRON_ID:
                return iron.getValue() ? new float[] { 0.85f, 0.85f, 0.85f } : null;
            default:
                return null;
        }
    }
}

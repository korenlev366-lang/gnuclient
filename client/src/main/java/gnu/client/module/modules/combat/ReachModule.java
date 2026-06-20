package gnu.client.module.modules.combat;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;

import java.util.List;
import java.util.Random;

/**
 * Extends entity targeting range (raven-bS raytrace + RainClient chance gate).
 *
 * <p>MCP 1.8.9 stable-22 / mcp.thiakil.com field names:
 * <ul>
 *   <li>{@code Minecraft.objectMouseOver} = {@code field_71476_x}</li>
 *   <li>{@code Minecraft.pointedEntity} = {@code field_147125_j}</li>
 *   <li>{@code MovingObjectPosition.entityHit} = {@code field_72308_g}</li>
 *   <li>{@code MovingObjectPosition.hitVec} = {@code field_72307_f}</li>
 *   <li>{@code MovingObjectPosition.typeOfHit} = {@code field_72313_a}</li>
 * </ul>
 *
 * Applied on tick-start (before click processing), render-world-last (after vanilla
 * raytrace), and Forge {@code MouseEvent} (manual / LWJGL clicks).
 */
public final class ReachModule extends Module {

    // MCP 1.8.9 — verified via mcp_stable-22 fields.csv
    private static final String F_OBJECT_MOUSE_OVER = "field_71476_x";
    private static final String F_POINTED_ENTITY = "field_147125_j";
    private static final String F_MOP_ENTITY_HIT = "field_72308_g";
    private static final String F_MOP_HIT_VEC = "field_72307_f";
    private static final String F_MOP_TYPE = "field_72313_a";

    private final SliderSetting distance = addSetting(new SliderSetting("Distance", 4.5f, 3.0f, 6.0f));
    private final SliderSetting chance = addSetting(new SliderSetting("Chance", 100.0f, 0.0f, 100.0f));

    private final Random random = new Random();

    public ReachModule() {
        super("Reach", "Extends entity targeting range", Category.COMBAT);
    }

    /** Called from {@link gnu.client.runtime.ClientEventListener} on left-click. */
    public static void applyIfEnabled() {
        Module m = ModuleManager.INSTANCE.getModule("Reach");
        if (m instanceof ReachModule) {
            ReachModule reach = (ReachModule) m;
            if (reach.isEnabled())
                reach.applyReach();
        }
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {}

    @Override
    public void onTickStart() {
        applyReach();
    }

    @Override
    public void onRender(float partialTicks) {
        applyReach();
    }

    private void applyReach() {
        if (random.nextDouble() * 100.0 > chance.getValue())
            return;

        Object view = McAccess.renderViewEntity();
        Object world = McAccess.theWorld();
        if (view == null || world == null)
            return;

        // Vanilla already resolved an entity at normal range.
        if (McAccess.pointedEntity() != null)
            return;

        Object[] hit = findEntityHit(view, world, distance.getValue(), 1.0f);
        if (hit == null)
            return;

        Object entity = hit[0];
        Object hitVec = hit[1];
        Object mop = createEntityMouseOver(entity, hitVec);
        if (mop == null)
            return;

        Object minecraft = McAccess.minecraft();
        McAccess.setObject(minecraft, F_OBJECT_MOUSE_OVER, mop);
        McAccess.setObject(minecraft, F_POINTED_ENTITY, entity);
    }

    /**
     * Raven-bS {@code Reach.getEntity} — proper eye→AABB intercept, not cone+torso.
     */
    private static Object[] findEntityHit(Object view, Object world, double reach, float partialTicks) {
        Class<?> vec3Cls = McAccess.gameClass("net.minecraft.util.Vec3");
        if (vec3Cls == null)
            return null;

        Object eyes = McAccess.invoke(view, "func_174824_e", new Class<?>[] { float.class }, partialTicks);
        Object look = McAccess.invoke(view, "func_70676_i", new Class<?>[] { float.class }, partialTicks);
        if (eyes == null || look == null)
            return null;

        double lx = McAccess.getDouble(look, "field_72450_a");
        double ly = McAccess.getDouble(look, "field_72448_b");
        double lz = McAccess.getDouble(look, "field_72449_c");

        Object end = McAccess.invoke(eyes, "func_72441_c",
                new Class<?>[] { double.class, double.class, double.class },
                lx * reach, ly * reach, lz * reach);
        if (end == null)
            return null;

        Object viewBox = McAccess.invoke(view, "func_174813_aQ", new Class<?>[0]);
        if (viewBox == null)
            return null;

        Object searchBox = McAccess.invoke(viewBox, "func_72321_a",
                new Class<?>[] { double.class, double.class, double.class },
                lx * reach, ly * reach, lz * reach);
        if (searchBox == null)
            return null;
        searchBox = McAccess.invoke(searchBox, "func_72314_b",
                new Class<?>[] { double.class, double.class, double.class }, 1.0, 1.0, 1.0);
        if (searchBox == null)
            return null;

        Class<?> entityCls = McAccess.gameClass("net.minecraft.entity.Entity");
        Class<?> aabbCls = McAccess.gameClass("net.minecraft.util.AxisAlignedBB");
        if (entityCls == null || aabbCls == null)
            return null;

        Object listObj = McAccess.invoke(world, "func_72839_b",
                new Class<?>[] { entityCls, aabbCls }, view, searchBox);
        if (!(listObj instanceof List))
            return null;
        List<?> entities = (List<?>) listObj;

        Class<?> playerCls = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");

        Object bestEntity = null;
        Object bestHitVec = null;
        double bestDist = reach;

        for (Object entity : entities) {
            if (entity == null)
                continue;
            if (playerCls != null && !playerCls.isInstance(entity))
                continue;

            Object canCollide = McAccess.invoke(entity, "func_70067_L", new Class<?>[0]);
            if (!(canCollide instanceof Boolean) || !(Boolean) canCollide)
                continue;

            Object borderObj = McAccess.invoke(entity, "func_70111_Y", new Class<?>[0]);
            float border = borderObj instanceof Float ? (Float) borderObj : 0.0f;

            Object box = McAccess.invoke(entity, "func_174813_aQ", new Class<?>[0]);
            if (box == null)
                continue;
            box = McAccess.invoke(box, "func_72314_b",
                    new Class<?>[] { double.class, double.class, double.class },
                    (double) border, (double) border, (double) border);
            if (box == null)
                continue;

            Object insideObj = McAccess.invoke(box, "func_72318_a", new Class<?>[] { vec3Cls }, eyes);
            boolean inside = insideObj instanceof Boolean && (Boolean) insideObj;

            Object intercept = McAccess.invoke(box, "func_72327_a",
                    new Class<?>[] { vec3Cls, vec3Cls }, eyes, end);

            if (inside) {
                if (bestDist >= 0.0) {
                    bestEntity = entity;
                    bestHitVec = hitVecFromIntercept(intercept, eyes);
                    bestDist = 0.0;
                }
            } else if (intercept != null) {
                Object hitVec = hitVecFromIntercept(intercept, eyes);
                Object distObj = McAccess.invoke(eyes, "func_72438_d", new Class<?>[] { vec3Cls }, hitVec);
                double dist = distObj instanceof Double ? (Double) distObj : reach;
                if (dist < bestDist || bestDist == 0.0) {
                    bestEntity = entity;
                    bestHitVec = hitVec;
                    bestDist = dist;
                }
            }
        }

        if (bestEntity == null || bestHitVec == null)
            return null;
        return new Object[] { bestEntity, bestHitVec };
    }

    private static Object hitVecFromIntercept(Object intercept, Object fallback) {
        if (intercept != null) {
            Object hit = McAccess.getObject(intercept, F_MOP_HIT_VEC);
            if (hit != null)
                return hit;
        }
        return fallback;
    }

    private static Object createEntityMouseOver(Object entity, Object hitVec) {
        Class<?> entityCls = McAccess.gameClass("net.minecraft.entity.Entity");
        Class<?> vec3Cls = McAccess.gameClass("net.minecraft.util.Vec3");
        if (entityCls == null || vec3Cls == null)
            return null;

        Object mop = McAccess.newInstance("net.minecraft.util.MovingObjectPosition",
                new Class<?>[] { entityCls, vec3Cls }, entity, hitVec);
        if (mop == null) {
            Class<?> typeCls = McAccess.gameClass("net.minecraft.util.MovingObjectPosition$MovingObjectType");
            Class<?> facingCls = McAccess.gameClass("net.minecraft.util.EnumFacing");
            if (typeCls == null)
                return null;
            Object entityType = enumConstant(typeCls, "ENTITY");
            if (entityType == null)
                return null;
            mop = McAccess.newInstance("net.minecraft.util.MovingObjectPosition",
                    new Class<?>[] { typeCls, vec3Cls, facingCls, entityCls },
                    entityType, hitVec, null, entity);
        }
        if (mop == null)
            return null;

        // Belt-and-suspenders: ensure clickMouse() sees ENTITY + entityHit.
        Object entityType = enumConstant(
                McAccess.gameClass("net.minecraft.util.MovingObjectPosition$MovingObjectType"), "ENTITY");
        if (entityType != null)
            McAccess.setObject(mop, F_MOP_TYPE, entityType);
        McAccess.setObject(mop, F_MOP_HIT_VEC, hitVec);
        McAccess.setObject(mop, F_MOP_ENTITY_HIT, entity);
        return mop;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object enumConstant(Class<?> enumCls, String name) {
        if (enumCls == null || !Enum.class.isAssignableFrom(enumCls))
            return null;
        try {
            return Enum.valueOf((Class<? extends Enum>) enumCls.asSubclass(Enum.class), name);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

package gnu.client.module.modules.visual;

import gnu.client.common.GnuLog;
import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.util.RenderHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Player ESP — 3D AABB boxes around other players (OpenMyau ESP mode 3D, simplified).
 */
public final class EspModule extends Module {

    static final class EntityData {
        double lastX;
        double lastY;
        double lastZ;
        double posX;
        double posY;
        double posZ;
        boolean sneaking;
    }

    private final SliderSetting r = addSetting(new SliderSetting("Red", 0.0f, 0.0f, 255.0f));
    private final SliderSetting g = addSetting(new SliderSetting("Green", 255.0f, 0.0f, 255.0f));
    private final SliderSetting b = addSetting(new SliderSetting("Blue", 0.0f, 0.0f, 255.0f));
    private final BoolSetting showSelf = addSetting(new BoolSetting("Show Self", false));
    private final BoolSetting filled = addSetting(new BoolSetting("Filled", false));
    private final SliderSetting lineWidth = addSetting(new SliderSetting("Line Width", 1.0f, 1.0f, 3.0f));

    private final List<EntityData> cache = new ArrayList<>();
    private boolean debugLogged;

    public EspModule() {
        super("ESP", "3D boxes around players", Category.VISUALS);
    }

    @Override
    public void onEnable() {
        debugLogged = false;
        cache.clear();
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

        List<?> entities = McAccess.getWorldEntitiesFiltered(world);
        for (Object entity : entities) {
            if (!showSelf.getValue() && entity == player)
                continue;

            EntityData data = new EntityData();
            data.lastX = McAccess.entityLastX(entity);
            data.lastY = McAccess.entityLastY(entity);
            data.lastZ = McAccess.entityLastZ(entity);
            data.posX = McAccess.entityPosX(entity);
            data.posY = McAccess.entityPosY(entity);
            data.posZ = McAccess.entityPosZ(entity);
            data.sneaking = McAccess.isSneaking(entity);
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

        if (!debugLogged) {
            debugLogged = true;
            EntityData e0 = cache.get(0);
            GnuLog.log("ESP debug: vp=" + rvpX + "," + rvpY + "," + rvpZ
                    + " e0=" + e0.posX + "," + e0.posY + "," + e0.posZ);
        }

        float fr = r.getValue() / 255.0f;
        float fg = g.getValue() / 255.0f;
        float fb = b.getValue() / 255.0f;
        float lw = lineWidth.getValue();

        RenderHelper.begin();

        for (EntityData data : cache) {
            double ix = data.lastX + (data.posX - data.lastX) * partialTicks;
            double iy = data.lastY + (data.posY - data.lastY) * partialTicks;
            double iz = data.lastZ + (data.posZ - data.lastZ) * partialTicks;

            double rx = ix - rvpX;
            double ry = iy - rvpY;
            double rz = iz - rvpZ;

            double height = data.sneaking ? 1.5 : 1.8;

            if (filled.getValue()) {
                RenderHelper.drawFilledBox(
                        rx - 0.3, ry, rz - 0.3,
                        rx + 0.3, ry + height, rz + 0.3,
                        fr, fg, fb, 0.25f);
            }
            RenderHelper.drawBoundingBox(
                    rx - 0.3, ry, rz - 0.3,
                    rx + 0.3, ry + height, rz + 0.3,
                    fr, fg, fb, 1.0f, lw);
        }

        RenderHelper.end();
    }
}

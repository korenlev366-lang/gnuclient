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
 * Lines from the camera to player torsos (raven Tracers, simplified).
 */
public final class TracersModule extends Module {

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
    private final SliderSetting lineWidth = addSetting(new SliderSetting("Width", 1.0f, 1.0f, 3.0f));
    private final BoolSetting showSelf = addSetting(new BoolSetting("Show Self", false));

    private static final double EYE_Y = 1.62;

    private final List<EntityData> cache = new ArrayList<>();

    public TracersModule() {
        super("Tracers", "Lines from camera to players", Category.VISUALS);
    }

    @Override
    public void onEnable() {
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
            double ty = ry + height * 0.5;

            RenderHelper.drawLine3D(
                    0.0, EYE_Y, 0.0,
                    rx, ty, rz,
                    fr, fg, fb, 1.0f, lw);
        }

        RenderHelper.end();
    }
}

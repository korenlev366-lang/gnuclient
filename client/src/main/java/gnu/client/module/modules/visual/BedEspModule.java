package gnu.client.module.modules.visual;

import gnu.client.common.GnuLog;
import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.util.RenderHelper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Highlights bed blocks near the player with configurable max distance (default 64).
 *
 * <p>Scans for {@code BlockBed} instances within the configured range.
 * Uses {@link McAccess#gameClass} for class resolution to handle both
 * MCP/SRG and fully obfuscated notch runtimes. Falls back to simple-name
 * matching when the class cannot be loaded by name.</p>
 */
public final class BedEspModule extends Module {

    private static final int Y_RANGE = 4;
    private static final int SCAN_INTERVAL = 200;

    private final SliderSetting r = addSetting(new SliderSetting("Red", 255.0f, 0.0f, 255.0f));
    private final SliderSetting g = addSetting(new SliderSetting("Green", 85.0f, 0.0f, 255.0f));
    private final SliderSetting b = addSetting(new SliderSetting("Blue", 85.0f, 0.0f, 255.0f));
    private final BoolSetting filled = addSetting(new BoolSetting("Filled", false));
    private final SliderSetting maxDist = addSetting(new SliderSetting("Max Distance", 64.0f, 8.0f, 64.0f));

    private int ticksSinceScan = SCAN_INTERVAL;
    private final List<long[]> bedCache = new ArrayList<>();

    private Constructor<?> cachedBpCtor;
    private Method cachedGetBlockState;
    private Method cachedGetBlock;
    private Class<?> bedBlockClass;
    private boolean reflectionFailed;

    public BedEspModule() {
        super("BedESP", "Highlight nearby beds with configurable max distance", Category.VISUALS);
    }

    @Override
    public void onEnable() {
        ticksSinceScan = SCAN_INTERVAL;
        bedCache.clear();
        reflectionFailed = false;
    }

    @Override
    public void onDisable() {
        bedCache.clear();
        cachedBpCtor = null;
        cachedGetBlockState = null;
        cachedGetBlock = null;
        bedBlockClass = null;
    }

    @Override
    public void onTick() {
        ticksSinceScan++;
        if (ticksSinceScan < SCAN_INTERVAL)
            return;
        ticksSinceScan = 0;

        Object player = McAccess.thePlayer();
        Object world = McAccess.theWorld();
        if (player == null || world == null)
            return;

        double px = McAccess.entityPosX(player);
        double py = McAccess.entityPosY(player);
        double pz = McAccess.entityPosZ(player);
        double maxDistSq = maxDist.getValue() * maxDist.getValue();
        int hr = (int) Math.ceil(maxDist.getValue());

        int ipx = (int) Math.floor(px);
        int ipy = (int) Math.floor(py);
        int ipz = (int) Math.floor(pz);

        ensureReflectionCache(world);
        if (reflectionFailed)
            return;

        bedCache.clear();

        for (int y = ipy - Y_RANGE; y <= ipy + Y_RANGE; y++) {
            for (int x = ipx - hr; x <= ipx + hr; x++) {
                for (int z = ipz - hr; z <= ipz + hr; z++) {
                    double dx = x + 0.5 - px;
                    double dy = y + 0.5 - py;
                    double dz = z + 0.5 - pz;
                    if (dx * dx + dy * dy + dz * dz > maxDistSq)
                        continue;

                    try {
                        Object bp = cachedBpCtor.newInstance(x, y, z);
                        Object state = cachedGetBlockState.invoke(world, bp);
                        if (state == null)
                            continue;
                        Object block = cachedGetBlock.invoke(state);
                        if (block == null)
                            continue;
                        if (isBedBlock(block)) {
                            bedCache.add(packXYZ(x, y, z));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void ensureReflectionCache(Object world) {
        if (reflectionFailed)
            return;

        Class<?> bpClass = McAccess.gameClass("net.minecraft.util.BlockPos");
        if (bpClass == null) {
            reflectionFailed = true;
            return;
        }

        if (cachedBpCtor == null) {
            try {
                cachedBpCtor = bpClass.getDeclaredConstructor(int.class, int.class, int.class);
                cachedBpCtor.setAccessible(true);
            } catch (Exception e) {
                GnuLog.log("BedESP: BlockPos ctor failed: " + e);
                reflectionFailed = true;
                return;
            }
        }

        if (cachedGetBlockState == null) {
            for (Class<?> c = world.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    cachedGetBlockState = c.getDeclaredMethod("func_180495_p", bpClass);
                    cachedGetBlockState.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (cachedGetBlockState == null) {
                reflectionFailed = true;
                return;
            }
        }

        if (cachedGetBlock == null) {
            Class<?> stateClass = McAccess.gameClass("net.minecraft.block.state.IBlockState");
            if (stateClass == null) {
                reflectionFailed = true;
                return;
            }
            for (Class<?> c = stateClass; c != null; c = c.getSuperclass()) {
                try {
                    cachedGetBlock = c.getDeclaredMethod("func_177230_c");
                    cachedGetBlock.setAccessible(true);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (cachedGetBlock == null) {
                reflectionFailed = true;
                return;
            }
        }

        if (bedBlockClass == null) {
            bedBlockClass = McAccess.gameClass("net.minecraft.block.BlockBed");
        }
    }

    /**
     * Check if a block object is a bed. Uses {@link Class#isInstance} when
     * the class resolved successfully; falls back to simple-name matching
     * (e.g. {@code "BlockBed"}, or notch names like {@code "aaw"}) for
     * fully obfuscated runtimes where the class may not be loadable by MCP name.
     */
    private boolean isBedBlock(Object block) {
        if (block == null)
            return false;
        if (bedBlockClass != null)
            return bedBlockClass.isInstance(block);
        String name = block.getClass().getSimpleName();
        return name.contains("BlockBed") || name.contains("Bed");
    }

    private static long[] packXYZ(int x, int y, int z) {
        return new long[] {
                ((long) x << 32) | (y & 0xFFFFFFFFL),
                ((long) z << 32)
        };
    }

    @Override
    public void onRender(float partialTicks) {
        if (bedCache.isEmpty())
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
        double height = filled.getValue() ? 1.0 : 0.5625;

        RenderHelper.begin();

        for (long[] packed : bedCache) {
            int bx = (int) (packed[0] >> 32);
            int by = (int) (packed[0]);
            int bz = (int) (packed[1] >> 32);

            double minX = bx - rvpX;
            double minY = by - rvpY;
            double minZ = bz - rvpZ;
            double maxX = minX + 1.0;
            double maxY = minY + height;
            double maxZ = minZ + 1.0;

            if (filled.getValue()) {
                RenderHelper.drawFilledBox(minX, minY, minZ, maxX, maxY, maxZ, fr, fg, fb, 0.35f);
            }
            RenderHelper.drawBoundingBox(minX, minY, minZ, maxX, maxY, maxZ, fr, fg, fb, 1.0f, 2.0f);
        }

        RenderHelper.end();
    }
}

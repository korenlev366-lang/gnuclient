package gnu.client.script;

import gnu.client.runtime.mc.McAccess;

import java.util.List;

/**
 * Script-facing {@code world} accessor — stateless singleton facade over
 * {@link McAccess}. Exposes block-state queries and the loaded entity list.
 *
 * <p>No cached world reference: each call re-resolves {@code McAccess.theWorld()}.
 */
public final class World {

    public static final World INSTANCE = new World();

    private World() {}

    /**
     * Block state at the given integer coords, or {@code null} if no world /
     * reflection failure.
     *
     * @return the {@code IBlockState} as a raw Object
     */
    public Object getBlockAt(int x, int y, int z) {
        Object world = McAccess.theWorld();
        if (world == null)
            return null;
        return McAccess.getBlockState(world, x, y, z);
    }

    /**
     * All loaded entities in the client world (filtered by AntiBot when that
     * module is active). Returns an empty list if no world.
     */
    public List<?> getEntities() {
        Object world = McAccess.theWorld();
        if (world == null)
            return java.util.Collections.emptyList();
        return McAccess.getWorldEntities(world);
    }

    public boolean isPlayer(Object entity) {
        return McAccess.isEntityPlayer(entity);
    }

    public double distanceTo(Object entity) {
        return McAccess.distanceToPlayer(entity);
    }

    /** Nearest other player within {@code range} blocks, or {@code null}. */
    public Object getNearestPlayer(double range) {
        return McAccess.getNearestPlayer(range);
    }
}

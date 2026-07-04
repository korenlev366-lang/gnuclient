package gnu.client.script;

import gnu.client.runtime.mc.McAccess;

/**
 * Script-facing {@code util} accessor — stateless singleton facade over the
 * shared {@link McAccess} RNG helpers.
 */
public final class Util {

    public static final Util INSTANCE = new Util();

    private Util() {}

    /** Inclusive random int in {@code [min, max]}. */
    public int randomInt(int min, int max) {
        return McAccess.randomInt(min, max);
    }

    /** Random double in {@code [min, max)}. */
    public double randomDouble(double min, double max) {
        return McAccess.randomDouble(min, max);
    }
}

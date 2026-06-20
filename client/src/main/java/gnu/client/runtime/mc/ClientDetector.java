package gnu.client.runtime.mc;

import gnu.client.common.GnuLog;

/**
 * Detects the injected Minecraft client at runtime (mirrors Timewarp detection strings).
 *
 * <p>Order: Forge → Lunar marker classes → MCP Minecraft class → obfuscated {@code ave}
 * fallback for 1.8.x Notch runtimes.
 */
public final class ClientDetector {

    private static final String[] LUNAR_MARKERS = {
            "com.moonsworth.lunar.LunarClient",
            "com.moonsworth.lunar.genesis.Genesis",
            "com.lunarclient",
            "com.moonsworth.lunar",
    };

    private static final String[] BADLION_MARKERS = {
            "net.badlion.client.Wrapper",
            "com.badlion.client",
    };

    private ClientDetector() {}

    public static ClientProfile detect(ClassLoader[] loaders) {
        if (loaders == null || loaders.length == 0)
            return ClientProfile.VANILLA_18;

        for (ClassLoader cl : loaders) {
            if (cl == null)
                continue;
            if (classExists(cl, "net.minecraftforge.fml.client.FMLClientHandler"))
                return ClientProfile.FORGE_18;
        }

        for (ClassLoader cl : loaders) {
            if (cl == null)
                continue;
            if (isGenesisLoader(cl))
                return ClientProfile.LUNAR_18;
        }

        for (ClassLoader cl : loaders) {
            if (cl == null)
                continue;
            for (String marker : LUNAR_MARKERS) {
                if (classExists(cl, marker)) {
                    if (classExists(cl, "ave") || classExists(cl, "net.minecraft.client.Minecraft"))
                        return ClientProfile.LUNAR_18;
                    return ClientProfile.LUNAR_17;
                }
            }
        }

        for (ClassLoader cl : loaders) {
            if (cl == null)
                continue;
            for (String marker : BADLION_MARKERS) {
                if (classExists(cl, marker))
                    return ClientProfile.BADLION_18;
            }
        }

        for (ClassLoader cl : loaders) {
            if (cl == null)
                continue;
            if (classExists(cl, "net.minecraft.client.Minecraft"))
                return ClientProfile.VANILLA_18;
            if (classExists(cl, "ave"))
                return ClientProfile.LUNAR_18;
        }

        GnuLog.log("JAVA_ ClientDetector: game version not detected - defaulting Vanilla 1.8");
        return ClientProfile.VANILLA_18;
    }

    /** Lunar 1.8 Genesis exposes MCP-named game classes on a dedicated loader. */
    public static boolean isGenesisLoader(ClassLoader cl) {
        if (cl == null)
            return false;
        String name = cl.getClass().getName();
        return name.contains("GenesisClassLoader") || name.contains("genesis.GenesisClassLoader");
    }

    public static boolean classExists(ClassLoader cl, String binaryName) {
        if (cl == null || binaryName == null || binaryName.isEmpty())
            return false;
        try {
            Class.forName(binaryName, false, cl);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

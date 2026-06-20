package gnu.client.runtime.mc;

/**
 * Runtime client profile (Timewarp-style auto-detection).
 * Forge and Lunar Genesis use SRG/MCP member names at runtime; legacy Notch
 * clients (Badlion, vanilla obf) use short obfuscated identifiers.
 */
public enum ClientProfile {
    FORGE_18("Forge 1.8"),
    LUNAR_18("Lunar 1.8"),
    LUNAR_17("Lunar 1.7"),
    BADLION_18("Badlion 1.8"),
    VANILLA_18("Vanilla 1.8");

    private static volatile ClientProfile current = FORGE_18;
    /** Set when the live Minecraft class uses MCP binary names (Lunar Genesis, Forge). */
    private static volatile boolean mcpRuntime;

    private final String label;

    ClientProfile(String label) {
        this.label = label;
    }

    public static ClientProfile current() {
        return current;
    }

    public static void setCurrent(ClientProfile profile) {
        if (profile != null)
            current = profile;
    }

    public static void setMcpRuntime(boolean mcp) {
        mcpRuntime = mcp;
    }

    public static boolean mcpRuntime() {
        return mcpRuntime;
    }

    public String label() {
        return label;
    }

    public boolean usesForgeEvents() {
        return this == FORGE_18;
    }

    /** Notch/vanilla field and method names (short obfuscated identifiers). */
    public boolean usesNotchMappings() {
        return this != FORGE_18;
    }
}

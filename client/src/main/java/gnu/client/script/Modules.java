package gnu.client.script;

/**
 * Script-facing {@code modules} accessor — per-script settings registry facade.
 *
 * <p>One {@code Modules} instance is constructed per compiled script (see the
 * wrapper template in the feasibility report), bound to that script's
 * {@code scriptName}. The {@code registerButton}/{@code registerSlider}/
 * {@code getButton}/{@code getSlider} methods are <b>stubs in this step</b>:
 * the actual wiring requires deciding how a script's settings attach to its
 * generated {@code gnu.client.module.Module} subclass (via
 * {@code Module.addSetting(...)}) and how the per-script {@code Module}
 * reference is handed to this facade. That is a separate, larger change.
 *
 * <p>Until the wiring lands, every setting method throws
 * {@link UnsupportedOperationException} with a clear message — these are
 * called during a script's {@code onLoad()} (one-shot, not per-tick), so
 * throwing is safe and surfaces the unfinished contract immediately rather
 * than silently no-op'ing.
 */
public final class Modules {

    private final String scriptName;

    public Modules(String scriptName) {
        this.scriptName = scriptName;
    }

    /** The script name this registry is bound to (set by the wrapper template). */
    public String getScriptName() {
        return scriptName;
    }

    /**
     * Register a boolean (button) setting on this script's module.
     *
     * @throws UnsupportedOperationException setting wiring not yet implemented
     */
    public void registerButton(String name, boolean defaultValue) {
        throw new UnsupportedOperationException(
                "modules.registerButton is not wired yet — script settings require the "
                        + "Module/Setting integration step (see feasibility report §5). "
                        + "Script: " + scriptName + ", setting: " + name);
    }

    /**
     * Register a slider (float, min..max) setting on this script's module.
     *
     * @throws UnsupportedOperationException setting wiring not yet implemented
     */
    public void registerSlider(String name, float defaultValue, float min, float max) {
        throw new UnsupportedOperationException(
                "modules.registerSlider is not wired yet — script settings require the "
                        + "Module/Setting integration step (see feasibility report §5). "
                        + "Script: " + scriptName + ", setting: " + name);
    }

    /**
     * Read a boolean (button) setting value on this script's module.
     *
     * @throws UnsupportedOperationException setting wiring not yet implemented
     */
    public boolean getButton(String name) {
        throw new UnsupportedOperationException(
                "modules.getButton is not wired yet — script settings require the "
                        + "Module/Setting integration step (see feasibility report §5). "
                        + "Script: " + scriptName + ", setting: " + name);
    }

    /**
     * Read a slider setting value on this script's module.
     *
     * @throws UnsupportedOperationException setting wiring not yet implemented
     */
    public float getSlider(String name) {
        throw new UnsupportedOperationException(
                "modules.getSlider is not wired yet — script settings require the "
                        + "Module/Setting integration step (see feasibility report §5). "
                        + "Script: " + scriptName + ", setting: " + name);
    }
}

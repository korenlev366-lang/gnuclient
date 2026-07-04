package gnu.client.script;

import gnu.client.module.Module;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.Setting;
import gnu.client.module.setting.SliderSetting;

import java.util.HashMap;
import java.util.Map;

/**
 * Script-facing {@code modules} accessor — per-script settings registry facade.
 *
 * <p>One {@code Modules} instance is constructed per compiled script (see the
 * wrapper template in the feasibility report), bound to that script's
 * {@code scriptName} and to its owning generated {@link Module} subclass.
 * {@code registerButton}/{@code registerSlider} create real
 * {@link BoolSetting}/{@link SliderSetting} instances and attach them to the
 * owning module via {@link Module#addScriptSetting}, which delegates to the
 * protected {@code addSetting} used by every hand-written module — so script
 * settings flow through the same list that {@code ConfigManager} serializes,
 * the native ImGui GUI iterates, and {@code Module.serialize/deserialize}
 * walk. {@code getButton}/{@code getSlider} read back from a per-script
 * {@code Map<String,Setting>} keyed by name for O(1) lookup.
 *
 * <p>Null-safe: missing settings or wrong-type casts return {@code false} /
 * {@code 0f} rather than throwing into a script's tick loop.
 */
public final class Modules {

    private final String scriptName;
    private final Module owner;
    private final Map<String, Setting<?>> settings = new HashMap<>();

    public Modules(String scriptName, Module owner) {
        this.scriptName = scriptName;
        this.owner = owner;
    }

    /** The script name this registry is bound to (set by the wrapper template). */
    public String getScriptName() {
        return scriptName;
    }

    /**
     * Register a boolean (button) setting on this script's module. The setting
     * is added to the owning {@link Module} and indexed by {@code name} for
     * later {@link #getButton} lookups.
     */
    public void registerButton(String name, boolean defaultValue) {
        if (owner == null || name == null)
            return;
        BoolSetting setting = new BoolSetting(name, defaultValue);
        owner.addScriptSetting(setting);
        settings.put(name, setting);
    }

    /**
     * Register a slider (float, {@code min..max}) setting on this script's
     * module. The setting is added to the owning {@link Module} and indexed by
     * {@code name} for later {@link #getSlider} lookups.
     */
    public void registerSlider(String name, float defaultValue, float min, float max) {
        if (owner == null || name == null)
            return;
        SliderSetting setting = new SliderSetting(name, defaultValue, min, max);
        owner.addScriptSetting(setting);
        settings.put(name, setting);
    }

    /**
     * Read a boolean (button) setting value. Returns {@code false} if the
     * setting was never registered or is not a {@link BoolSetting}.
     */
    public boolean getButton(String name) {
        Setting<?> setting = settings.get(name);
        if (!(setting instanceof BoolSetting))
            return false;
        return ((BoolSetting) setting).getValue();
    }

    /**
     * Read a slider setting value as a primitive {@code float}. Returns
     * {@code 0f} if the setting was never registered or is not a
     * {@link SliderSetting}.
     */
    public float getSlider(String name) {
        Setting<?> setting = settings.get(name);
        if (!(setting instanceof SliderSetting))
            return 0f;
        return ((SliderSetting) setting).getValue();
    }
}

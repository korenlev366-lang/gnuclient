package gnu.client.module;

import com.google.gson.JsonObject;
import gnu.client.config.ConfigManager;
import gnu.client.module.modules.visual.HudModule;
import gnu.client.module.setting.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {

    private final String name;
    private final String description;
    private final Category category;
    private volatile boolean enabled;
    private int keyCode = -1;
    private final List<Setting<?>> settings = new ArrayList<>();

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    /** {@link KeybindAction#TOGGLE} unless overridden (e.g. ClickGUI). */
    public KeybindAction getKeybindAction() {
        return KeybindAction.TOGGLE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** LWJGL {@code Keyboard} key code, or {@code -1} for unbound. */
    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
        if (!ConfigManager.instance().isLoading())
            ConfigManager.instance().save();
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(settings);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled)
            return;
        this.enabled = enabled;
        if (enabled)
            onEnable();
        else
            onDisable();
        HudModule.onModuleToggled(this, enabled);
        if (!ConfigManager.instance().isLoading())
            ConfigManager.instance().save();
    }

    public abstract void onEnable();

    public abstract void onDisable();

    public void onTick() {}

    /** Early client tick (Forge {@code ClientTickEvent} START), before living update. */
    public void onTickStart() {}

    /** 3D world-space draw; dispatched from {@code RenderWorldLastEvent} only. */
    public void onRender(float partialTicks) {}

    /** 2D screen-space draw; dispatched from {@code RenderGameOverlayEvent} only. */
    public void onOverlay(Object scaledResolution) {}

    public void onPacket(Object packet) {}

    /**
     * Suffix string(s) appended to the module name in the enabled-modules HUD
     * array. Returns an empty array by default.
     */
    public String[] getSuffix() {
        return new String[0];
    }

    @SuppressWarnings("unchecked")
    protected <T extends Setting<?>> T addSetting(T setting) {
        settings.add(setting);
        return setting;
    }

    /**
     * Public entry point for runtime-loaded script modules to register settings
     * from outside this package (where {@link #addSetting} is not visible).
     * Hand-written modules should keep using {@link #addSetting} directly.
     *
     * @return the setting, for fluent chaining
     */
    public <T extends Setting<?>> T addScriptSetting(T setting) {
        return addSetting(setting);
    }

    public JsonObject serialize() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.addProperty("keyCode", keyCode);
        JsonObject settingsJson = new JsonObject();
        for (Setting<?> s : settings) {
            settingsJson.add(s.getName(), s.serialize());
        }
        root.add("settings", settingsJson);
        return root;
    }

    public void deserialize(JsonObject root) {
        if (root.has("enabled"))
            setEnabled(root.get("enabled").getAsBoolean());
        if (root.has("keyCode"))
            keyCode = root.get("keyCode").getAsInt();
        if (root.has("settings")) {
            JsonObject settingsJson = root.getAsJsonObject("settings");
            for (Setting<?> s : settings) {
                if (settingsJson.has(s.getName()))
                    s.deserialize(settingsJson.get(s.getName()));
            }
        }
    }
}

package gnu.client.module.modules.settings;

import gnu.client.module.Category;
import gnu.client.module.KeybindAction;
import gnu.client.module.Module;
import gnu.client.runtime.NativeBootstrap;

import org.lwjgl.input.Keyboard;

/**
 * Settings-tab entry for the in-game ClickGUI. The bound key toggles the native
 * ImGui menu; this module is never enabled as a feature.
 */
public final class ClickGuiModule extends Module {

    public static final String NAME = "ClickGUI";

    private static ClickGuiModule instance;

    public ClickGuiModule() {
        super(NAME, "Open the in-game ClickGUI menu", Category.SETTINGS);
        setKeyCode(Keyboard.KEY_INSERT);
        instance = this;
    }

    public static ClickGuiModule instance() {
        return instance;
    }

    @Override
    public KeybindAction getKeybindAction() {
        return KeybindAction.MENU;
    }

    @Override
    public void toggle() {
        NativeBootstrap.toggleMenu();
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled)
            return;
        super.setEnabled(false);
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {}
}

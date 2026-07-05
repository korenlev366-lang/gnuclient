package gnu.client.runtime;

import gnu.client.command.ChatCommandHandler;
import gnu.client.common.GnuLog;
import gnu.client.config.ConfigManager;
import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.combat.AimAssistModule;
import gnu.client.module.modules.combat.AntiBotModule;
import gnu.client.module.modules.combat.AutoClickerModule;
import gnu.client.module.modules.combat.HitSelectModule;
import gnu.client.module.modules.combat.WTapModule;
import gnu.client.module.modules.combat.KillAuraModule;
import gnu.client.module.modules.combat.AutoBlockModule;
import gnu.client.module.modules.combat.ReachModule;
import gnu.client.module.modules.combat.VelocityModule;
import gnu.client.module.modules.player.BridgeAssistModule;
import gnu.client.module.modules.movement.FastStopModule;
import gnu.client.module.modules.movement.SprintModule;
import gnu.client.module.modules.movement.StasisModule;
import gnu.client.module.modules.player.FastPlaceModule;

import gnu.client.module.modules.visual.BedEspModule;
import gnu.client.module.modules.visual.EspModule;
import gnu.client.module.modules.visual.ItemEspModule;
import gnu.client.module.modules.network.BacktrackModule;
import gnu.client.module.modules.network.BlinkModule;
import gnu.client.module.modules.network.KnockbackDelayModule;
import gnu.client.module.modules.network.LagrangeModule;
import gnu.client.module.modules.network.PingFixModule;
import gnu.client.module.modules.visual.HudModule;
import gnu.client.module.modules.visual.NameTagsModule;
import gnu.client.module.modules.visual.TracersModule;
import gnu.client.module.modules.visual.FreeLookModule;
import gnu.client.module.modules.settings.ClickGuiModule;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.Setting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.ClientProfile;
import gnu.client.runtime.mc.McAccess;
import gnu.client.script.ScriptManager;

import java.util.ArrayList;
import java.util.List;

/**
 * JNI entry point. The native agent (gnu-agent.so) loads this class onto the
 * Forge LaunchClassLoader, then calls {@link #initOnRenderThread()} from the
 * render thread (where Minecraft is reachable) until init succeeds. GUI accessors
 * below are called by the native ImGui overlay using cached method ids.
 *
 * IMPORTANT: this jar is injected into a fully obfuscated runtime and is NOT
 * deobf-remapped, so there are NO compile-time {@code net.minecraft.*} type
 * references anywhere in the payload. Minecraft access goes through reflection
 * ({@link McAccess}, anchored on the non-obfuscated FMLClientHandler).
 *
 * Threading: Forge clients use {@link ClientEventListener} on the event bus.
 * Lunar/Badlion/Vanilla use {@link VanillaModuleDriver} from native tick/render hooks.
 */
public final class NativeBootstrap {

    private static volatile boolean initialized;
    private static volatile boolean initAttempted;
    private static ClientEventListener eventListener;

    // Stable index -> module mapping shared with the native GUI.
    private static final List<Module> GUI_MODULES = new ArrayList<>();

    /** Module name awaiting a key press from {@link ClientEventListener} (LWJGL queue). */
    private static volatile String rebindModule;

    private NativeBootstrap() {}

    /**
     * Called from the native render thread (glXSwapBuffers hook) each frame until
     * it returns true. Resolves Minecraft via reflection and runs init once.
     * Returns true once initialization has completed (native stops calling).
     */
    public static boolean initOnRenderThread() {
        if (initialized)
            return true;
        try {
            if (!McAccess.resolve(NativeBootstrap.class.getClassLoader())) {
                // MC not ready / not yet found on a candidate loader; native
                // retries on a later frame. Silent to avoid per-frame log spam.
                return false;
            }
            if (initAttempted)
                return true; // init already tried (see log); stop native retry
            GnuLog.log("JAVA_ initOnRenderThread: MC resolved, running init");
            init();
            return true; // tried once; do not spin the render thread on failure
        } catch (Throwable t) {
            GnuLog.log("JAVA_ initOnRenderThread failed: " + t);
            return false;
        }
    }

    private static synchronized void init() {
        if (initialized) {
            GnuLog.log("JAVA_ init skipped: already initialized");
            return;
        }
        initAttempted = true;
        try {
            ConfigManager.setLoading(true);
            ModuleManager mgr = ModuleManager.INSTANCE;
            mgr.reset();
            mgr.init();
            try {
                mgr.register(new ClickGuiModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL ClickGuiModule: " + e);
            }
            try {
                mgr.register(new WTapModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL WTapModule: " + e);
            }
            try {
                mgr.register(new SprintModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL SprintModule: " + e);
            }
            try {
                mgr.register(new BridgeAssistModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL BridgeAssistModule: " + e);
            }
            try {
                mgr.register(new FastStopModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL FastStopModule: " + e);
            }
            try {
                mgr.register(new VelocityModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL VelocityModule: " + e);
            }
            try {
                mgr.register(new FastPlaceModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL FastPlaceModule: " + e);
            }

            try {
                mgr.register(new StasisModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL StasisModule: " + e);
            }
            try {
                mgr.register(new AntiBotModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL AntiBotModule: " + e);
            }
            try {
                mgr.register(new AimAssistModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL AimAssistModule: " + e);
            }
            try {
                mgr.register(new AutoClickerModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL AutoClickerModule: " + e);
            }
            try {
                mgr.register(new HitSelectModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL HitSelectModule: " + e);
            }
            try {
                mgr.register(new KillAuraModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL KillAuraModule: " + e);
            }
            try {
                mgr.register(new AutoBlockModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL AutoBlockModule: " + e);
            }
            try {
                mgr.register(new ReachModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL ReachModule: " + e);
            }
            try {
                mgr.register(new EspModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL EspModule: " + e);
            }
            try {
                mgr.register(new TracersModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL TracersModule: " + e);
            }
            try {
                mgr.register(new ItemEspModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL ItemEspModule: " + e);
            }
            try {
                mgr.register(new BedEspModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL BedEspModule: " + e);
            }
            try {
                mgr.register(new NameTagsModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL NameTagsModule: " + e);
            }
            try {
                mgr.register(new HudModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL HudModule: " + e);
            }
            try {
                mgr.register(new BlinkModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL BlinkModule: " + e);
            }
            try {
                mgr.register(new KnockbackDelayModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL KnockbackDelayModule: " + e);
            }
            try {
                mgr.register(new BacktrackModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL BacktrackModule: " + e);
            }
            try {
                mgr.register(new LagrangeModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL LagrangeModule: " + e);
            }
            try {
                mgr.register(new PingFixModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL PingFixModule: " + e);
            }
            try {
                mgr.register(new FreeLookModule());
            } catch (Exception e) {
                GnuLog.log("MODULE FAIL FreeLookModule: " + e);
            }
            ConfigManager.setLoading(false);

            if (McAccess.profile().usesForgeEvents()) {
                eventListener = new ClientEventListener();
                registerForgeEventBus(eventListener);
            } else {
                GnuLog.log("JAVA_ using native tick/render driver for " + McAccess.profile().label());
            }

            ConfigManager.INSTANCE.load();
            // reloadAll() registers script modules into ModuleManager and calls
            // refreshGuiModules() at its end, so GUI_MODULES is populated with
            // built-ins + scripts before we log JAVA_READY below.
            gnu.client.script.ScriptManager.instance().reloadAll();
            ChatCommandHandler.register();
            McAccess.resetTimer();

            initialized = true;
            GnuLog.log("JAVA_READY profile=" + McAccess.profile().label() + " modules=" + GUI_MODULES.size());
        } catch (Throwable t) {
            GnuLog.log("JAVA_ init failed: " + t);
            for (StackTraceElement e : t.getStackTrace())
                GnuLog.log("JAVA_   at " + e);
            Throwable cause = t.getCause();
            if (cause != null) {
                GnuLog.log("JAVA_ caused by: " + cause);
                for (StackTraceElement e : cause.getStackTrace())
                    GnuLog.log("JAVA_   at " + e);
            }
        }
    }

    /** The Minecraft instance as a raw Object (obf type at runtime). */
    public static Object getMinecraft() {
        return McAccess.minecraft();
    }

    /** Register {@link ClientEventListener} without loading Forge types on Notch clients. */
    private static void registerForgeEventBus(Object listener) {
        try {
            Class<?> forge = Class.forName("net.minecraftforge.common.MinecraftForge");
            Object bus = forge.getField("EVENT_BUS").get(null);
            bus.getClass().getMethod("register", Object.class).invoke(bus, listener);
            GnuLog.log("JAVA_ Forge EVENT_BUS registered");
        } catch (Throwable t) {
            GnuLog.log("JAVA_ Forge event registration failed: " + t);
        }
    }

    /** Native 20 Hz hook (render thread). Drives modules on Lunar/Badlion/Vanilla. */
    public static void tick() {
        if (!initialized)
            return;
        if (McAccess.profile().usesForgeEvents())
            return;
        VanillaModuleDriver.tick();
    }

    /** Native per-frame hook (render thread). World/overlay modules on Notch clients. */
    public static void render() {
        if (!initialized || McAccess.profile().usesForgeEvents())
            return;
        VanillaModuleDriver.render();
    }

    // ===================== GUI accessors (called from native) =====================

    private static Module module(int i) {
        if (i < 0 || i >= GUI_MODULES.size())
            return null;
        return GUI_MODULES.get(i);
    }

    /** Native evdev state from the selected physical mouse (BTN_LEFT). */
    public static native boolean isLeftMouseDown();

 
    /** Native evdev shift key state (KEY_LEFTSHIFT or KEY_RIGHTSHIFT). */
    public static native boolean isShiftDown();
    /** Native X11 synthetic Button1 click. holdMs is the press duration. */
    public static native void nativeClick(int holdMs);

    /** Physical mouse REL_X since last consume (evdev). */
    public static native int consumeMouseDeltaX();

    /** Physical mouse REL_Y since last consume (evdev). */
    public static native int consumeMouseDeltaY();

    /** Milliseconds since last physical mouse movement, or a large value if none. */
    public static native long lastMouseMoveAgeMs();

    /** Toggle native ImGui menu open/closed (ClickGUI keybind). */
    public static native void toggleMenuNative();

    /** Set native ImGui menu open state. */
    public static native void setMenuOpenNative(boolean open);

    /** Whether the native ImGui menu is open. */
    public static native boolean isMenuOpenNative();

    public static void toggleMenu() {
        toggleMenuNative();
    }

    public static void setMenuOpen(boolean open) {
        setMenuOpenNative(open);
    }

    public static boolean isMenuOpen() {
        return isMenuOpenNative();
    }

    private static Setting<?> setting(int i, int s) {
        Module m = module(i);
        if (m == null)
            return null;
        int visibleIdx = 0;
        for (Setting<?> setting : m.getSettings()) {
            if (!setting.isVisible()) continue;
            if (visibleIdx == s) return setting;
            visibleIdx++;
        }
        return null;
    }

    public static int guiModuleCount() {
        return GUI_MODULES.size();
    }

    /**
     * Re-snapshot {@link ModuleManager#all()} into {@link #GUI_MODULES} so the
     * native GUI and the {@code JAVA_READY} count reflect modules registered
     * after {@link #init()} — notably script modules added by
     * {@code ScriptManager.reloadAll()} (initial boot and RShift+R reloads).
     * Called by {@code ScriptManager.reloadAll()} at the end of every reload.
     */
    public static void refreshGuiModules() {
        GUI_MODULES.clear();
        GUI_MODULES.addAll(ModuleManager.INSTANCE.all());
    }

    public static void reloadScriptsFromGui() {
        GnuLog.log("GUI_ scripts: reload triggered from ClickGUI");
        try {
            ScriptManager.instance().reloadAll();
        } catch (Throwable t) {
            GnuLog.log("GUI_ scripts: reload from ClickGUI failed: " + t);
        }
    }

    public static String guiModuleName(int i) {
        Module m = module(i);
        return m == null ? "" : m.getName();
    }

    public static String guiModuleDesc(int i) {
        Module m = module(i);
        return m == null ? "" : m.getDescription();
    }

    public static int guiModuleCategory(int i) {
        Module m = module(i);
        return m == null ? Category.MISC.ordinal() : m.getCategory().ordinal();
    }

    public static boolean guiModuleEnabled(int i) {
        Module m = module(i);
        return m != null && m.isEnabled();
    }

    /** True for bind-only modules (e.g. ClickGUI) — no Enable toggle in the GUI. */
    public static boolean guiModuleBindOnly(int i) {
        Module m = module(i);
        return m != null && m.getKeybindAction() == gnu.client.module.KeybindAction.MENU;
    }

    public static void guiToggle(int i) {
        Module m = module(i);
        if (m == null)
            return;
        if (m.getKeybindAction() == gnu.client.module.KeybindAction.MENU)
            toggleMenu();
        else
            m.toggle();
    }

    public static int getModuleKeyCode(String moduleName) {
        Module m = ModuleManager.INSTANCE.getModule(moduleName);
        return m == null ? -1 : m.getKeyCode();
    }

    public static void setModuleKeyCode(String moduleName, int keyCode) {
        Module m = ModuleManager.INSTANCE.getModule(moduleName);
        if (m != null)
            m.setKeyCode(keyCode);
    }

    public static void startRebind(String moduleName) {
        if (moduleName != null && !moduleName.isEmpty())
            rebindModule = moduleName;
    }

    public static void finishRebind() {
        rebindModule = null;
    }

    public static boolean isRebindActive() {
        return rebindModule != null;
    }

    public static boolean isRebindPending(String moduleName) {
        return moduleName != null && moduleName.equals(rebindModule);
    }

    public static boolean guiIsRebindPending(int i) {
        Module m = module(i);
        return m != null && isRebindPending(m.getName());
    }

    /** Drain LWJGL keyboard events while {@link #rebindModule} is set (game thread). */
    public static void handleRebindKeyboard() {
        String pending = rebindModule;
        if (pending == null)
            return;
        try {
            while (org.lwjgl.input.Keyboard.next()) {
                int key = org.lwjgl.input.Keyboard.getEventKey();
                boolean pressed = org.lwjgl.input.Keyboard.getEventKeyState();
                if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
                    if (pressed)
                        finishRebind();
                    continue;
                }
                if (key == org.lwjgl.input.Keyboard.KEY_DELETE) {
                    if (pressed) {
                        Module m = ModuleManager.INSTANCE.getModule(pending);
                        if (m != null)
                            m.setKeyCode(-1);
                        finishRebind();
                    }
                    continue;
                }
                if (key != 0 && pressed) {
                    Module m = ModuleManager.INSTANCE.getModule(pending);
                    if (m != null)
                        m.setKeyCode(key);
                    finishRebind();
                    return;
                }
            }
        } catch (Throwable t) {
            finishRebind();
        }
    }

    /** Display label for ImGui keybind button: {@code NONE} or LWJGL key name. */
    public static String guiModuleKeyLabel(int i) {
        Module m = module(i);
        if (m == null)
            return "NONE";
        int code = m.getKeyCode();
        if (code < 0)
            return "NONE";
        try {
            String name = org.lwjgl.input.Keyboard.getKeyName(code);
            return name == null || name.isEmpty() ? "KEY" + code : name;
        } catch (Throwable t) {
            return "KEY" + code;
        }
    }

    public static void guiSetModuleKeyCode(int i, int keyCode) {
        Module m = module(i);
        if (m != null)
            m.setKeyCode(keyCode);
    }

    public static int guiSettingCount(int i) {
        Module m = module(i);
        if (m == null) return 0;
        int count = 0;
        for (Setting<?> s : m.getSettings()) {
            if (s.isVisible()) count++;
        }
        return count;
    }

    public static String guiSettingName(int i, int s) {
        Setting<?> set = setting(i, s);
        return set == null ? "" : set.getName();
    }

    /** 0 = bool, 1 = slider, 2 = mode, -1 = unknown. */
    public static int guiSettingType(int i, int s) {
        Setting<?> set = setting(i, s);
        if (set instanceof BoolSetting)
            return 0;
        if (set instanceof SliderSetting)
            return 1;
        if (set instanceof ModeSetting)
            return 2;
        return -1;
    }

    public static boolean guiSettingBool(int i, int s) {
        Setting<?> set = setting(i, s);
        return set instanceof BoolSetting && ((BoolSetting) set).getValue();
    }

    public static void guiSetBool(int i, int s, boolean v) {
        Setting<?> set = setting(i, s);
        if (set instanceof BoolSetting) {
            ((BoolSetting) set).setValue(v);
            ConfigManager.INSTANCE.save();
        }
    }

    public static float guiSettingFloat(int i, int s) {
        Setting<?> set = setting(i, s);
        return set instanceof SliderSetting ? ((SliderSetting) set).getValue() : 0.0f;
    }

    public static float guiSettingMin(int i, int s) {
        Setting<?> set = setting(i, s);
        return set instanceof SliderSetting ? ((SliderSetting) set).getMin() : 0.0f;
    }

    public static float guiSettingMax(int i, int s) {
        Setting<?> set = setting(i, s);
        return set instanceof SliderSetting ? ((SliderSetting) set).getMax() : 1.0f;
    }

    public static void guiSetFloat(int i, int s, float v) {
        Setting<?> set = setting(i, s);
        if (set instanceof SliderSetting) {
            ((SliderSetting) set).setValue(v);
            ConfigManager.INSTANCE.save();
        }
    }

    public static int guiSettingMode(int i, int s) {
        Setting<?> set = setting(i, s);
        return set instanceof ModeSetting ? ((ModeSetting) set).getValue() : 0;
    }

    public static int guiSettingModeCount(int i, int s) {
        Setting<?> set = setting(i, s);
        return set instanceof ModeSetting ? ((ModeSetting) set).getModes().size() : 0;
    }

    public static String guiSettingModeName(int i, int s, int m) {
        Setting<?> set = setting(i, s);
        if (!(set instanceof ModeSetting))
            return "";
        List<String> modes = ((ModeSetting) set).getModes();
        return (m < 0 || m >= modes.size()) ? "" : modes.get(m);
    }

    public static void guiSetMode(int i, int s, int v) {
        Setting<?> set = setting(i, s);
        if (set instanceof ModeSetting) {
            ((ModeSetting) set).setValue(v);
            ConfigManager.INSTANCE.save();
        }
    }

    // ---- HUD overlay (native ImGui, drawn even when the menu is closed) ----

    public static boolean hudShouldDraw() {
        return HudModule.shouldDrawOverlay();
    }

    public static boolean hudShowArray() {
        HudModule hud = HudModule.instance();
        return hud != null && hud.isEnabled() && hud.wantsArray();
    }

    public static boolean hudShowNotifications() {
        HudModule hud = HudModule.instance();
        return hud != null && hud.isEnabled() && hud.wantsNotifications();
    }

    public static int hudEnabledModuleCount() {
        return HudModule.enabledModuleNames().size();
    }

    public static String hudEnabledModuleName(int i) {
        List<String> names = HudModule.enabledModuleNames();
        return (i < 0 || i >= names.size()) ? "" : names.get(i);
    }

    public static int hudNotificationCount() {
        return HudModule.notificationCount();
    }

    public static String hudNotificationText(int i) {
        return HudModule.notificationText(i);
    }

    public static boolean hudNotificationEnabled(int i) {
        return HudModule.notificationEnabled(i);
    }

    public static float hudNotificationAlpha(int i) {
        return HudModule.notificationAlpha(i);
    }
}

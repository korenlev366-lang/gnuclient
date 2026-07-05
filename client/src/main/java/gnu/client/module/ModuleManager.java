package gnu.client.module;

import gnu.client.common.GnuLog;
import gnu.client.module.modules.visual.NameTagsModule;
import gnu.client.runtime.NativeBootstrap;

import org.lwjgl.input.Keyboard;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModuleManager {

    public static final ModuleManager INSTANCE = new ModuleManager();

    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Map<Module, Long> lastToggleTime = new HashMap<>();
    private final Map<Integer, Boolean> prevKeyState = new HashMap<>();
    private static final Object KEYBIND_LOCK = new Object();
    private static final long TOGGLE_DEBOUNCE_MS = 120L;
    private boolean initialized;

    private ModuleManager() {}

    public static ModuleManager instance() {
        return INSTANCE;
    }

    public void register(Module module) {
        modules.put(module.getName(), module);
    }

    /** Remove a module by name, disabling it first if it was enabled. */
    public void unregister(String name) {
        Module m = modules.remove(name);
        if (m != null && m.isEnabled()) {
            try { m.setEnabled(false); } catch (Throwable ignored) {}
        }
    }

    public void init() {
        if (initialized)
            return;
        initialized = true;
        GnuLog.log("ModuleManager initialized");
    }

    public void reset() {
        initialized = false;
        modules.clear();
    }

    public Module get(String name) {
        return modules.get(name);
    }

    public Module getModule(String name) {
        for (Module m : modules.values()) {
            if (m.getName().equalsIgnoreCase(name))
                return m;
        }
        return null;
    }

    public Collection<Module> all() {
        return modules.values();
    }

    // ---- dispatch ----

    public void tick() {
        dispatchTick();
    }

    public void tickStart() {
        dispatchTickStart();
    }

    private void dispatchTick() {
        for (Module module : modules.values()) {
            if (!module.isEnabled())
                continue;
            try {
                module.onTick();
            } catch (Throwable t) {
                logModuleError("onTick", module, t);
            }
        }
    }

    private void dispatchTickStart() {
        for (Module module : modules.values()) {
            if (!module.isEnabled())
                continue;
            try {
                module.onTickStart();
            } catch (Throwable t) {
                logModuleError("onTickStart", module, t);
            }
        }
    }













    /**
     * Poll LWJGL keyboard on the game thread.
     * Uses state-based dispatch ({@link Keyboard#isKeyDown}) with edge detection
     * so LWJGL event queue is NOT consumed — vanilla input still works normally.
     */
    public void handleKeybinds() {
        synchronized (KEYBIND_LOCK) {
            pollAndDispatchEvents();
        }
    }

    /**
     * Poll keyboard and dispatch any key-down events to bound modules.
     * Uses state-based polling ({@link Keyboard#isKeyDown}) with edge detection
     * so LWJGL event queue is NOT consumed — Minecraft still receives WASD,
     * hotbar, inventory, and other vanilla key presses normally.
     * Debounce (120 ms) prevents key-repeat from double-toggling.
     */
    private static void dispatchKeybind(Module module) {
        if (module.getKeybindAction() == KeybindAction.MENU)
            NativeBootstrap.toggleMenu();
        else
            module.toggle();
    }

    private void pollAndDispatchEvents() {
        if (modules.isEmpty())
            return;
        try {
            Keyboard.poll();
            long now = System.currentTimeMillis();
            for (Module module : modules.values()) {
                int code = module.getKeyCode();
                if (code <= 0 || code >= Keyboard.KEYBOARD_SIZE)
                    continue;
                boolean down = Keyboard.isKeyDown(code);
                boolean prev = prevKeyState.getOrDefault(code, false);
                if (down && !prev) {
                    Long last = lastToggleTime.get(module);
                    if (last == null || now - last > TOGGLE_DEBOUNCE_MS) {
                        dispatchKeybind(module);
                        lastToggleTime.put(module, now);
                    }
                }
                prevKeyState.put(code, down);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 3D world overlays from {@code RenderWorldLastEvent} — ESP, tracers, item/bed
     * boxes, network ghosts, etc. NameTags captures GL matrices here for 2D overlay.
     */
    public void renderWorld(float partialTicks) {
        dispatchRender(partialTicks);
        captureNameTagsGlState(partialTicks);
    }

    public void dispatchRender(float partialTicks) {
        for (Module module : modules.values()) {
            if (!module.isEnabled())
                continue;
            try {
                module.onRender(partialTicks);
            } catch (Throwable t) {
                logModuleError("onRender", module, t);
            }
        }
    }

    public void overlay(Object scaledResolution) {
        for (Module module : modules.values()) {
            if (!module.isEnabled())
                continue;
            try {
                module.onOverlay(scaledResolution);
            } catch (Throwable t) {
                logModuleError("onOverlay", module, t);
            }
        }
    }

    private void captureNameTagsGlState(float partialTicks) {
        Module module = getModule("NameTags");
        if (module instanceof NameTagsModule && module.isEnabled()) {
            try {
                ((NameTagsModule) module).captureGlState(partialTicks);
            } catch (Throwable t) {
                logModuleError("captureGlState", module, t);
            }
        }
    }

    private static void logModuleError(String phase, Module module, Throwable t) {
        GnuLog.log("module " + phase + " failed name=" + module.getName() + " err=" + t);
        t.printStackTrace();
    }
}
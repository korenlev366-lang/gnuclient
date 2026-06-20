package gnu.client.module.modules.movement;

import gnu.client.common.GnuLog;
import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.NativeBootstrap;
import gnu.client.runtime.mc.McAccess;

/**
 * Clean timer — persistently writes {@code Timer.timerSpeed} via a 1 ms daemon
 * thread (Timewarp / Slinky parity). No pulse, no use-item protection, no packet
 * hooks. Hotbar switching works smoothly at any timer speed because we never
 * force {@code 1.0f} due to placement delays or held keys.
 *
 * Speed {@code 0} follows raven-bS: world {@code timerSpeed} stays {@code 1.0f};
 * local freeze is {@code EntityPlayerSP.onUpdate} skip via {@code PlayerUpdateHook}.
 */
public final class TimerModule extends Module {

    private static final Object HACK_LOCK = new Object();
    private static volatile Thread hackThread;
    private static volatile boolean hackRunning;

    private final SliderSetting speed =
            addSetting(new SliderSetting("Speed", 1.0f, 0.0f, 2.0f));
    private final BoolSetting condMouse =
            addSetting(new BoolSetting("Mouse pressed", false));
    private final BoolSetting condDamage =
            addSetting(new BoolSetting("Damage", false));
    private final BoolSetting condFalling =
            addSetting(new BoolSetting("Falling", false));

    public TimerModule() {
        super("Timer", "Change game tick speed", Category.PLAYER);
    }

    // ---------- static helpers (called from other hooks) ----------

    /** Safety net — always restore vanilla {@code 1.0f} when module is off. */
    public static void maintain() {
        Module module = ModuleManager.INSTANCE.getModule("Timer");
        if (!(module instanceof TimerModule) || !module.isEnabled())
            McAccess.resetTimer();
    }

    public static boolean isEnabledActive() {
        Module module = ModuleManager.INSTANCE.getModule("Timer");
        return module instanceof TimerModule && module.isEnabled();
    }

    /**
     * Render-thread keybind poll while Timer is on (see {@code NativeBootstrap#tick()}).
     * Game-thread keybind handling is skipped when Timer is active to avoid
     * double-toggles.
     */
    public static void pollKeybindsIfNeeded() {
        if (!isEnabledActive())
            return;
        ModuleManager.INSTANCE.pollKeyboardAndHandleKeybinds();
    }

    /**
     * Speed {@code 0} freezes the local player without halting
     * {@code Minecraft.runTick} (so use/placement can still run).
     */
    public static boolean shouldSkipLocalUpdate() {
        Module module = ModuleManager.INSTANCE.getModule("Timer");
        if (!(module instanceof TimerModule) || !module.isEnabled())
            return false;
        TimerModule timer = (TimerModule) module;
        if (timer.speed.getValue() > 0.0f)
            return false;
        // At speed 0 the player is frozen — skip local onUpdate.
        return true;
    }

    // ---------- desired speed ----------

    /** Desired {@code timerSpeed} for the hack thread (vanilla {@code 1.0f} when inactive). */
    static float desiredTimerSpeed() {
        Module module = ModuleManager.INSTANCE.getModule("Timer");
        if (!(module instanceof TimerModule) || !module.isEnabled())
            return 1.0f;
        TimerModule timer = (TimerModule) module;
        if (McAccess.thePlayer() == null)
            return 1.0f;
        if (!timer.conditionsPass())
            return 1.0f;
        float configured = timer.speed.getValue();
        if (configured <= 0.0f)
            return 1.0f;
        return configured;
    }

    // ---------- lifecycle ----------

    @Override
    public void onEnable() {
        startHackThread();
        McAccess.setTimerSpeedVerified(desiredTimerSpeed());
    }

    @Override
    public void onDisable() {
        stopHackThread();
        McAccess.resetTimer();
    }

    @Override
    public void onTickStart() {
        McAccess.setTimerSpeedVerified(desiredTimerSpeed());
    }

    @Override
    public void onTick() {
        McAccess.setTimerSpeedVerified(desiredTimerSpeed());
    }

    // ---------- conditions ----------

    /**
     * When any condition is enabled, all enabled conditions must pass (AND).
     * With none enabled, modified speed applies whenever the module is on.
     */
    private boolean conditionsPass() {
        boolean any = condMouse.getValue() || condDamage.getValue() || condFalling.getValue();
        if (!any)
            return true;

        Object player = McAccess.thePlayer();
        if (player == null)
            return false;

        if (condMouse.getValue() && !NativeBootstrap.isLeftMouseDown())
            return false;

        if (condDamage.getValue() && McAccess.getInt(player, "field_70737_aN") <= 0)
            return false;

        if (condFalling.getValue()) {
            boolean onGround = McAccess.getBool(player, "field_70122_E");
            float fallDist = McAccess.getFloat(player, "field_70143_R");
            if (onGround && fallDist <= 0.0f)
                return false;
        }

        return true;
    }

    // ---------- hack thread (1 ms loop — Timewarp parity) ----------

    private static void startHackThread() {
        synchronized (HACK_LOCK) {
            if (hackThread != null && hackThread.isAlive()) {
                hackRunning = true;
                return;
            }
            hackRunning = true;
            hackThread = new Thread(() -> {
                while (hackRunning) {
                    try {
                        // Sleep FIRST so any concurrent disable (which sets hackRunning=false,
                        // enabled=false, and calls resetTimer) is guaranteed to be visible
                        // when we re-read conditions after waking up.
                        Thread.sleep(1L);
                        Module mod = ModuleManager.INSTANCE.getModule("Timer");
                        float desired = desiredTimerSpeed();
                        if (hackRunning && mod instanceof TimerModule && mod.isEnabled()) {
                            McAccess.setTimerSpeedVerified(desired);
                            // TOCTOU race: the module may have been disabled (or hackRunning
                            // may have been set to false) during the setTimerSpeedVerified
                            // call above. Re-check and correct immediately so we never leave
                            // the timer at a non-1.0 speed after a concurrent disable.
                            if (!hackRunning || !mod.isEnabled())
                                McAccess.resetTimer();
                        } else {
                            McAccess.resetTimer();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable t) {
                        GnuLog.log("JAVA_ gnu-timer-hack: " + t);
                    }
                }
            }, "gnu-timer-hack");
            hackThread.setDaemon(true);
            hackThread.start();
            GnuLog.log("JAVA_ gnu-timer-hack started");
        }
    }

    private static void stopHackThread() {
        synchronized (HACK_LOCK) {
            hackRunning = false;
            Thread t = hackThread;
            hackThread = null;
            if (t != null)
                t.interrupt();
        }
    }

    // ---------- suffix ----------

    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%.1fx", speed.getValue())};
    }
}
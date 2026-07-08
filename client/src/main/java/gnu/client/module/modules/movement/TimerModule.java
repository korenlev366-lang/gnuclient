package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;

/**
 * Simple timer — sets {@code Timer.timerSpeed} while enabled and restores vanilla
 * {@code 1.0f} on disable.
 */
public final class TimerModule extends Module {

    private final SliderSetting speed =
            addSetting(new SliderSetting("Speed", 1.0f, 0.0f, 2.0f, 0.01f));

    public TimerModule() {
        super("Timer", "Change game tick speed", Category.PLAYER);
    }

    /** Safety net — restore vanilla speed when the module is off. */
    public static void maintain() {
        Module module = ModuleManager.INSTANCE.getModule("Timer");
        if (!(module instanceof TimerModule) || !module.isEnabled())
            McAccess.resetTimer();
    }

    @Override
    public void onEnable() {
        apply();
    }

    @Override
    public void onDisable() {
        McAccess.resetTimer();
    }

    @Override
    public void onTickStart() {
        apply();
    }

    @Override
    public void onTick() {
        apply();
    }

    private void apply() {
        if (!isEnabled())
            return;
        McAccess.setTimerSpeed(speed.getValue());
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.format("%.2fx", speed.getValue())};
    }
}

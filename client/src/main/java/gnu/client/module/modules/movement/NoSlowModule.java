package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.combat.AutoBlockModule;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;

import java.util.Arrays;

/**
 * NoSlow for Grim 1.8.9 — sword block and consumables.
 *
 * <p><b>Vanilla</b> — scales {@code MovementInput} before the client applies the 0.2× item slow
 * (Raven-style). Slow % 0 = full speed attempt; 80 = vanilla slow.
 *
 * <p><b>Grim</b> — sends {@code C07 RELEASE_USE_ITEM} each tick so Grim/server clear
 * {@code isSlowedByUsingItem} while you keep using client-side. Best for sword block; consumables
 * may stutter (off by default in Grim mode).
 */
public final class NoSlowModule extends Module {

    private static final String[] MODES = { "Vanilla", "Grim" };
    private static final float VANILLA_ITEM_SLOW = 0.2f;

    private final ModeSetting mode =
            addSetting(new ModeSetting("Mode", 0, Arrays.asList(MODES)));
    private final SliderSetting slowPercent =
            addSetting(new SliderSetting("Slow %", 0f, 0f, 80f));
    private final BoolSetting sword =
            addSetting(new BoolSetting("Sword", true));
    private final BoolSetting consumables =
            addSetting(new BoolSetting("Consumables", true));
    private final BoolSetting bow =
            addSetting(new BoolSetting("Bow", false));
    private final BoolSetting grimConsumables =
            addSetting(new BoolSetting("Grim consumables", false));
    private final BoolSetting slotFlick =
            addSetting(new BoolSetting("Slot flick", true));
    private final BoolSetting allowSprint =
            addSetting(new BoolSetting("Sprint", true));

    public NoSlowModule() {
        super("NoSlow", "Move faster while blocking or eating", Category.PLAYER);
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {}

    @Override
    public void onTickStart() {
        if (!isEnabled())
            return;
        Object player = McAccess.thePlayer();
        if (player == null || !McAccess.isUsingItem() || McAccess.isRiding())
            return;
        if (!affectsHeldItem())
            return;
        if (isAutoBlockActive())
            return;

        if (isGrimMode()) {
            if (slotFlick.getValue())
                McAccess.sendHeldItemChangeFlicker();
            McAccess.sendReleaseUseItem(player);
        }

        if (allowSprint.getValue() && McAccess.isForwardKeyHeld())
            McAccess.setSprintKeyState(true);
    }

    /** Called from {@link gnu.client.runtime.MovementInputHook}. */
    public static void patchMovementInput(Object movInput) {
        Module mod = ModuleManager.INSTANCE.getModule("NoSlow");
        if (!(mod instanceof NoSlowModule) || !mod.isEnabled())
            return;
        NoSlowModule ns = (NoSlowModule) mod;
        if (movInput == null)
            return;
        if (!McAccess.isUsingItem() || McAccess.isRiding())
            return;
        if (!ns.affectsHeldItem())
            return;

        float target = ns.getTargetMultiplier();
        if (Math.abs(target - VANILLA_ITEM_SLOW) < 0.001f)
            return;

        float scale = target / VANILLA_ITEM_SLOW;
        float forward = McAccess.getFloat(movInput, "field_78900_b");
        float strafe = McAccess.getFloat(movInput, "field_78902_a");
        McAccess.setFloat(movInput, "field_78900_b", forward * scale);
        McAccess.setFloat(movInput, "field_78902_a", strafe * scale);
    }

    private boolean isGrimMode() {
        return "Grim".equals(mode.getCurrentMode());
    }

    private float getTargetMultiplier() {
        float pct = slowPercent.getValue();
        if (pct < 0f)
            pct = 0f;
        if (pct > 80f)
            pct = 80f;
        return (100f - pct) / 100f;
    }

    private boolean affectsHeldItem() {
        if (McAccess.isHoldingSword() && sword.getValue())
            return true;
        if (isGrimMode() ? grimConsumables.getValue() : consumables.getValue()) {
            if (McAccess.isHoldingConsumable())
                return true;
        }
        if (McAccess.isHoldingBow() && bow.getValue())
            return true;
        return false;
    }

    private static boolean isAutoBlockActive() {
        Module m = ModuleManager.INSTANCE.getModule("Auto Block");
        return m instanceof AutoBlockModule && ((AutoBlockModule) m).isActive();
    }
}

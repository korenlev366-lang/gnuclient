package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.runtime.mc.McAccess;

import java.util.Arrays;
import java.util.List;

/**
 * Timewarp Fast Stop — opposite keybind tap on movement key release.
 * Legit = 1 tick; Blatant = hold opposite key until velocity < 0.01. Cancels if you press any movement key.
 */
public final class FastStopModule extends Module {

    private static final List<String> MODES = Arrays.asList("Blatant", "Legit");
    private static final double VELOCITY_STOP_THRESHOLD = 0.01;
    private static final int LEGIT_HOLD_TICKS = 1;
    private static final int HOLD_UNTIL_STOP = -1; // blatant: hold opposite key until velocity < 0.01

    private static final String KEY_FORWARD = "field_74351_w";
    private static final String KEY_BACK = "field_74368_y"; // keyBindBack
    private static final String KEY_LEFT = "field_74370_x";
    private static final String KEY_RIGHT = "field_74366_z";

    private final gnu.client.module.setting.ModeSetting mode =
            addSetting(new gnu.client.module.setting.ModeSetting("Mode", 1, MODES));

    private boolean physicalForward;
    private boolean physicalBack;
    private boolean physicalLeft;
    private boolean physicalRight;

    private int holdForwardTicks;
    private int holdBackTicks;
    private int holdLeftTicks;
    private int holdRightTicks;

    public FastStopModule() {
        super("Instant Stop", "Stop instantly by counter-strafing", Category.PLAYER);
    }

    @Override
    public void onEnable() {
        syncPhysicalKeys();
        resetHolds();
    }

    @Override
    public void onDisable() {
        resetHolds();
        restorePhysicalMovementKeys();
    }

    @Override
    public void onTickStart() {
        if (!isEnabled())
            return;

        if (McAccess.currentScreen() != null) {
            resetHolds();
            restorePhysicalMovementKeys();
            syncPhysicalKeys();
            return;
        }

        cancelHoldsOnUserInput();
        detectKeyReleases();
        applyHoldKeybinds();
        syncPhysicalKeys();
    }

    private void cancelHoldsOnUserInput() {
        if (!hasActiveHold())
            return;
        if (McAccess.isPhysicalKeyBindDown(KEY_FORWARD)
                || McAccess.isPhysicalKeyBindDown(KEY_BACK)
                || McAccess.isPhysicalKeyBindDown(KEY_LEFT)
                || McAccess.isPhysicalKeyBindDown(KEY_RIGHT)) {
            resetHolds();
            restorePhysicalMovementKeys();
        }
    }

    private void detectKeyReleases() {
        if (!hasHorizontalVelocity())
            return;

        boolean forward = McAccess.isPhysicalKeyBindDown(KEY_FORWARD);
        boolean back = McAccess.isPhysicalKeyBindDown(KEY_BACK);
        boolean left = McAccess.isPhysicalKeyBindDown(KEY_LEFT);
        boolean right = McAccess.isPhysicalKeyBindDown(KEY_RIGHT);

        if (physicalForward && !forward)
            startOppositeHold(KEY_BACK);
        if (physicalBack && !back)
            startOppositeHold(KEY_FORWARD);
        if (physicalLeft && !left && !physicalRight)
            startOppositeHold(KEY_RIGHT);
        if (physicalRight && !right && !physicalLeft)
            startOppositeHold(KEY_LEFT);
    }

    private void startOppositeHold(String oppositeKey) {
        int ticks = isLegit() ? LEGIT_HOLD_TICKS : HOLD_UNTIL_STOP;
        if (KEY_BACK.equals(oppositeKey))
            holdBackTicks = ticks;
        else if (KEY_FORWARD.equals(oppositeKey))
            holdForwardTicks = ticks;
        else if (KEY_LEFT.equals(oppositeKey))
            holdLeftTicks = ticks;
        else if (KEY_RIGHT.equals(oppositeKey))
            holdRightTicks = ticks;
    }

    private void applyHoldKeybinds() {
        if (holdForwardTicks > 0 || holdForwardTicks == HOLD_UNTIL_STOP)
            McAccess.setForwardKeyState(true);
        else
            McAccess.setForwardKeyState(McAccess.isPhysicalKeyBindDown(KEY_FORWARD));

        if (holdBackTicks > 0 || holdBackTicks == HOLD_UNTIL_STOP)
            McAccess.setBackKeyState(true);
        else
            McAccess.setBackKeyState(McAccess.isPhysicalKeyBindDown(KEY_BACK));

        if (holdLeftTicks > 0 || holdLeftTicks == HOLD_UNTIL_STOP)
            McAccess.setLeftKeyState(true);
        else
            McAccess.setLeftKeyState(McAccess.isPhysicalKeyBindDown(KEY_LEFT));

        if (holdRightTicks > 0 || holdRightTicks == HOLD_UNTIL_STOP)
            McAccess.setRightKeyState(true);
        else
            McAccess.setRightKeyState(McAccess.isPhysicalKeyBindDown(KEY_RIGHT));

        // Decrement tick-based holds; check velocity for HOLD_UNTIL_STOP
        if (holdForwardTicks == HOLD_UNTIL_STOP) {
            if (!hasHorizontalVelocity())
                holdForwardTicks = 0;
        } else if (holdForwardTicks > 0) {
            holdForwardTicks--;
        }
        if (holdBackTicks == HOLD_UNTIL_STOP) {
            if (!hasHorizontalVelocity())
                holdBackTicks = 0;
        } else if (holdBackTicks > 0) {
            holdBackTicks--;
        }
        if (holdLeftTicks == HOLD_UNTIL_STOP) {
            if (!hasHorizontalVelocity())
                holdLeftTicks = 0;
        } else if (holdLeftTicks > 0) {
            holdLeftTicks--;
        }
        if (holdRightTicks == HOLD_UNTIL_STOP) {
            if (!hasHorizontalVelocity())
                holdRightTicks = 0;
        } else if (holdRightTicks > 0) {
            holdRightTicks--;
        }
    }

    private boolean hasActiveHold() {
        return holdForwardTicks > 0 || holdForwardTicks == HOLD_UNTIL_STOP
            || holdBackTicks > 0 || holdBackTicks == HOLD_UNTIL_STOP
            || holdLeftTicks > 0 || holdLeftTicks == HOLD_UNTIL_STOP
            || holdRightTicks > 0 || holdRightTicks == HOLD_UNTIL_STOP;
    }

    private boolean isLegit() {
        return mode.getValue() == 1;
    }

    private boolean hasHorizontalVelocity() {
        Object player = McAccess.thePlayer();
        if (player == null)
            return false;
        double motionX = McAccess.getDouble(player, "field_70159_w");
        double motionZ = McAccess.getDouble(player, "field_70179_y");
        return Math.abs(motionX) >= VELOCITY_STOP_THRESHOLD
                || Math.abs(motionZ) >= VELOCITY_STOP_THRESHOLD;
    }

    private void syncPhysicalKeys() {
        physicalForward = McAccess.isPhysicalKeyBindDown(KEY_FORWARD);
        physicalBack = McAccess.isPhysicalKeyBindDown(KEY_BACK);
        physicalLeft = McAccess.isPhysicalKeyBindDown(KEY_LEFT);
        physicalRight = McAccess.isPhysicalKeyBindDown(KEY_RIGHT);
    }

    private void restorePhysicalMovementKeys() {
        McAccess.setForwardKeyState(McAccess.isPhysicalKeyBindDown(KEY_FORWARD));
        McAccess.setBackKeyState(McAccess.isPhysicalKeyBindDown(KEY_BACK));
        McAccess.setLeftKeyState(McAccess.isPhysicalKeyBindDown(KEY_LEFT));
        McAccess.setRightKeyState(McAccess.isPhysicalKeyBindDown(KEY_RIGHT));
    }

    private void resetHolds() {
        holdForwardTicks = 0;
        holdBackTicks = 0;
        holdLeftTicks = 0;
        holdRightTicks = 0;
    }
}

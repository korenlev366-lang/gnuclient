package gnu.client.runtime;

import gnu.client.module.modules.player.BridgeAssistModule;
import gnu.client.module.modules.movement.StasisModule;

/**
 * JVMTI hook at {@code MovementInputFromOptions.updatePlayerMoveState} RETURN.
 */
public final class MovementInputHook {

    private MovementInputHook() {}

    public static void afterUpdatePlayerMoveState(Object movementInput) {
        BridgeAssistModule.patchMovementInput(movementInput);
        StasisModule.patchPlayerInput(movementInput);
    }
}

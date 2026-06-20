package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.modules.combat.WTapModule;
import gnu.client.runtime.mc.McAccess;

/**
 * Auto-sprint via sprint keybind (OpenMyau {@code Sprint}).
 *
 * <p>Holds the sprint key every tick START so sprint persists through jumps.
 * Yields to {@link WTapModule} while the post-attack delay is active.
 */
public final class SprintModule extends Module {

    public SprintModule() {
        super("Sprint", "Auto-sprint via keybind (packet-safe)", Category.PLAYER);
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {
        McAccess.setSprintKeyState(false);
    }

    @Override
    public void onTickStart() {
        if (!isEnabled())
            return;
        if (McAccess.thePlayer() == null)
            return;
        if (WTapModule.shouldSuppressSprintKey()) {
            McAccess.setSprintKeyState(false);
            return;
        }
        McAccess.setSprintKeyState(true);
    }
}

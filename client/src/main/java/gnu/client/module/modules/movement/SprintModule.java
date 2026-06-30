package gnu.client.module.modules.movement;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.combat.AutoBlockModule;
import gnu.client.module.modules.combat.WTapModule;
import gnu.client.runtime.mc.McAccess;

/**
 * Auto-sprint via sprint keybind (OpenMyau {@code Sprint}).
 *
 * <p>Holds the sprint key every tick START so sprint persists through jumps.
 * Yields to {@link WTapModule} while the post-attack delay is active, and to
 * {@link AutoBlockModule} while blocking or lagging (vanilla cannot sprint while using item).
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
        Object player = McAccess.thePlayer();
        if (player == null)
            return;
        if (WTapModule.shouldSuppressSprintKey()) {
            McAccess.setSprintKeyState(false);
            return;
        }
        Module autoBlock = ModuleManager.INSTANCE.getModule("Auto Block");
        if (autoBlock instanceof AutoBlockModule && ((AutoBlockModule) autoBlock).isActive()) {
            McAccess.setSprintKeyState(false);
            McAccess.setClientSprinting(player, false);
            return;
        }
        McAccess.setSprintKeyState(true);
    }
}

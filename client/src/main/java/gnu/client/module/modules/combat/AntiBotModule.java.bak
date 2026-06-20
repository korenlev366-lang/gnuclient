package gnu.client.module.modules.combat;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;

/**
 * Toggle gate for Raven-bS AntiBot filtering across combat and visual consumers.
 *
 * <p>This module intentionally has no per-tick logic. Consumers either request
 * {@link gnu.client.runtime.mc.McAccess#getWorldEntitiesFiltered(Object)} or
 * apply the same RavenAntiBot.isBot(Object) gate inline.</p>
 */
public final class AntiBotModule extends Module {

    private final BoolSetting tablistCheck = addSetting(new BoolSetting("Tablist", true));

    public AntiBotModule() {
        super("AntiBot", "Filters Raven AntiBot entities from target and visual scans", Category.COMBAT);
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {}

    public static boolean isActive() {
        Module module = ModuleManager.INSTANCE.getModule("AntiBot");
        return module != null && module.isEnabled();
    }

    public static boolean isTablistCheckEnabled() {
        Module module = ModuleManager.INSTANCE.getModule("AntiBot");
        if (module instanceof AntiBotModule) {
            return ((AntiBotModule) module).tablistCheck.getValue();
        }
        return false;
    }
}

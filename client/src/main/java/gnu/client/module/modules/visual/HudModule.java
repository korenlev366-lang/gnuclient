package gnu.client.module.modules.visual;

import gnu.client.config.ConfigManager;
import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * In-game HUD overlay (native ImGui) listing enabled modules and toast
 * notifications when modules are toggled. Colors follow the GUI accent palette.
 */
public final class HudModule extends Module {

    private static final long NOTIF_DURATION_MS = 2500L;
    private static final long NOTIF_FADE_MS = 400L;
    private static final int NOTIF_MAX = 8;

    private static HudModule instance;
    private static final List<Notification> NOTIFICATIONS = new ArrayList<>();

    private final BoolSetting showArray = addSetting(new BoolSetting("Array", true));
    private final BoolSetting showNotifications = addSetting(new BoolSetting("Notifications", true));

    public HudModule() {
        super("HUD", "Enabled module list and toggle notifications", Category.VISUALS);
        instance = this;
    }

    public static HudModule instance() {
        return instance;
    }

    public static void onModuleToggled(Module module, boolean enabled) {
        if (module instanceof HudModule)
            return;
        if (ConfigManager.instance().isLoading())
            return;
        HudModule hud = instance();
        if (hud == null || !hud.isEnabled() || !hud.showNotifications.getValue())
            return;
        hud.pushNotification(module.getName(), enabled);
    }

    private void pushNotification(String moduleName, boolean enabled) {
        synchronized (NOTIFICATIONS) {
            NOTIFICATIONS.add(0, new Notification(moduleName, enabled, System.currentTimeMillis()));
            while (NOTIFICATIONS.size() > NOTIF_MAX)
                NOTIFICATIONS.remove(NOTIFICATIONS.size() - 1);
        }
    }

    public boolean wantsArray() {
        return showArray.getValue();
    }

    public boolean wantsNotifications() {
        return showNotifications.getValue();
    }

    public static boolean hasActiveNotifications() {
        long now = System.currentTimeMillis();
        synchronized (NOTIFICATIONS) {
            for (Notification n : NOTIFICATIONS) {
                if (now - n.createdAtMs < NOTIF_DURATION_MS)
                    return true;
            }
        }
        return false;
    }

    public static boolean shouldDrawOverlay() {
        HudModule hud = instance();
        if (hud == null || !hud.isEnabled())
            return false;
        if (hud.wantsArray())
            return true;
        return hud.wantsNotifications() && hasActiveNotifications();
    }

    public static List<String> enabledModuleNames() {
        List<Module> enabled = new ArrayList<>();
        for (Module m : ModuleManager.INSTANCE.all()) {
            if (!m.isEnabled() || m instanceof HudModule)
                continue;
            enabled.add(m);
        }
        enabled.sort(Comparator.comparing(Module::getName));
        List<String> names = new ArrayList<>(enabled.size());
        for (Module m : enabled)
            names.add(m.getName());
        return names;
    }

    public static int notificationCount() {
        long now = System.currentTimeMillis();
        synchronized (NOTIFICATIONS) {
            int n = 0;
            for (Notification notif : NOTIFICATIONS) {
                if (now - notif.createdAtMs < NOTIF_DURATION_MS)
                    n++;
            }
            return n;
        }
    }

    public static String notificationText(int index) {
        Notification n = notificationAt(index);
        if (n == null)
            return "";
        return n.moduleName + (n.enabled ? " enabled" : " disabled");
    }

    public static boolean notificationEnabled(int index) {
        Notification n = notificationAt(index);
        return n != null && n.enabled;
    }

    public static float notificationAlpha(int index) {
        Notification n = notificationAt(index);
        if (n == null)
            return 0.0f;
        long age = System.currentTimeMillis() - n.createdAtMs;
        if (age >= NOTIF_DURATION_MS)
            return 0.0f;
        if (age <= NOTIF_DURATION_MS - NOTIF_FADE_MS)
            return 1.0f;
        return (NOTIF_DURATION_MS - age) / (float) NOTIF_FADE_MS;
    }

    private static Notification notificationAt(int index) {
        long now = System.currentTimeMillis();
        synchronized (NOTIFICATIONS) {
            int i = 0;
            for (Notification n : NOTIFICATIONS) {
                if (now - n.createdAtMs >= NOTIF_DURATION_MS)
                    continue;
                if (i == index)
                    return n;
                i++;
            }
        }
        return null;
    }

    @Override
    public void onEnable() {}

    @Override
    public void onDisable() {
        synchronized (NOTIFICATIONS) {
            NOTIFICATIONS.clear();
        }
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        synchronized (NOTIFICATIONS) {
            NOTIFICATIONS.removeIf(n -> now - n.createdAtMs >= NOTIF_DURATION_MS);
        }
    }

    private static final class Notification {
        final String moduleName;
        final boolean enabled;
        final long createdAtMs;

        Notification(String moduleName, boolean enabled, long createdAtMs) {
            this.moduleName = moduleName;
            this.enabled = enabled;
            this.createdAtMs = createdAtMs;
        }
    }
}

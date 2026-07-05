package gnu.client.command;

import gnu.client.module.KeybindAction;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.modules.settings.ClickGuiModule;
import gnu.client.runtime.NativeBootstrap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** {@code .bind <module> <key>} — client-only; packet is cancelled before send. */
public final class BindCommand {

    private static final String PREFIX = ".bind";

    private static final Map<String, String> MODULE_ALIASES = new HashMap<>();

    static {
        MODULE_ALIASES.put("gui", ClickGuiModule.NAME);
        MODULE_ALIASES.put("clickgui", ClickGuiModule.NAME);
        MODULE_ALIASES.put("menu", ClickGuiModule.NAME);
    }

    private BindCommand() {}

    public static boolean handles(String message) {
        if (message == null)
            return false;
        String trimmed = message.trim();
        if (!trimmed.regionMatches(true, 0, PREFIX, 0, PREFIX.length()))
            return false;
        if (trimmed.length() == PREFIX.length())
            return true;
        char next = trimmed.charAt(PREFIX.length());
        return Character.isWhitespace(next);
    }

    /**
     * @return log line for {@code GnuLog}, or {@code null} if not a bind command
     */
    public static String execute(String message) {
        if (!handles(message))
            return null;

        String trimmed = message.trim();
        String args = trimmed.length() > PREFIX.length()
                ? trimmed.substring(PREFIX.length()).trim()
                : "";

        if (args.isEmpty())
            return "bind: usage .bind <module> <key>  (key=none to unbind)";

        int split = args.indexOf(' ');
        if (split < 0)
            return "bind: usage .bind <module> <key>";

        String moduleToken = args.substring(0, split).trim();
        String keyToken = args.substring(split + 1).trim();
        if (moduleToken.isEmpty() || keyToken.isEmpty())
            return "bind: usage .bind <module> <key>";

        Module module = resolveModule(moduleToken);
        if (module == null)
            return "bind: unknown module \"" + moduleToken + "\"";

        try {
            int keyCode = KeyNames.parse(keyToken);
            NativeBootstrap.setModuleKeyCode(module.getName(), keyCode);
            if (keyCode < 0)
                return "bind: unbound " + module.getName();
            String label = KeyNames.format(keyCode);
            if (module.getKeybindAction() == KeybindAction.MENU)
                return "bind: " + module.getName() + " menu key -> [" + label + "]";
            return "bind: " + module.getName() + " -> [" + label + "]";
        } catch (IllegalArgumentException e) {
            return "bind: " + e.getMessage();
        }
    }

    private static Module resolveModule(String token) {
        String alias = MODULE_ALIASES.get(token.toLowerCase(Locale.ROOT));
        if (alias != null)
            return ModuleManager.INSTANCE.getModule(alias);
        return ModuleManager.INSTANCE.getModule(token);
    }
}

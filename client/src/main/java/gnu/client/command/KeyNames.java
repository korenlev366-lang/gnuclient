package gnu.client.command;

import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** LWJGL {@link Keyboard} key name → key code for chat {@code .bind}. */
public final class KeyNames {

    private static final Map<String, Integer> ALIASES = new HashMap<>();

    static {
        ALIASES.put("NONE", -1);
        ALIASES.put("INSERT", Keyboard.KEY_INSERT);
        ALIASES.put("INS", Keyboard.KEY_INSERT);
        ALIASES.put("DELETE", Keyboard.KEY_DELETE);
        ALIASES.put("DEL", Keyboard.KEY_DELETE);
        ALIASES.put("ESCAPE", Keyboard.KEY_ESCAPE);
        ALIASES.put("ESC", Keyboard.KEY_ESCAPE);
        ALIASES.put("SPACE", Keyboard.KEY_SPACE);
        ALIASES.put("TAB", Keyboard.KEY_TAB);
        ALIASES.put("BACK", Keyboard.KEY_BACK);
        ALIASES.put("BACKSPACE", Keyboard.KEY_BACK);
        ALIASES.put("RETURN", Keyboard.KEY_RETURN);
        ALIASES.put("ENTER", Keyboard.KEY_RETURN);
        ALIASES.put("LSHIFT", Keyboard.KEY_LSHIFT);
        ALIASES.put("RSHIFT", Keyboard.KEY_RSHIFT);
        ALIASES.put("SHIFT", Keyboard.KEY_LSHIFT);
        ALIASES.put("LCONTROL", Keyboard.KEY_LCONTROL);
        ALIASES.put("RCONTROL", Keyboard.KEY_RCONTROL);
        ALIASES.put("CONTROL", Keyboard.KEY_LCONTROL);
        ALIASES.put("CTRL", Keyboard.KEY_LCONTROL);
        ALIASES.put("LMENU", Keyboard.KEY_LMENU);
        ALIASES.put("RMENU", Keyboard.KEY_RMENU);
        ALIASES.put("ALT", Keyboard.KEY_LMENU);
        ALIASES.put("CAPITAL", Keyboard.KEY_CAPITAL);
        ALIASES.put("CAPSLOCK", Keyboard.KEY_CAPITAL);
        ALIASES.put("CAPS", Keyboard.KEY_CAPITAL);
        ALIASES.put("UP", Keyboard.KEY_UP);
        ALIASES.put("DOWN", Keyboard.KEY_DOWN);
        ALIASES.put("LEFT", Keyboard.KEY_LEFT);
        ALIASES.put("RIGHT", Keyboard.KEY_RIGHT);
        ALIASES.put("HOME", Keyboard.KEY_HOME);
        ALIASES.put("END", Keyboard.KEY_END);
        ALIASES.put("PRIOR", Keyboard.KEY_PRIOR);
        ALIASES.put("PAGEUP", Keyboard.KEY_PRIOR);
        ALIASES.put("NEXT", Keyboard.KEY_NEXT);
        ALIASES.put("PAGEDOWN", Keyboard.KEY_NEXT);
        for (int i = 0; i <= 9; i++)
            ALIASES.put("NUM" + i, Keyboard.KEY_NUMPAD0 + i);
        for (int i = 1; i <= 12; i++)
            ALIASES.put("F" + i, Keyboard.KEY_F1 + (i - 1));
    }

    private KeyNames() {}

    /**
     * @return LWJGL key code, or {@code -1} for {@code none}
     * @throws IllegalArgumentException if the name is not recognized
     */
    public static int parse(String name) {
        if (name == null)
            throw new IllegalArgumentException("key name required");
        String trimmed = name.trim();
        if (trimmed.isEmpty())
            throw new IllegalArgumentException("key name required");
        if (trimmed.equalsIgnoreCase("none"))
            return -1;

        String norm = normalize(trimmed);
        Integer alias = ALIASES.get(norm);
        if (alias != null)
            return alias;

        if (norm.length() == 1) {
            int letter = Keyboard.getKeyIndex(trimmed.toUpperCase(Locale.ROOT));
            if (isValidKey(letter))
                return letter;
        }

        String[] candidates = {
                trimmed,
                trimmed.toUpperCase(Locale.ROOT),
                norm,
                "KEY_" + norm
        };
        for (String candidate : candidates) {
            int code = Keyboard.getKeyIndex(candidate);
            if (isValidKey(code))
                return code;
        }

        throw new IllegalArgumentException("unknown key: " + name);
    }

    public static String format(int keyCode) {
        if (keyCode < 0)
            return "NONE";
        try {
            String name = Keyboard.getKeyName(keyCode);
            if (name != null && !name.isEmpty())
                return name;
        } catch (Throwable ignored) {
        }
        return "KEY" + keyCode;
    }

    private static String normalize(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static boolean isValidKey(int code) {
        return code > 0 && code < Keyboard.KEYBOARD_SIZE;
    }
}

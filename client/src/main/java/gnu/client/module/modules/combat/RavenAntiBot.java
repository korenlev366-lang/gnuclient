package gnu.client.module.modules.combat;

import gnu.client.runtime.mc.McAccess;

/**
 * Raven-bS / Raven-XD {@code AntiBot.isBot} heuristics (reflection-only).
 * AimAssist calls this when {@link AimAssistModule} bot check is enabled.
 */
public final class RavenAntiBot {

    private RavenAntiBot() {}

    static void onTickCleanup() {
        // no-op (join delay removed)
    }

    public static boolean isBot(Object entity) {
        if (entity == null)
            return reject("null_entity", "?");
        if (entity == McAccess.thePlayer())
            return false;

        Class<?> playerCls = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");
        if (playerCls == null || !playerCls.isInstance(entity))
            return reject("not_player", entityName(entity));

        String name = entityName(entity);

        if (McAccess.getBool(entity, "field_70128_L"))
            return reject("dead", name);

        if (name.isEmpty())
            return reject("empty_name", name);

        float health = entityHealth(entity);
        if (health != 20.0f && name.startsWith("\u00a7c"))
            return reject("red_name_low_hp", name);

        if (McAccess.getInt(entity, "field_70738_aO") != 0)
            return false;

        if (health == 20.0f) {
            String unformatted = displayNameUnformatted(entity);
            if (unformatted.length() == 10 && (unformatted.isEmpty() || unformatted.charAt(0) != '\u00a7'))
                return reject("npc_len10_no_color", name);
            if (unformatted.length() == 12 && isPlayerSleeping(entity)
                    && !unformatted.isEmpty() && unformatted.charAt(0) == '\u00a7')
                return reject("sleeping_len12", name);
            if (unformatted.length() >= 7 && unformatted.charAt(2) == '['
                    && unformatted.charAt(3) == 'N' && unformatted.charAt(6) == ']')
                return reject("npc_bracket_n", name);
            if (name.contains(" "))
                return reject("name_has_space", name);
        } else if (isInvisible(entity)) {
            String unformatted = displayNameUnformatted(entity);
            if (unformatted.length() >= 3 && unformatted.charAt(0) == '\u00a7'
                    && unformatted.charAt(1) == 'c')
                return reject("invisible_red_prefix", name);
        }

        // Check 7: Tablist presence (Raven check 7).
        // If the name isn't in the server's playerlist, treat as bot.
        // Guard: only reject if tablist is non-empty (avoids false-flag at inject).
        if (AntiBotModule.isTablistCheckEnabled()) {
            java.util.Set<String> tablist = McAccess.getTablistNames();
            if (!tablist.isEmpty() && !tablist.contains(name))
                return reject("not_in_tablist", name);
        }

        return false;
    }

    private static boolean reject(String reason, String name) {
        return true;
    }

    private static String entityName(Object entity) {
        if (entity == null)
            return "?";
        Object name = McAccess.invoke(entity, "func_70005_c_", new Class<?>[0]);
        return name != null ? name.toString() : "";
    }

    private static float entityHealth(Object entity) {
        Object hp = McAccess.invoke(entity, "func_110143_aJ", new Class<?>[0]);
        if (hp == null)
            hp = McAccess.invokeNamed(entity, "getHealth", new Class<?>[0]);
        return hp instanceof Float ? (Float) hp : 0.0f;
    }

    private static String displayNameUnformatted(Object entity) {
        Object displayName = McAccess.invoke(entity, "func_145748_c_", new Class<?>[0]);
        if (displayName == null)
            displayName = McAccess.invokeNamed(entity, "getDisplayName", new Class<?>[0]);
        if (displayName == null)
            return "";
        Object unformatted = McAccess.invoke(displayName, "func_150260_g", new Class<?>[0]);
        if (unformatted == null)
            unformatted = McAccess.invokeNamed(displayName, "getUnformattedText", new Class<?>[0]);
        return unformatted != null ? unformatted.toString() : displayName.toString();
    }

    private static boolean isInvisible(Object entity) {
        Object r = McAccess.invoke(entity, "func_82150_aj", new Class<?>[0]);
        if (r instanceof Boolean)
            return (Boolean) r;
        r = McAccess.invokeNamed(entity, "isInvisible", new Class<?>[0]);
        return r instanceof Boolean && (Boolean) r;
    }

    private static boolean isPlayerSleeping(Object entity) {
        Object r = McAccess.invoke(entity, "func_70608_bn", new Class<?>[0]);
        if (r instanceof Boolean)
            return (Boolean) r;
        r = McAccess.invokeNamed(entity, "isPlayerSleeping", new Class<?>[0]);
        return r instanceof Boolean && (Boolean) r;
    }
}

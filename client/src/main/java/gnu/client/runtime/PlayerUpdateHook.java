package gnu.client.runtime;

import gnu.client.module.modules.movement.StasisModule;
import gnu.client.module.modules.movement.TimerModule;
import gnu.client.runtime.mc.McAccess;

/**
 * JVMTI hook at {@code EntityPlayerSP.onUpdate} HEAD — raven {@code MixinEntityPlayerSP.onUpdatePre}.
 */
public final class PlayerUpdateHook {

    private PlayerUpdateHook() {}

    /**
     * @return true to skip the rest of {@code onUpdate} (raven {@code CallbackInfo.cancel}).
     */
    public static boolean onUpdateHead(Object player) {
        if (player == null)
            return false;
        Object local = McAccess.thePlayer();
        if (local == null || player != local)
            return false;

        StasisModule.onPreUpdate(player);
        return TimerModule.shouldSkipLocalUpdate();
    }

}

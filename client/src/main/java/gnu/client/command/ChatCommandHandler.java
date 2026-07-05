package gnu.client.command;

import gnu.client.common.GnuLog;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

/**
 * Intercepts outbound chat packets for client-only dot-commands (e.g. {@code .bind}).
 */
public final class ChatCommandHandler implements PacketListener {

    public static final ChatCommandHandler INSTANCE = new ChatCommandHandler();

    private static final int SEND_PRIORITY = 500;

    private ChatCommandHandler() {}

    public static void register() {
        PacketEvents.register(INSTANCE);
    }

    @Override
    public int sendPriority() {
        return SEND_PRIORITY;
    }

    @Override
    public boolean onSend(Object packet) {
        if (!PacketHelper.isChatSend(packet))
            return false;
        String message = PacketHelper.chatMessage(packet);
        if (message == null || !BindCommand.handles(message))
            return false;

        String result = BindCommand.execute(message);
        if (result != null)
            GnuLog.log("CMD_ " + result);
        return true;
    }

    @Override
    public boolean onReceive(Object packet) {
        return false;
    }
}

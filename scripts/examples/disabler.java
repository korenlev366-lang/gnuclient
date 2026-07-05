// Packet queue / disable example — educational, not an anti-cheat bypass.
//
// Action mode: Queue (timed hold → flush → gap) or Disable (drop while enabled).
// Direction mode: Outbound, Inbound, or Both.
//
// packetSendPriority(): higher runs first; first true cancels the send.
//   FreeLook=200, AutoBlock=100, lag modules=0, script default=-100.

List<Object> outboundBuffer = new ArrayList<Object>();
List<Object> inboundBuffer = new ArrayList<Object>();
long phaseStart = 0;
boolean holding = true;

void onLoad() {
    modules.registerMode("Action", 0, "Queue", "Disable");
    modules.registerMode("Direction", 0, "Outbound", "Inbound", "Both");
    modules.registerSlider("Hold ms", 500f, 50f, 5000f);
    modules.registerSlider("Gap ms", 200f, 0f, 2000f);
    modules.registerSlider("Send priority", 50f, -100f, 200f);

    modules.registerButton("All outbound", false);
    modules.registerButton("C00 keep alive", false);
    modules.registerButton("C01 chat", false);
    modules.registerButton("C02 attack", false);
    modules.registerButton("C02 interact", false);
    modules.registerButton("C03 position", true);
    modules.registerButton("C03 rotation", false);
    modules.registerButton("C07 block dig", false);
    modules.registerButton("C07 release item", false);
    modules.registerButton("C08 use/place", false);
    modules.registerButton("C09 held item", false);
    modules.registerButton("C0A swing", false);
    modules.registerButton("C0B entity action", false);
    modules.registerButton("C0C steer vehicle", false);
    modules.registerButton("C0F transaction", false);
    modules.registerButton("C15 client settings", false);
    modules.registerButton("C17 custom payload", false);

    modules.registerButton("All inbound", false);
    modules.registerButton("S00 keep alive", false);
    modules.registerButton("S02 chat", false);
    modules.registerButton("S06 health", false);
    modules.registerButton("S08 pos look", false);
    modules.registerButton("S12 velocity", false);
    modules.registerButton("S12 self velocity", false);
    modules.registerButton("S27 explosion", false);
    modules.registerButton("S32 transaction", false);
}

int packetSendPriority() {
    return (int) modules.getSlider("Send priority");
}

boolean affectsOutbound() {
    return modules.isMode("Direction", "Outbound") || modules.isMode("Direction", "Both");
}

boolean affectsInbound() {
    return modules.isMode("Direction", "Inbound") || modules.isMode("Direction", "Both");
}

boolean isQueueAction() {
    return modules.isMode("Action", "Queue");
}

boolean matchesC03Position(Object packet) {
    return packets.isMovement(packet) && packets.hasPosition(packet);
}

boolean matchesC03Rotation(Object packet) {
    return packets.isMovement(packet) && !packets.hasPosition(packet);
}

boolean matchesC02Interact(Object packet) {
    return packets.isUseEntity(packet) && !packets.isAttack(packet);
}

boolean matchesInboundChat(Object packet) {
    return packets.isChat(packet) && !packets.isChatSend(packet);
}

boolean shouldTargetOutbound(Object packet) {
    if (modules.getButton("All outbound"))
        return true;
    if (modules.getButton("C00 keep alive") && packets.isKeepAlive(packet))
        return true;
    if (modules.getButton("C01 chat") && packets.isChatSend(packet))
        return true;
    if (modules.getButton("C02 attack") && packets.isAttack(packet))
        return true;
    if (modules.getButton("C02 interact") && matchesC02Interact(packet))
        return true;
    if (modules.getButton("C03 position") && matchesC03Position(packet))
        return true;
    if (modules.getButton("C03 rotation") && matchesC03Rotation(packet))
        return true;
    if (modules.getButton("C07 block dig") && packets.isBlockDig(packet))
        return true;
    if (modules.getButton("C07 release item") && packets.isReleaseUseItem(packet))
        return true;
    if (modules.getButton("C08 use/place") && packets.isBlockPlacement(packet))
        return true;
    if (modules.getButton("C09 held item") && packets.isHeldItemChange(packet))
        return true;
    if (modules.getButton("C0A swing") && packets.isAnimation(packet))
        return true;
    if (modules.getButton("C0B entity action") && packets.isEntityAction(packet))
        return true;
    if (modules.getButton("C0C steer vehicle") && packets.isSteerVehicle(packet))
        return true;
    if (modules.getButton("C0F transaction") && packets.isClientTransaction(packet))
        return true;
    if (modules.getButton("C15 client settings") && packets.isClientSettings(packet))
        return true;
    if (modules.getButton("C17 custom payload") && packets.isCustomPayload(packet))
        return true;
    return false;
}

boolean shouldTargetInbound(Object packet) {
    if (modules.getButton("All inbound"))
        return true;
    if (modules.getButton("S00 keep alive") && packets.isKeepAlive(packet))
        return true;
    if (modules.getButton("S02 chat") && matchesInboundChat(packet))
        return true;
    if (modules.getButton("S06 health") && packets.isUpdateHealth(packet))
        return true;
    if (modules.getButton("S08 pos look") && packets.isPlayerPosLook(packet))
        return true;
    if (modules.getButton("S12 velocity") && packets.isVelocity(packet))
        return true;
    if (modules.getButton("S12 self velocity") && packets.isSelfVelocity(packet))
        return true;
    if (modules.getButton("S27 explosion") && packets.isExplosion(packet))
        return true;
    if (modules.getButton("S32 transaction") && packets.isServerTransaction(packet))
        return true;
    return false;
}

void flushOutbound() {
    for (Object p : outboundBuffer)
        packets.sendReleased(p);
    outboundBuffer.clear();
}

void flushInbound() {
    for (Object p : inboundBuffer)
        packets.processInbound(p);
    inboundBuffer.clear();
}

void flushAll() {
    flushOutbound();
    flushInbound();
}

void onPreUpdate() {
    if (!isQueueAction())
        return;

    long holdMs = (long) modules.getSlider("Hold ms");
    long gapMs = (long) modules.getSlider("Gap ms");
    long elapsed = client.time() - phaseStart;

    if (phaseStart == 0)
        phaseStart = client.time();

    if (holding) {
        if (elapsed >= holdMs) {
            flushAll();
            holding = false;
            phaseStart = client.time();
        }
    } else {
        if (elapsed >= gapMs) {
            holding = true;
            phaseStart = client.time();
        }
    }
}

boolean onPacketSend(Object packet) {
    if (!affectsOutbound() || !shouldTargetOutbound(packet))
        return false;

    if (!isQueueAction())
        return true;

    if (!holding)
        return false;

    outboundBuffer.add(packet);
    return true;
}

boolean onPacketReceive(Object packet) {
    if (!affectsInbound() || !shouldTargetInbound(packet))
        return false;

    if (!isQueueAction())
        return true;

    if (!holding)
        return false;

    inboundBuffer.add(packet);
    return true;
}

void onScriptDisable() {
    flushAll();
    holding = true;
    phaseStart = 0;
}

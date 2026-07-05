// Packet queue / disable example — educational, not an anti-cheat bypass.
//
// Mode 0 Queue: HOLD matching outbound packets → FLUSH buffer → GAP → repeat.
// Mode 1 Disable: drop matching outbound packets while enabled (no buffer, no flush).
//
// Each toggle targets one packet family (use the script API predicates in packets.*).
// Movement is split into position C03 (C04/C06) vs rotation-only C03 (C05 look).
//
// packetSendPriority(): higher runs first; first true cancels the send.
//   FreeLook=200, AutoBlock=100, lag modules=0, script default=-100.

List<Object> buffer = new ArrayList<Object>();
long phaseStart = 0;
boolean holding = true;

void onLoad() {
    modules.registerButton("Disable mode", false);
    modules.registerSlider("Hold ms", 500f, 50f, 5000f);
    modules.registerSlider("Gap ms", 200f, 0f, 2000f);
    modules.registerSlider("Send priority", 50f, -100f, 200f);

    modules.registerButton("C03 position", true);
    modules.registerButton("C03 rotation", false);
    modules.registerButton("C02 attack", false);
    modules.registerButton("C02 interact", false);
    modules.registerButton("C0A swing", false);
    modules.registerButton("C08 block place", false);
    modules.registerButton("C07 release item", false);
    modules.registerButton("C00 keep alive", false);
    modules.registerButton("C0F transaction", false);
    modules.registerButton("C0C steer vehicle", false);
}

int packetSendPriority() {
    return (int) modules.getSlider("Send priority");
}

boolean isQueueMode() {
    return modules.getSlider("Mode") < 0.5f;
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

boolean shouldTarget(Object packet) {
    if (modules.getButton("C03 position") && matchesC03Position(packet))
        return true;
    if (modules.getButton("C03 rotation") && matchesC03Rotation(packet))
        return true;
    if (modules.getButton("C02 attack") && packets.isAttack(packet))
        return true;
    if (modules.getButton("C02 interact") && matchesC02Interact(packet))
        return true;
    if (modules.getButton("C0A swing") && packets.isAnimation(packet))
        return true;
    if (modules.getButton("C08 block place") && packets.isBlockPlacement(packet))
        return true;
    if (modules.getButton("C07 release item") && packets.isReleaseUseItem(packet))
        return true;
    if (modules.getButton("C00 keep alive") && packets.isKeepAlive(packet))
        return true;
    if (modules.getButton("C0F transaction") && packets.isClientTransaction(packet))
        return true;
    if (modules.getButton("C0C steer vehicle") && packets.isSteerVehicle(packet))
        return true;
    return false;
}

void flushBuffer() {
    for (Object p : buffer)
        packets.sendReleased(p);
    buffer.clear();
}

void onPreUpdate() {
    if (!isQueueMode())
        return;

    long holdMs = (long) modules.getSlider("Hold ms");
    long gapMs = (long) modules.getSlider("Gap ms");
    long elapsed = client.time() - phaseStart;

    if (phaseStart == 0)
        phaseStart = client.time();

    if (holding) {
        if (elapsed >= holdMs) {
            flushBuffer();
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
    if (!shouldTarget(packet))
        return false;

    if (!isQueueMode())
        return true;

    if (!holding)
        return false;

    buffer.add(packet);
    return true;
}

void onScriptDisable() {
    flushBuffer();
    holding = true;
    phaseStart = 0;
}

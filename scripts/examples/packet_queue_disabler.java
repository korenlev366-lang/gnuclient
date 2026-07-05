// Simple packet disabler / blink cycle — educational example, not an anti-cheat bypass.
//
// Cycle: HOLD (queue matching outbound packets) → FLUSH (release buffer) → GAP (pass-through) → repeat.
//
// packetSendPriority() demo:
//   Listeners with HIGHER priority run FIRST in onPacketSend. The first listener that returns true
//   cancels the send — lower-priority listeners never see that packet.
//
//   Native reference points (outbound send order):
//     FreeLook      = 200  (runs first)
//     AutoBlock     = 100
//     Lagrange / KnockbackDelay / Backtrack = 0
//     Scripts (default if you omit packetSendPriority) = -100  (runs last)
//
//   Raise "Send priority" above 0 only if this script must win over native lag modules for the
//   same packet. Lower it (e.g. -100) to let built-in modules hold packets first.

List<Object> buffer = new ArrayList<Object>();
long phaseStart = 0;
boolean holding = true;

void onLoad() {
    modules.registerSlider("Hold ms", 500f, 50f, 5000f);
    modules.registerSlider("Gap ms", 200f, 0f, 2000f);
    modules.registerSlider("Send priority", 50f, -100f, 200f);

    modules.registerButton("Hold movement", true);
    modules.registerButton("Hold transactions", false);
    modules.registerButton("Hold keep-alive", false);
    modules.registerButton("Hold all outbound", false);
}

int packetSendPriority() {
    return (int) modules.getSlider("Send priority");
}

boolean shouldHold(Object packet) {
    if (modules.getButton("Hold all outbound"))
        return true;
    if (modules.getButton("Hold movement") && packets.isMovement(packet))
        return true;
    if (modules.getButton("Hold transactions") && packets.isTransaction(packet))
        return true;
    if (modules.getButton("Hold keep-alive") && packets.isKeepAlive(packet))
        return true;
    return false;
}

void flushBuffer() {
    for (Object p : buffer)
        packets.sendReleased(p);
    buffer.clear();
}

void onPreUpdate() {
    long holdMs = (long) modules.getSlider("Hold ms");
    long gapMs = (long) modules.getSlider("Gap ms");
    long elapsed = client.time() - phaseStart;

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
    if (!holding)
        return false;
    if (!shouldHold(packet))
        return false;

    buffer.add(packet);
    return true;
}

void onScriptDisable() {
    flushBuffer();
    holding = true;
    phaseStart = 0;
}

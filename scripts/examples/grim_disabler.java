// Grim disabler — test helpers for your own Grim server.
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.
//
// Clever bits (vs just blocking S08):
//   • Inflate velocity (S12 XYZ) — Grim's KB/explosion sandwich predicts a bigger
//     vector, so the offset window is wider for a few ticks after each hit.
//   • Txn delay — hold C0F confirms briefly so velocity sandwiches stay in the
//     "first bread" uncertain state longer (wider prediction tolerance).
//
// BlockSetback still causes BadPacketsN — default OFF. See Grim-bypass-log.md.
// Do NOT use Dummy steer with Grim Fly — extra C0C/tick flags VehicleTimer.

List<Object> txnBuffer = new ArrayList<Object>();
long txnHoldStart = 0;

void onLoad() {
    modules.registerButton("BlockSetback", false);
    modules.registerButton("Inflate velocity", true);
    modules.registerSlider("VelMult", 2.0f, 1.0f, 5.0f);
    modules.registerButton("Include down", true);
    modules.registerButton("Txn delay", false);
    modules.registerSlider("Txn delay ms", 50f, 0f, 200f);
}

boolean onPacketSend(Object packet) {
    if (modules.getButton("Txn delay") && packets.isClientTransaction(packet)) {
        if (txnHoldStart == 0)
            txnHoldStart = client.time();
        txnBuffer.add(packet);
        return true;
    }
    return false;
}

boolean onPacketReceive(Object packet) {
    if (modules.getButton("BlockSetback") && packets.isPlayerPosLook(packet))
        return true;

    if (modules.getButton("Inflate velocity") && packets.isSelfVelocity(packet)) {
        int mx = packets.velocityMotionX(packet);
        int my = packets.velocityMotionY(packet);
        int mz = packets.velocityMotionZ(packet);
        if (mx == 0 && my == 0 && mz == 0)
            return false;

        float mult = modules.getSlider("VelMult");
        boolean down = modules.getButton("Include down");

        if (mx != 0)
            packets.setVelocityMotionX(packet, (int) (mx * mult));
        if (mz != 0)
            packets.setVelocityMotionZ(packet, (int) (mz * mult));
        if (my > 0 || (down && my < 0))
            packets.setVelocityMotionY(packet, (int) (my * mult));
    }
    return false;
}

void onPreUpdate() {
    if (modules.getButton("Txn delay") && !txnBuffer.isEmpty()) {
        long delay = (long) modules.getSlider("Txn delay ms");
        if (client.time() - txnHoldStart >= delay) {
            for (Object p : txnBuffer)
                packets.sendReleased(p);
            txnBuffer.clear();
            txnHoldStart = 0;
        }
    }
}

void onScriptDisable() {
    for (Object p : txnBuffer)
        packets.sendReleased(p);
    txnBuffer.clear();
    txnHoldStart = 0;
}

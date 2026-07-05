// Grim Setback Sync — NOT a movement bypass.
//
// Grim patched "reuse setback velocity to bypass movement checks". This script only:
//   • Accepts S08 setbacks (snap client) — prevents BadPacketsN from ignored teleports
//   • Syncs outgoing C03 to client pos + real onGround for a few ticks after S08
//
// It does NOT widen lenience, inflate KB, or reuse setback velocity. For fast travel on
// Grim 1.8.9 use grim_fly vehicle mode (Simulation skipped while mounted).

void onLoad() {
    modules.registerButton("Accept setbacks", true);
    modules.registerSlider("Setback lock", 3f, 1f, 10f);
}

int packetSendPriority() {
    return 80;
}

void snapSetback(Object packet) {
    double x = packets.posLookX(packet);
    double y = packets.posLookY(packet);
    double z = packets.posLookZ(packet);
    float yaw = packets.posLookYaw(packet);
    float pitch = packets.posLookPitch(packet);
    if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z))
        return;

    client.setPlayerPosition(x, y, z);
    if (!Float.isNaN(yaw) && !Float.isNaN(pitch))
        client.setRotation(yaw, pitch);

    int lock = (int) modules.getSlider("Setback lock");
    if (lock < 1) lock = 1;
    lenience.setSetbackTicks(lock);
}

boolean onPacketReceive(Object packet) {
    if (modules.getButton("Accept setbacks") && packets.isPlayerPosLook(packet)) {
        snapSetback(packet);
        return false;
    }
    return false;
}

boolean onPacketSend(Object packet) {
    if (lenience.getSetbackTicks() <= 0 || !packets.isMovement(packet))
        return false;

    if (packets.hasPosition(packet)) {
        packets.setMovementPosition(packet, client.getPosX(), client.getPosY(), client.getPosZ());
        packets.setMovementOnGround(packet, client.isOnGround());
    } else {
        packets.setMovementOnGround(packet, client.isOnGround());
    }
    return false;
}

void onPreUpdate() {
    lenience.decayTick();
}

void onScriptDisable() {
    lenience.reset();
}

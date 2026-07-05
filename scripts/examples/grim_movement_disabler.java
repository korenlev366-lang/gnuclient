// DISCLAIMER: Does NOT bypass Grim. Bad experimental attempt — see scripts/examples/README.md.
//
// Grim Movement Disabler — keeps Grim movement checks quiet so grim_speed can run.
//
// Does NOT speed you up by itself. It:
//   • Accepts S08 setbacks (snap client + sync C03) — anti BadPacketsN / GroundSpoof
//   • Publishes lenience windows to grim.* for grim_speed to ride (KB / explosion / setback)
//
// Enable this BEFORE grim_speed. Never block/cancel S08.

void onLoad() {
    modules.registerButton("Accept setbacks", true);
    modules.registerSlider("Setback lock", 5f, 1f, 15f);
    modules.registerButton("Force ground on setback", true);
    modules.registerButton("Zero motion on setback", true);

    modules.registerButton("Track KB window", true);
    modules.registerSlider("KB window", 15f, 5f, 40f);
    modules.registerButton("Track expl window", true);
    modules.registerSlider("Expl window", 15f, 5f, 40f);
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
    if (modules.getButton("Zero motion on setback"))
        client.setMotion(0, 0, 0);
    if (modules.getButton("Force ground on setback"))
        client.setOnGround(true);

    int lock = (int) modules.getSlider("Setback lock");
    if (lock < 1) lock = 1;
    grim.setSetbackTicks(lock);
}

boolean onPacketReceive(Object packet) {
    if (modules.getButton("Accept setbacks") && packets.isPlayerPosLook(packet)) {
        snapSetback(packet);
        return false;
    }

    if (modules.getButton("Track KB window") && packets.isSelfVelocity(packet)) {
        int mx = packets.velocityMotionX(packet);
        int mz = packets.velocityMotionZ(packet);
        if (mx != 0 || mz != 0) {
            int w = (int) modules.getSlider("KB window");
            grim.bumpKbWindow(w);
        }
        return false;
    }

    if (modules.getButton("Track expl window") && packets.isExplosion(packet)) {
        float mx = packets.explosionMotionX(packet);
        float mz = packets.explosionMotionZ(packet);
        if (mx != 0f || mz != 0f) {
            int w = (int) modules.getSlider("Expl window");
            grim.bumpExplWindow(w);
        }
        return false;
    }
    return false;
}

boolean onPacketSend(Object packet) {
    if (grim.getSetbackTicks() <= 0 || !packets.isMovement(packet))
        return false;

    if (packets.hasPosition(packet)) {
        packets.setMovementPosition(packet, client.getPosX(), client.getPosY(), client.getPosZ());
        if (modules.getButton("Force ground on setback"))
            packets.setMovementOnGround(packet, true);
        else
            packets.setMovementOnGround(packet, client.isOnGround());
    } else if (modules.getButton("Force ground on setback")) {
        packets.setMovementOnGround(packet, true);
    }
    return false;
}

void onPreUpdate() {
    grim.decayTick();
}

void onScriptDisable() {
    grim.reset();
}

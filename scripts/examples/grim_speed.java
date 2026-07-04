// Grim Speed — 8+ bps on Grim 1.8.9 (script, not a native module).
//
// Mode 0 Grim Strafe (DEFAULT): sprint + landing jump + alternating air strafe.
//   ~8.0–9.2 bps on flat without timer (Grim Timer flags ~1.01).
// Mode 1 Bhop: sprint + jump only — manual strafe needed for 8 bps.
// Mode 2 Micro: 0.03 C03 creep (slow; lenience boost only).
// Mode 3 Knockback: bhop during real inbound S12 windows.
//
// Pair with grim_movement_disabler for setback sync. Never scales setMotion in a loop.

int strafeFlip = 1;
boolean wasOnGround = true;
boolean autoStrafeLeft = false;
boolean autoStrafeRight = false;

double microX = 0;
double microY = 0;
double microZ = 0;
boolean microReady = false;

int kbTicks = 0;

void onLoad() {
    modules.registerSlider("Mode", 0f, 0f, 3f);
    modules.registerButton("Auto sprint", true);
    modules.registerButton("Bhop", true);
    modules.registerButton("Auto strafe", true);
    modules.registerSlider("Micro step", 0.029f, 0.01f, 0.029f);
    modules.registerButton("Lenience micro", true);
}

int packetSendPriority() {
    return 50;
}

float activeMode() {
    if (client.isRiding())
        return -1f;
    return modules.getSlider("Mode");
}

void releaseAutoStrafe() {
    if (autoStrafeLeft) {
        client.setLeftKey(false);
        autoStrafeLeft = false;
    }
    if (autoStrafeRight) {
        client.setRightKey(false);
        autoStrafeRight = false;
    }
}

void tickStrafeBhop() {
    if (!keybinds.isMovementDown()) {
        releaseAutoStrafe();
        return;
    }
    if (status.isUsingItem())
        return;

    if (modules.getButton("Auto sprint"))
        client.setSprintKey(true);

    boolean onGround = client.isOnGround();

    if (modules.getButton("Auto strafe") && !onGround && keybinds.isForwardDown()
            && !keybinds.isLeftDown() && !keybinds.isRightDown()) {
        if (strafeFlip > 0) {
            client.setLeftKey(true);
            client.setRightKey(false);
            autoStrafeLeft = true;
            autoStrafeRight = false;
        } else {
            client.setRightKey(true);
            client.setLeftKey(false);
            autoStrafeRight = true;
            autoStrafeLeft = false;
        }
    } else {
        releaseAutoStrafe();
    }

    if (onGround && !wasOnGround)
        strafeFlip = -strafeFlip;
    wasOnGround = onGround;

    if (!modules.getButton("Bhop"))
        return;
    if (onGround && client.getMotionY() <= 0.0)
        client.setJump(true);
}

void tickBhop() {
    if (!keybinds.isMovementDown())
        return;
    if (modules.getButton("Auto sprint"))
        client.setSprintKey(true);
    if (!modules.getButton("Bhop"))
        return;
    if (!client.isOnGround() || status.isUsingItem())
        return;
    if (client.getMotionY() <= 0.0)
        client.setJump(true);
}

void tickMicro() {
    if (!keybinds.isMovementDown())
        return;

    float step = modules.getSlider("Micro step");
    if (step > 0.029f) step = 0.029f;

    if (!microReady) {
        microX = client.getPosX();
        microY = client.getPosY();
        microZ = client.getPosZ();
        microReady = true;
    }

    float forward = 0f;
    if (keybinds.isForwardDown()) forward += 1f;
    if (keybinds.isBackDown()) forward -= 1f;
    float strafe = 0f;
    if (keybinds.isLeftDown()) strafe += 1f;
    if (keybinds.isRightDown()) strafe -= 1f;

    if (forward == 0f && strafe == 0f)
        return;

    double yaw = Math.toRadians(client.getYaw());
    double sin = Math.sin(yaw);
    double cos = Math.cos(yaw);
    microX += (-sin * forward + cos * strafe) * step;
    microZ += (cos * forward + sin * strafe) * step;
    microY = client.getPosY();

    client.setPlayerPosition(microX, microY, microZ);
    client.setMotion(0, 0, 0);
}

void tickKnockback() {
    if (kbTicks <= 0 && grim.getKbWindow() <= 0 && grim.getExplWindow() <= 0)
        return;
    if (kbTicks > 0)
        kbTicks--;
    if (modules.getButton("Auto sprint") && keybinds.isForwardDown())
        client.setSprintKey(true);
    if (client.isOnGround() && modules.getButton("Bhop") && client.getMotionY() <= 0.0)
        client.setJump(true);
}

boolean onPacketSend(Object packet) {
    float mode = activeMode();
    if (!packets.isMovement(packet) || !packets.hasPosition(packet))
        return false;

    if (mode == 2f && microReady) {
        packets.setMovementPosition(packet, microX, microY, microZ);
        packets.setMovementOnGround(packet, true);
        return false;
    }

    if (!modules.getButton("Lenience micro") || grim.getSetbackTicks() > 0)
        return false;
    if (!grim.lenient() || mode == 2f)
        return false;
    if (!keybinds.isMovementDown())
        return false;

    float step = modules.getSlider("Micro step");
    if (step > 0.029f) step = 0.029f;

    float forward = 0f;
    if (keybinds.isForwardDown()) forward += 1f;
    if (keybinds.isBackDown()) forward -= 1f;
    float strafe = 0f;
    if (keybinds.isLeftDown()) strafe += 1f;
    if (keybinds.isRightDown()) strafe -= 1f;
    if (forward == 0f && strafe == 0f)
        forward = 1f;

    double yaw = Math.toRadians(client.getYaw());
    double sin = Math.sin(yaw);
    double cos = Math.cos(yaw);
    double nx = client.getPosX() + (-sin * forward + cos * strafe) * step;
    double nz = client.getPosZ() + (cos * forward + sin * strafe) * step;
    packets.setMovementPosition(packet, nx, client.getPosY(), nz);
    packets.setMovementOnGround(packet, client.isOnGround());
    return false;
}

boolean onPacketReceive(Object packet) {
    if (activeMode() != 3f || !packets.isSelfVelocity(packet))
        return false;

    int mx = packets.velocityMotionX(packet);
    int my = packets.velocityMotionY(packet);
    int mz = packets.velocityMotionZ(packet);
    if (mx == 0 && my == 0 && mz == 0)
        return false;

    kbTicks = 40;
    grim.bumpKbWindow(40);
    return false;
}

void onPreUpdate() {
    float mode = activeMode();
    if (mode < 0f)
        return;

    if (mode == 0f)
        tickStrafeBhop();
    else if (mode == 1f)
        tickBhop();
    else if (mode == 2f)
        tickMicro();
    else if (mode == 3f)
        tickKnockback();
}

void onScriptDisable() {
    releaseAutoStrafe();
    microReady = false;
    kbTicks = 0;
    wasOnGround = true;
    strafeFlip = 1;
}

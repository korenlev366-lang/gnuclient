// Grim Speed — on-foot movement for Grim 1.8.9 (vanilla physics; Grim simulates foot movement).
//
// Grim runs full Simulation on foot. Packet nudges / timer / setback lenience flag or are patched.
// ~8 bps flat needs strafe bhop + air yaw steer (what good players do with the mouse manually).
// Faster than ~8 bps on Grim → grim_fly mode 0 (boat/vehicle; Simulation skipped on 1.8.9).
//
// Mode 0 Grim Strafe: MovementInput patch + optional air steer.
// Mode 1 Bhop: auto jump/sprint, manual strafe + steer.
// Mode 2 Micro: 0.03 C03 creep (slow point-three test only).

int strafeFlip = 1;
boolean wasOnGround = true;

double microX = 0;
double microY = 0;
double microZ = 0;
boolean microReady = false;

void onLoad() {
    modules.registerSlider("Mode", 0f, 0f, 2f);
    modules.registerButton("Auto sprint", true);
    modules.registerButton("Bhop", true);
    modules.registerButton("Auto strafe", true);
    modules.registerButton("Air steer", true);
    modules.registerSlider("Steer deg", 11f, 4f, 20f);
    modules.registerSlider("Micro step", 0.029f, 0.01f, 0.029f);
}

int packetSendPriority() {
    return 50;
}

float activeMode() {
    if (client.isRiding())
        return -1f;
    return modules.getSlider("Mode");
}

boolean wantsAutoStrafe(float mode, float forward, float strafe, boolean onGround, boolean landing) {
    if (forward <= 0f || strafe != 0f)
        return false;
    if (mode == 0f && modules.getButton("Auto strafe"))
        return !onGround || landing;
    return false;
}

void patchMovementInput(Object movInput) {
    float mode = activeMode();
    if (mode < 0f || mode == 2f)
        return;
    if (!keybinds.isMovementDown())
        return;
    if (status.isUsingItem())
        return;

    boolean onGround = client.isOnGround();
    boolean landing = onGround && !wasOnGround;
    if (landing)
        strafeFlip = -strafeFlip;

    float forward = 0f;
    if (keybinds.isForwardDown()) forward += 1f;
    if (keybinds.isBackDown()) forward -= 1f;
    float strafe = 0f;
    if (keybinds.isLeftDown()) strafe += 1f;
    if (keybinds.isRightDown()) strafe -= 1f;

    boolean autoStrafe = wantsAutoStrafe(mode, forward, strafe, onGround, landing);
    if (autoStrafe)
        strafe = strafeFlip > 0 ? 1f : -1f;

    if (forward == 0f && strafe == 0f) {
        wasOnGround = onGround;
        return;
    }

    if (modules.getButton("Air steer") && !onGround && (autoStrafe || mode == 1f)) {
        float deg = modules.getSlider("Steer deg");
        if (deg > 20f) deg = 20f;
        if (strafe > 0f)
            client.setRotation(client.getYaw() - deg, client.getPitch());
        else if (strafe < 0f)
            client.setRotation(client.getYaw() + deg, client.getPitch());
    }

    boolean jump = modules.getButton("Bhop") && onGround;
    client.setMovementInput(movInput, forward, strafe, jump);

    if (modules.getButton("Auto sprint") && forward > 0f) {
        client.setSprintKey(true);
        client.setSprinting(true);
    }

    wasOnGround = onGround;
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
        forward = 1f;

    double yaw = Math.toRadians(client.getYaw());
    double sin = Math.sin(yaw);
    double cos = Math.cos(yaw);
    microX += (-sin * forward + cos * strafe) * step;
    microZ += (cos * forward + sin * strafe) * step;
    microY = client.getPosY();

    client.setPlayerPosition(microX, microY, microZ);
    client.setMotion(0, 0, 0);
}

boolean onPacketSend(Object packet) {
    if (activeMode() != 2f || !microReady)
        return false;
    if (!packets.isMovement(packet) || !packets.hasPosition(packet))
        return false;
    packets.setMovementPosition(packet, microX, microY, microZ);
    packets.setMovementOnGround(packet, true);
    return false;
}

void onPreUpdate() {
    if (activeMode() == 2f)
        tickMicro();
}

void onScriptDisable() {
    microReady = false;
    wasOnGround = true;
    strafeFlip = 1;
}

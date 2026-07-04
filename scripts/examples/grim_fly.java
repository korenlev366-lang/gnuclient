// Grim Fly — abuses real Grim 1.8.9 gaps, not generic blink garbage.
//
// Mode 0 Vehicle: Simulation SKIPPED while mounted on 1.8.9 (boat/horse/pig/minecart).
// Mode 1 Micro:    0.03 position steps — Grim's point-three window (slow creep up).
// Mode 2 Knockback: inflate S12 and ride the lenience window (hit yourself / explosion).
// Mode 3 Blink:    hold C03 + txn, teleport client-side, release one packet.
//
// Auto vehicle: when riding, always uses mode 0 regardless of slider.

List<Object> blinkMove = new ArrayList<Object>();
List<Object> blinkTxn = new ArrayList<Object>();
long blinkStart = 0;
long txnStart = 0;

double microX = 0;
double microY = 0;
double microZ = 0;
boolean microReady = false;

int kbTicks = 0;

void onLoad() {
    modules.registerSlider("Mode", 0f, 0f, 3f);
    modules.registerButton("Auto vehicle", true);
    modules.registerSlider("Steer", 0.98f, 0.1f, 0.98f);
    modules.registerSlider("Micro step", 0.029f, 0.01f, 0.03f);
    modules.registerSlider("KB mult", 4.0f, 1.0f, 8.0f);
    modules.registerSlider("Blink ms", 150f, 50f, 500f);
    modules.registerSlider("Txn ms", 100f, 0f, 300f);
    modules.registerSlider("Blink V", 0.42f, 0.1f, 1.2f);
    modules.registerButton("Jump on space", true);
}

int packetSendPriority() {
    return 50;
}

float activeMode() {
    if (modules.getButton("Auto vehicle") && client.isRiding())
        return 0f;
    return modules.getSlider("Mode");
}

boolean onPacketSend(Object packet) {
    float mode = activeMode();

    if (mode == 0f && client.isRiding() && packets.isSteerVehicle(packet)) {
        float steer = modules.getSlider("Steer");
        if (steer > 0.98f) steer = 0.98f;

        float yaw = client.getYaw();
        float forward = 0f;
        if (keybinds.isForwardDown()) forward += 1f;
        if (keybinds.isBackDown()) forward -= 1f;
        float strafe = 0f;
        if (keybinds.isLeftDown()) strafe += 1f;
        if (keybinds.isRightDown()) strafe -= 1f;

        if (forward != 0f) {
            if (strafe > 0f) yaw += forward > 0f ? -45f : 45f;
            else if (strafe < 0f) yaw += forward > 0f ? 45f : -45f;
            strafe = 0f;
            forward = forward > 0f ? 1f : -1f;
        }

        packets.setSteerForward(packet, forward * steer);
        packets.setSteerStrafe(packet, strafe * steer);
        packets.setSteerJump(packet, modules.getButton("Jump on space") && keybinds.isJumpDown());
        return false;
    }

    if (mode == 1f && !client.isRiding() && packets.isMovement(packet) && packets.hasPosition(packet) && microReady) {
        packets.setMovementPosition(packet, microX, microY, microZ);
        packets.setMovementOnGround(packet, false);
        return false;
    }

    if (mode == 3f && !client.isRiding()) {
        if (packets.isMovement(packet) && packets.hasPosition(packet)) {
            if (blinkStart == 0) blinkStart = client.time();
            blinkMove.add(packet);
            return true;
        }
        if (packets.isClientTransaction(packet)) {
            if (txnStart == 0) txnStart = client.time();
            blinkTxn.add(packet);
            return true;
        }
    }
    return false;
}

boolean onPacketReceive(Object packet) {
    if (activeMode() != 2f || !packets.isSelfVelocity(packet))
        return false;

    int mx = packets.velocityMotionX(packet);
    int my = packets.velocityMotionY(packet);
    int mz = packets.velocityMotionZ(packet);
    if (mx == 0 && my == 0 && mz == 0)
        return false;

    float mult = modules.getSlider("KB mult");
    if (mx != 0) packets.setVelocityMotionX(packet, (int) (mx * mult));
    if (mz != 0) packets.setVelocityMotionZ(packet, (int) (mz * mult));
    if (my != 0) packets.setVelocityMotionY(packet, (int) (my * mult));
    kbTicks = 60;
    return false;
}

void tickMicro() {
    float step = modules.getSlider("Micro step");
    if (step > 0.029f) step = 0.029f;

    if (!microReady) {
        microX = client.getPosX();
        microY = client.getPosY();
        microZ = client.getPosZ();
        microReady = true;
    }

    double nx = microX;
    double ny = microY;
    double nz = microZ;

    if (modules.getButton("Jump on space") && keybinds.isJumpDown())
        ny += step;

    float forward = 0f;
    if (keybinds.isForwardDown()) forward += 1f;
    if (keybinds.isBackDown()) forward -= 1f;
    float strafe = 0f;
    if (keybinds.isLeftDown()) strafe += 1f;
    if (keybinds.isRightDown()) strafe -= 1f;

    if (forward != 0f || strafe != 0f) {
        double yaw = Math.toRadians(client.getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        nx += (-sin * forward + cos * strafe) * step;
        nz += (cos * forward + sin * strafe) * step;
    }

    microX = nx;
    microY = ny;
    microZ = nz;
    client.setPlayerPosition(nx, ny, nz);
    client.setMotion(0, 0, 0);
}

void tickBlink() {
    float v = modules.getSlider("Blink V");
    double mx = client.getMotionX();
    double my = client.getMotionY();
    double mz = client.getMotionZ();

    if (keybinds.isJumpDown()) my = v;
    else my = 0.02;
    if (keybinds.isMovementDown()) {
        mx *= 1.2;
        mz *= 1.2;
    }

    client.setMotion(mx, my, mz);

    long now = client.time();
    long blinkDelay = (long) modules.getSlider("Blink ms");
    long txnDelay = (long) modules.getSlider("Txn ms");

    if (!blinkMove.isEmpty() && now - blinkStart >= blinkDelay) {
        Object last = blinkMove.get(blinkMove.size() - 1);
        packets.setMovementPosition(last, client.getPosX(), client.getPosY(), client.getPosZ());
        packets.setMovementOnGround(last, client.isOnGround());
        packets.sendReleased(last);
        blinkMove.clear();
        blinkStart = 0;
    }

    if (!blinkTxn.isEmpty() && now - txnStart >= txnDelay) {
        for (Object p : blinkTxn)
            packets.sendReleased(p);
        blinkTxn.clear();
        txnStart = 0;
    }
}

void onPreUpdate() {
    float mode = activeMode();

    if (mode == 0f && client.isRiding()) {
        if (modules.getButton("Jump on space") && keybinds.isJumpDown())
            client.setJump(true);
        return;
    }

    if (mode == 1f && !client.isRiding())
        tickMicro();

    if (mode == 2f && kbTicks > 0) {
        kbTicks--;
        if (keybinds.isJumpDown())
            client.setJump(true);
    }

    if (mode == 3f && !client.isRiding())
        tickBlink();
}

void onScriptDisable() {
    microReady = false;
    kbTicks = 0;
    if (!blinkMove.isEmpty()) {
        Object last = blinkMove.get(blinkMove.size() - 1);
        packets.setMovementPosition(last, client.getPosX(), client.getPosY(), client.getPosZ());
        packets.sendReleased(last);
        blinkMove.clear();
    }
    for (Object p : blinkTxn)
        packets.sendReleased(p);
    blinkTxn.clear();
    blinkStart = 0;
    txnStart = 0;
}

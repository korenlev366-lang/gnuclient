// Grim Fly — on-foot blink + disabler helpers (1.8.9 test server).
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.
//
// Buffers outbound C03 while you move client-side, then releases ONE position
// packet (not a burst). Pair with velocity inflate + txn delay to widen Grim's
// prediction window. BlockSetback OFF by default (BadPacketsN).
//
// On-foot fly still fights Simulation — this is the least-bad script approach.

List<Object> txnBuffer = new ArrayList<Object>();
Object heldMove = null;
long blinkHoldStart = 0;
long txnHoldStart = 0;

void onLoad() {
    modules.registerSlider("V speed", 0.42f, 0.05f, 1.2f);
    modules.registerSlider("H mult", 1.0f, 0.5f, 2.5f);
    modules.registerSlider("Blink ms", 120f, 40f, 600f);
    modules.registerButton("Glide", true);
    modules.registerSlider("Glide fall", 0.04f, 0.0f, 0.2f);
    modules.registerButton("Blink C03", true);
    modules.registerButton("Txn delay", true);
    modules.registerSlider("Txn ms", 80f, 0f, 250f);
    modules.registerButton("Inflate velocity", true);
    modules.registerSlider("Vel mult", 2.5f, 1.0f, 6.0f);
    modules.registerButton("Vel down", true);
    modules.registerButton("Block setback", false);
    modules.registerButton("Sprint", true);
}

int packetSendPriority() {
    return 50;
}

boolean onPacketSend(Object packet) {
    if (client.isRiding())
        return false;

    if (modules.getButton("Blink C03") && packets.isMovement(packet) && packets.hasPosition(packet)) {
        heldMove = packet;
        if (blinkHoldStart == 0)
            blinkHoldStart = client.time();
        return true;
    }

    if (modules.getButton("Txn delay") && packets.isClientTransaction(packet)) {
        if (txnHoldStart == 0)
            txnHoldStart = client.time();
        txnBuffer.add(packet);
        return true;
    }
    return false;
}

boolean onPacketReceive(Object packet) {
    if (modules.getButton("Block setback") && packets.isPlayerPosLook(packet))
        return true;

    if (modules.getButton("Inflate velocity") && packets.isSelfVelocity(packet)) {
        int mx = packets.velocityMotionX(packet);
        int my = packets.velocityMotionY(packet);
        int mz = packets.velocityMotionZ(packet);
        if (mx == 0 && my == 0 && mz == 0)
            return false;

        float mult = modules.getSlider("Vel mult");
        boolean down = modules.getButton("Vel down");
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
    if (client.isRiding())
        return;

    double mx = client.getMotionX();
    double my = client.getMotionY();
    double mz = client.getMotionZ();

    float h = modules.getSlider("H mult");
    if (keybinds.isMovementDown()) {
        mx *= h;
        mz *= h;
    }

    float v = modules.getSlider("V speed");
    if (keybinds.isJumpDown())
        my = v;
    else if (modules.getButton("Glide"))
        my = -modules.getSlider("Glide fall");

    client.setMotion(mx, my, mz);

    if (modules.getButton("Sprint") && keybinds.isForwardDown())
        client.setSprintKey(true);

    long now = client.time();

    if (modules.getButton("Blink C03") && heldMove != null) {
        long blinkDelay = (long) modules.getSlider("Blink ms");
        if (now - blinkHoldStart >= blinkDelay) {
            packets.setMovementPosition(heldMove, client.getPosX(), client.getPosY(), client.getPosZ());
            packets.setMovementOnGround(heldMove, client.isOnGround());
            packets.sendReleased(heldMove);
            heldMove = null;
            blinkHoldStart = 0;
        }
    }

    if (modules.getButton("Txn delay") && !txnBuffer.isEmpty()) {
        long txnDelay = (long) modules.getSlider("Txn ms");
        if (now - txnHoldStart >= txnDelay) {
            for (Object p : txnBuffer)
                packets.sendReleased(p);
            txnBuffer.clear();
            txnHoldStart = 0;
        }
    }
}

void onScriptDisable() {
    if (heldMove != null) {
        packets.setMovementPosition(heldMove, client.getPosX(), client.getPosY(), client.getPosZ());
        packets.setMovementOnGround(heldMove, client.isOnGround());
        packets.sendReleased(heldMove);
        heldMove = null;
    }
    blinkHoldStart = 0;
    for (Object p : txnBuffer)
        packets.sendReleased(p);
    txnBuffer.clear();
    txnHoldStart = 0;
}

// DISCLAIMER: Does NOT bypass Grim. Bad experimental attempt — see scripts/examples/README.md.
//
// Grim disabler — LEGACY test helper. Prefer grim_movement_disabler.java for movement.
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.
//
// WARNING: Inbound velocity inflate does NOT change what server Grim predicts — it only
// makes your client move more than the server expects → Simulation flags.
// Txn delay holds C0F → Timer + BadPacketsN when combined with setbacks.
// BlockSetback (cancel S08) → BadPacketsN guaranteed.

void onLoad() {
    modules.registerButton("BlockSetback", false);
    modules.registerButton("Inflate velocity", false);
    modules.registerSlider("VelMult", 1.0f, 1.0f, 3.0f);
    modules.registerButton("Include down", false);
    modules.registerButton("Txn delay", false);
    modules.registerSlider("Txn delay ms", 0f, 0f, 100f);
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

// Grim disabler — test helpers for your own Grim server.
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.
//
// BlockSetback causes BadPacketsN — default OFF. See Grim-bypass-log.md.

void onLoad() {
    modules.registerButton("BlockSetback", false);
    modules.registerButton("BoostVelocity", false);
    modules.registerSlider("VelMult", 2.0f, 1.0f, 5.0f);
}

boolean onPacketReceive(Object packet) {
    if (modules.getButton("BlockSetback") && packets.isPlayerPosLook(packet))
        return true;

    if (modules.getButton("BoostVelocity") && packets.isSelfVelocity(packet)) {
        int my = packets.velocityMotionY(packet);
        if (my > 0) {
            float mult = modules.getSlider("VelMult");
            packets.setVelocityMotionY(packet, (int) (my * mult));
        }
    }
    return false;
}

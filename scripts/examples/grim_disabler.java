// Grim disabler — companion for grim_fly on your own test server.
// BlockSetback: eat S08 teleports. BoostVelocity: multiply inbound S12 Y.
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.

void onLoad() {
    modules.registerButton("BlockSetback", true);
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

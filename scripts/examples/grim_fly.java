// Grim Fly — 1.8.9 vehicle exemption (SERVER-SYNCED).
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.
//
// Server-synced steer only — do NOT use client motion spoof (causes rubberband).
// See SCRIPTING.md Grim section and gnu client dev/Grim-bypass-log.md.

void onLoad() {
    modules.registerSlider("Steer", 0.98f, 0.1f, 0.98f);
    modules.registerButton("Mouse yaw", true);
    modules.registerButton("Cancel vanilla steer", true);
}

boolean onPacketSend(Object packet) {
    if (!modules.getButton("Cancel vanilla steer"))
        return false;
    return packets.isSteerVehicle(packet);
}

void onPreUpdate() {
    if (!client.isRiding())
        return;

    float steer = modules.getSlider("Steer");
    if (steer > 0.98f)
        steer = 0.98f;

    float yaw = client.getYaw();
    if (modules.getButton("Mouse yaw"))
        client.setRotation(yaw, client.getPitch());

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

    float fwdSteer = forward * steer;
    float sideSteer = strafe * steer;
    boolean jump = keybinds.isJumpDown();

    client.sendSteer(sideSteer, fwdSteer, jump, false);
}

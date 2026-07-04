// Grim Fly — vehicle exemption (1.8.9). MUST be in a boat/minecart.
// Grim skips foot Simulation while mounted. Sets riding entity motion only.
// Do NOT send extra C0C steer packets — vanilla already sends one/tick (VehicleTimer).
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.
// Pair with grim_disabler (BlockSetback) on your test server.

void onLoad() {
    modules.registerSlider("H Speed", 0.55f, 0.05f, 2.5f);
    modules.registerSlider("V Speed", 0.45f, 0.05f, 2.0f);
    modules.registerSlider("Glide", -0.12f, -0.5f, 0.3f);
    modules.registerButton("Mouse yaw", true);
}

void onPreUpdate() {
    if (!client.isRiding())
        return;

    float hs = modules.getSlider("H Speed");
    if (client.isSprinting())
        hs = hs * 1.25f;

    double vy;
    if (keybinds.isJumpDown())
        vy = modules.getSlider("V Speed");
    else if (keybinds.isSneakDown())
        vy = -modules.getSlider("V Speed");
    else
        vy = modules.getSlider("Glide");

    float yaw = client.getYaw();
    if (modules.getButton("Mouse yaw"))
        client.setRotation(yaw, client.getPitch());

    double mx = 0.0;
    double mz = 0.0;
    float forward = 0f;
    if (keybinds.isForwardDown()) forward += 1f;
    if (keybinds.isBackDown()) forward -= 1f;
    float strafe = 0f;
    if (keybinds.isLeftDown()) strafe += 1f;
    if (keybinds.isRightDown()) strafe -= 1f;

    if (forward != 0f || strafe != 0f) {
        if (forward != 0f) {
            if (strafe > 0f) yaw += forward > 0f ? -45f : 45f;
            else if (strafe < 0f) yaw += forward > 0f ? 45f : -45f;
            strafe = 0f;
            forward = forward > 0f ? 1f : -1f;
        }
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        mx = forward * hs * -sin + strafe * hs * cos;
        mz = forward * hs * cos + strafe * hs * sin;
    }

    client.setRidingMotion(mx, vy, mz);
    client.setMotion(mx, vy, mz);
}

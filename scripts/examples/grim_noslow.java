// DISCLAIMER: Does NOT bypass Grim. Bad experimental attempt — see scripts/examples/README.md.
//
// Grim NoSlow — sword block + consumables (1.8.9).
// Copy to ~/.config/gnuclient/scripts/ and Reload Scripts.
//
// Grim mode: C07 RELEASE_USE_ITEM (+ optional slot flick) for server/Grim bypass,
// plus itemUseSlowTarget() for full client movement (Raven-style).
// Vanilla mode: itemUseSlowTarget() only — do NOT scale motion (compounds every tick).

void onLoad() {
    modules.registerButton("Grim mode", true);
    modules.registerSlider("Slow %", 0f, 0f, 80f);
    modules.registerButton("Sword", true);
    modules.registerButton("Consumables", true);
    modules.registerButton("Grim consumables", false);
    modules.registerButton("Bow", false);
    modules.registerButton("Slot flick", true);
    modules.registerButton("Sprint", true);
}

boolean affectsHeldItem() {
    if (status.isHoldingSword() && modules.getButton("Sword"))
        return true;
    boolean grim = modules.getButton("Grim mode");
    if ((grim ? modules.getButton("Grim consumables") : modules.getButton("Consumables"))
            && status.isHoldingConsumable())
        return true;
    if (status.isHoldingBow() && modules.getButton("Bow"))
        return true;
    return false;
}

float itemUseSlowTarget() {
    if (!status.isUsingItem() || client.isRiding() || !affectsHeldItem())
        return -1f;
    if (modules.getButton("Grim mode"))
        return 1.0f;
    float pct = modules.getSlider("Slow %");
    if (pct < 0f) pct = 0f;
    if (pct > 80f) pct = 80f;
    return (100f - pct) / 100f;
}

void onPreUpdate() {
    if (!status.isUsingItem() || client.isRiding() || !affectsHeldItem())
        return;

    if (modules.getButton("Grim mode")) {
        if (modules.getButton("Slot flick"))
            client.heldItemChangeFlicker();
        client.releaseUseItem();
    }

    if (modules.getButton("Sprint") && keybinds.isForwardDown())
        client.setSprintKey(true);
}

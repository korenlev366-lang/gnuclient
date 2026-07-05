// Grim Velocity — knockback reduction for Grim 1.8.9.
//
// Grim tracks S12 in transaction sandwiches (AntiKB). Cancel/zero/inflate S12 → AntiKB.
// Scaling client motion without matching server prediction → Simulation.
//
// Mode 0 Jump Reset (Grim-safe): jump at hurtTime 9 while sprinting on ground.
// Mode 1 Intave: attack-while-hurt motion scale + jump reset — often flags Simulation.
// Mode 2 Reduce: tick motion scale while hurt — flags Simulation on Grim.
// Mode 3 AttackSlow (Grim-safe): sprint + C02 attacks during hurt window — Grim models
//   0.6x horizontal per attack in PredictionEngine.addAttackSlowToPossibilities.
//   On 1.8 without KB sword: 1 slow per tick (re-sprint each tick). With KB sword: burst
//   multiple attacks same tick for stacked 0.6x (up to Grim's cap of 5).
//
// Do NOT delay/hold S12 (KnockbackDelay) — Grim force-resyncs ignored KB.

int HURT_TICK = 9;
int MIN_MELEE_HORIZ = 80;
long EXPLOSION_WINDOW_MS = 600L;

boolean isFallDamage = false;
boolean hasHitKnockback = false;
float lastHorizontalKb = 0f;
int ticksSinceKnockback = 0;
long lastExplosionMs = 0L;
long lastAttackMs = 0L;

void onLoad() {
    modules.registerSlider("Mode", 3f, 0f, 3f);
    modules.registerSlider("Chance %", 100f, 0f, 100f);
    modules.registerSlider("Min horiz KB", 0f, 0f, 10f);
    modules.registerSlider("Tick delay", 0f, 0f, 20f);
    modules.registerButton("Sprint only", false);
    modules.registerButton("Disable in water", false);
    modules.registerButton("Disable on vehicle", true);
    modules.registerSlider("Horiz %", 0f, 0f, 100f);
    modules.registerSlider("Vert %", 0f, 0f, 100f);
    modules.registerSlider("Intave factor", 0.6f, 0f, 1f);
    modules.registerSlider("Intave hurt min", 8f, 1f, 10f);
    modules.registerSlider("Intave hurt max", 9f, 1f, 10f);
    modules.registerSlider("Intave cooldown ms", 2000f, 0f, 10000f);
    modules.registerSlider("Attack range", 4f, 2.5f, 6f);
    modules.registerSlider("Burst clicks", 1f, 1f, 5f);
    modules.registerButton("Re-sprint", true);
    modules.registerButton("+ Jump reset", true);
    modules.registerButton("Release block", true);
}

float activeMode() {
    if (modules.getButton("Disable on vehicle") && client.isRiding())
        return -1f;
    return modules.getSlider("Mode");
}

boolean isFallVelocity(int mx, int my, int mz) {
    return mx == 0 && mz == 0 && my < 0;
}

boolean isMeleeHorizontal(int mx, int mz) {
    return Math.abs(mx) >= MIN_MELEE_HORIZ || Math.abs(mz) >= MIN_MELEE_HORIZ;
}

boolean isMeleeKnockback(int mx, int my, int mz) {
    if (isFallVelocity(mx, my, mz))
        return false;
    return isMeleeHorizontal(mx, mz);
}

boolean recentExplosion() {
    return lastExplosionMs > 0L && (client.time() - lastExplosionMs) < EXPLOSION_WINDOW_MS;
}

float horizontalKbMag(int mx, int mz) {
    double hx = mx / 8000.0;
    double hz = mz / 8000.0;
    return (float) Math.sqrt(hx * hx + hz * hz);
}

void resetKbState() {
    isFallDamage = false;
    hasHitKnockback = false;
    lastHorizontalKb = 0f;
    ticksSinceKnockback = 0;
}

void noteKnockback(int mx, int my, int mz) {
    isFallDamage = isFallVelocity(mx, my, mz);
    lastHorizontalKb = horizontalKbMag(mx, mz);
    if (isMeleeKnockback(mx, my, mz) && !recentExplosion()) {
        hasHitKnockback = true;
        ticksSinceKnockback = 0;
    }
}

boolean passesChance() {
    float c = modules.getSlider("Chance %");
    if (c >= 100f)
        return true;
    return util.randomInt(0, 99) <= (int) c;
}

boolean passesHorizontalGate() {
    float minKb = modules.getSlider("Min horiz KB");
    if (minKb <= 0.001f)
        return lastHorizontalKb > 0.001f;
    return lastHorizontalKb >= minKb;
}

boolean passesSprintGate() {
    if (!modules.getButton("Sprint only"))
        return true;
    return client.isSprinting();
}

boolean passesTickDelay() {
    return ticksSinceKnockback >= (int) modules.getSlider("Tick delay");
}

boolean wantsJumpReset() {
    float mode = activeMode();
    if (mode == 0f || mode == 1f)
        return true;
    if (mode == 3f && modules.getButton("+ Jump reset"))
        return true;
    return false;
}

boolean shouldJumpReset() {
    float mode = activeMode();
    if (mode < 0f || !wantsJumpReset())
        return false;
    if (modules.getButton("Disable in water") && status.isInWater())
        return false;
    if (status.getHurtTime() != HURT_TICK)
        return false;
    if (!client.isOnGround())
        return false;
    if (!passesSprintGate())
        return false;
    if (isFallDamage)
        return false;
    if (!hasHitKnockback)
        return false;
    if (!passesHorizontalGate())
        return false;
    if (!passesTickDelay())
        return false;
    return passesChance();
}

void patchMovementInput(Object movInput) {
    if (activeMode() < 0f)
        return;
    if (!shouldJumpReset())
        return;

    float forward = 0f;
    if (keybinds.isForwardDown()) forward += 1f;
    if (keybinds.isBackDown()) forward -= 1f;
    float strafe = 0f;
    if (keybinds.isLeftDown()) strafe += 1f;
    if (keybinds.isRightDown()) strafe -= 1f;

    client.setMovementInput(movInput, forward, strafe, true);

    if (activeMode() != 3f) {
        hasHitKnockback = false;
        ticksSinceKnockback = 0;
    }
}

void applyReduce() {
    if (activeMode() != 2f)
        return;
    if (status.getHurtTime() <= 0)
        return;
    if (!passesChance())
        return;

    float h = modules.getSlider("Horiz %") / 100f;
    float v = modules.getSlider("Vert %") / 100f;
    client.setMotion(
        client.getMotionX() * h,
        client.getMotionY() * v,
        client.getMotionZ() * h);
}

void applyAttackSlow() {
    if (activeMode() != 3f)
        return;
    if (!hasHitKnockback || status.getHurtTime() <= 0)
        return;
    if (modules.getButton("Disable in water") && status.isInWater())
        return;
    if (!passesHorizontalGate())
        return;
    if (!passesChance())
        return;

    double range = modules.getSlider("Attack range");
    Object target = world.getNearestPlayer(range);
    if (target == null)
        return;

    if (modules.getButton("Release block") && status.isBlocking())
        client.releaseUseItem();

    if (modules.getButton("Re-sprint") && !client.isServerSprinting())
        client.sendSprintStart();

    int burst = (int) modules.getSlider("Burst clicks");
    if (burst < 1) burst = 1;
    if (burst > 5) burst = 5;

    for (int i = 0; i < burst; i++) {
        if (!client.attackEntity(target))
            break;
        if (modules.getButton("Re-sprint") && i + 1 < burst && !client.isServerSprinting())
            client.sendSprintStart();
    }
}

boolean onPacketReceive(Object packet) {
    float mode = activeMode();
    if (mode < 0f)
        return false;

    if (packets.isExplosion(packet)) {
        lastExplosionMs = client.time();
        return false;
    }

    if (!packets.isSelfVelocity(packet))
        return false;

    noteKnockback(
        packets.velocityMotionX(packet),
        packets.velocityMotionY(packet),
        packets.velocityMotionZ(packet));
    return false;
}

boolean onPacketSend(Object packet) {
    float mode = activeMode();
    if (mode != 1f)
        return false;
    if (!packets.isAttack(packet))
        return false;

    int hurt = status.getHurtTime();
    float hurtMin = modules.getSlider("Intave hurt min");
    float hurtMax = modules.getSlider("Intave hurt max");
    long cooldown = (long) modules.getSlider("Intave cooldown ms");
    long now = client.time();

    if (hurt >= (int) hurtMin && hurt <= (int) hurtMax
            && (now - lastAttackMs) <= cooldown) {
        float factor = modules.getSlider("Intave factor");
        client.setMotion(
            client.getMotionX() * factor,
            client.getMotionY(),
            client.getMotionZ() * factor);
    }
    lastAttackMs = now;
    return false;
}

void onPreUpdate() {
    if (hasHitKnockback)
        ticksSinceKnockback++;
    if (status.getHurtTime() <= 0)
        hasHitKnockback = false;

    applyReduce();
    applyAttackSlow();
}

void onDisable() {
    resetKbState();
    lastExplosionMs = 0L;
    lastAttackMs = 0L;
}

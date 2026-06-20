package gnu.client.module.modules.combat;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.OutboundLagQueue;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketUtil;
import gnu.client.common.GnuLog;
import java.util.function.Consumer;

/**
 * Auto Block — holds right-click (use item) to block with a sword when a
 * valid target is in range while LMB is held. Ported from raven-bS
 * {@code Autoblock}.
 *
 * <p>Blocking uses keybind approach with {@link McAccess#pressUseItemKeyOnce}:
 * both {@link McAccess#setUseItemKeyState}(true) and
 * {@code pressUseItemKeyOnce()} are called. The latter is required because
 * the game's {@code rightClickMouse()} path checks {@code isPressed()},
 * which requires {@code pressTime > 0} from {@code onTick}. Without it,
 * C08 is never sent and Grim flags Post(player digging) from stale C07
 * RELEASE_USE_ITEM packets.
 *
 * <p>Lag (Slinky-style): When hold time expires, the lag starts FIRST
 * (matching raven-bS order), then the sword unblocks on the client (blink).
 * During the lag window, C08s and C07s are buffered — the natural C07 from
 * {@code stopUsingItem()} (fired when the key is released while
 * isUsingItem=true) is stored in the outbound queue. When a C02 attack
 * reaches the server during lag:
 * <ol>
 *   <li>A synthetic C07 RELEASE_USE_ITEM is sent to clear the server's
 *       isUsingItem (preventing Grim MultiActionsA)</li>
 *   <li>Lag is released, draining the buffered natural C07, C03 movements,
 *       and any other queued packets — all BEFORE the C02 reaches the server</li>
 *   <li>The module re-blocks immediately (if {@code blockAgainImmediately}
 *       is enabled) via key state manipulation; the game's natural
 *       {@code blockHitTimer} prevents C08 from being sent in the same tick
 *       as C02 (deferred 5 ticks)</li>
 * </ol>
 * This closely mirrors raven-bS's approach: {@code onSendPacket} with
 * HIGH priority releases the global {@code LagRequest} on C02, draining the
 * buffered C07 before C02 reaches the server.
 *
 * <p>Anti-MultiActions: When C02 attack fires (during lag or not), the module
 * sends C07 RELEASE_USE_ITEM via {@link McAccess#sendReleaseUseItem} before
 * letting C02 pass through. This clears the server-side isUsingItem state,
 * preventing Grim MultiActionsA (attack_while_using) and MultiActionsE
 * (swing_while_using) flags. The C07 is only sent on actual attack packets,
 * not on every tick — avoiding C07 spam.
 *
 * <p>C0A swing animations are dropped ONLY during lag (to prevent MultiActionsE
 * from a swing arriving while the server has isUsingItem=true). Outside lag,
 * C0A passes through normally, matching raven-bS behavior.
 *
 * <p>The module also cancels right-click mouse events via
 * {@link gnu.client.runtime.ClientEventListener} to prevent vanilla
 * interaction from interfering with autoblock state.
 */
public final class AutoBlockModule extends Module implements gnu.client.runtime.packet.PacketListener {

    // ── Settings ──────────────────────────────────────────────────────────

    private final SliderSetting range = addSetting(
            new SliderSetting("Range", 4.0f, 2.0f, 6.0f));
    private final SliderSetting maxHurtTimeMs = addSetting(
            new SliderSetting("Max Hurt Time", 200.0f, 50.0f, 500.0f));
    private final SliderSetting maxHoldMs = addSetting(
            new SliderSetting("Max Hold Time", 150.0f, 50.0f, 500.0f));

    private final BoolSetting requireLmb = addSetting(
            new BoolSetting("Require LMB", true));
    private final BoolSetting requireRmb = addSetting(
            new BoolSetting("Require RMB", false));
    private final BoolSetting onlyWhenDamaged = addSetting(
            new BoolSetting("Only when damaged", false));
    private final BoolSetting ignoreTeammates = addSetting(
            new BoolSetting("Ignore teammates", true));

    private final SliderSetting lagChance = addSetting(
            new SliderSetting("Lag Chance", 100.0f, 0.0f, 100.0f));
    private final SliderSetting lagMaxDuration = addSetting(
            new SliderSetting("Lag Max Duration", 200.0f, 50.0f, 500.0f));
    private final BoolSetting preventDelayAttacks = addSetting(
            new BoolSetting("Prevent delaying attacks", true));
    private final BoolSetting blockAgainImmediately = addSetting(
            new BoolSetting("Block again immediately", true));
    private final BoolSetting holdThroughLag = addSetting(
            new BoolSetting("Hold Through Lag", false));
    private final BoolSetting forceBlockAnimation = addSetting(
            new BoolSetting("Force block animation", true));

    // ── State ─────────────────────────────────────────────────────────────

    private boolean isBlocking;
    private boolean manualBlock;
    private int blockStartTick = -1;
    private int lastSelfHurtTime;
    // Current target (nearest valid player in range)
    private Object currentTarget;

    // Lag state (OutboundLagQueue-based: buffers non-attack packets during lag window).
    private final OutboundLagQueue outbound = new OutboundLagQueue();
    private final Consumer<Object> releaseHeldPacket = PacketUtil::sendPacketReleased;
    private boolean isLagging;
    private int lagStartTick = -1;
    // Tick counter (monotonic, wraps safely)
    private int tickCounter;
    // Set true when an attack is registered in this tick (noteAttack).
    private boolean attackedThisTick = false;
    // Snapshot of attackedThisTick from the previous tick (captured before reset).
    private boolean attackedLastTick;
    // Tick counter value when the most recent real attack was noted via noteAttack.
    private int lastAttackTick = -1;

    // ── Construction ──────────────────────────────────────────────────────

    public AutoBlockModule() {
        super("Auto Block", "Holds right-click to block with a sword when a target is in range",
                Category.COMBAT);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int msToTicks(float ms) {
        if (ms <= 0.0f) return 0;
        return (int) Math.ceil(ms / 50.0);
    }

    // ── Module lifecycle ──────────────────────────────────────────────────

    @Override
    public void onEnable() {
        tickCounter = 0;
        resetState(false);
        gnu.client.runtime.packet.PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        gnu.client.runtime.packet.PacketEvents.unregister(this);
        resetState(true);
    }

    // ── Tick (Module.onTickStart, dispatched from ModuleManager) ─────────
    //
    // onTickStart fires BEFORE clickMouse() and sendClickBlockToController
    // in the same game tick.  We use it to evaluate conditions and manage
    // block/lag state before the game processes input.

    @Override
    public void onTickStart() {
        tickCounter++;
        int currentTick = tickCounter;
        // Snapshot previous-tick value before resetting for this tick.
        attackedLastTick = attackedThisTick;
        GnuLog.log("[AB] ENTER tick=" + currentTick);
        attackedThisTick = false;

        Object player = McAccess.thePlayer();
        Object world = McAccess.theWorld();
        if (player == null || world == null || McAccess.currentScreen() != null) {
            GnuLog.log("[AB] BAIL pwS player=" + (player!=null) + " world=" + (world!=null) + " screen=" + (McAccess.currentScreen()!=null));
            resetState(true);
            return;
        }

        int selfHurtTime = McAccess.getInt(player, "field_70737_aN");
        boolean hurtAgain = selfHurtTime > lastSelfHurtTime;
        lastSelfHurtTime = selfHurtTime;

        if (!isHoldingSword()) {
            GnuLog.log("[AB] BAIL noSword");
            resetState(false);
            return;
        }

        // ── Target finding ────────────────────────────────────────────────
        // Match raven-bS CombatTargeting: mouse-over target first, then closest
        currentTarget = findTarget(player, world);

        // ── Input state ───────────────────────────────────────────────────
        // Raven-bS: both KillAura and AutoClicker count as LMB held
        boolean clickSourceActive = isClickSourceActive();
        boolean rmbDown = McAccess.isPhysicalRmbDown();
        boolean lmbDown = McAccess.isPhysicalLmbDown() || clickSourceActive;

        // ── Raven-bS flow ─────────────────────────────────────────────────
        if (!rmbDown && requireRmb.getValue()) {
            resetState(true);
            return;
        }

        if (!lmbDown) {
            if (!rmbDown) {
                resetState(true);
                return;
            }
            // Manual block (RMB only, no LMB)
            if (isLagging) releaseLag();
            if (!isBlocking) {
                startBlocking(currentTick);
                manualBlock = true;
            }
            return;
        }

        if (manualBlock) {
            stopBlocking(true);
            manualBlock = false;
        }

        boolean hasTarget = currentTarget != null;
        boolean conditionsMet = hasTarget && checkConditions(lmbDown, rmbDown);

        // ── Lag management ────────────────────────────────────────────────
        if (isLagging) {
            int lagMaxTicks = msToTicks(lagMaxDuration.getValue());
            boolean lagExpired = lagMaxTicks > 0 && lagStartTick >= 0
                    && currentTick - lagStartTick >= lagMaxTicks;

            if (lagExpired || !conditionsMet) {
                releaseLag();
                // Raven-bS: after lag expiry, start new block cycle immediately
                if (lagExpired && blockAgainImmediately.getValue() && conditionsMet) {
                    startBlocking(currentTick);
                }
            }
        }

        if (!conditionsMet) {
            GnuLog.log("[AB] BAIL condFalse hasTarget=" + (currentTarget!=null) + " click=" + isClickSourceActive());
            stopBlocking(true);
            return;
        }

        // ── Start blocking ────────────────────────────────────────────────
        // Start blocking when conditions are met and not already blocking/lagging.
        if (!isBlocking && !isLagging) {
            boolean shouldStart;
            if (onlyWhenDamaged.getValue()) {
                shouldStart = shouldPredictiveBlock();
            } else {
                // Proactive block: ensure C08 is sent before any C02 in this tick,
                // preventing Grim PacketOrderB (pre-attack) flags.
                shouldStart = true;
            }
            if (shouldStart) {
                startBlocking(currentTick);
            }
        }

        // ── [DIAG] Gate-state snapshot ──────────────────────────────────────
        GnuLog.log("[AB] tick=" + currentTick
                + " isBlocking=" + isBlocking
                + " isLagging=" + isLagging
                + " blockStartTick=" + blockStartTick
                + " lagStartTick=" + lagStartTick
                + " conditionsMet=" + conditionsMet
                + " hasTarget=" + (currentTarget != null)
                + " clickSrc=" + isClickSourceActive()
                + " shouldStartLagGate=" + (isBlocking && !isLagging)
                + " atk=" + attackedLastTick
                + " atkAge=" + (tickCounter - lastAttackTick));

        // ── Hold-time expiry -> lag (Raven-bS order: startLag THEN stopBlocking)
        if (isBlocking) {
            int maxHoldTicks = msToTicks(maxHoldMs.getValue());
            boolean timeExpired = maxHoldTicks > 0 && blockStartTick >= 0
                    && currentTick - blockStartTick >= maxHoldTicks;
            boolean shouldStop = timeExpired;
            if (onlyWhenDamaged.getValue() && hurtAgain) {
                shouldStop = true;
            }
            if (shouldStop) {
                /* Step 1 (Raven-bS order): start lag first — the blink */
                boolean lagGate = shouldStartLag();
                GnuLog.log("[AB] holdExpiry entered=" + shouldStop
                        + " willCallStartLag=" + lagGate
                        + " tick=" + currentTick
                        + " isBlocking=" + isBlocking
                        + " isLagging=" + isLagging
                        + " blockStartTick=" + blockStartTick
                        + " lagStartTick=" + lagStartTick
                        + " timeExpired=" + timeExpired
                        + " hurtAgain=" + hurtAgain
                        + " onlyWhenDamaged=" + onlyWhenDamaged.getValue());
                if (lagGate) {
                    startLag(currentTick);
                }
                /* Step 2: then stop blocking — key state released */
                stopBlocking(true);
            }
        }

    }

    // ── Force block animation at ClientTickEvent.END ────────────────────
    //
    // OnTick fires AFTER keybind processing in runTick() (ClientTickEvent.END),
    // at which point the game has either processed rightClickMouse() or not.
    // If the game state is already correct, this is a no-op (the fields are
    // already set). If the game state got out of sync (e.g., clearItemInUse
    // cleared activeItemStack), this restores it between runTick() and
    // renderWorld(), so the hand renders as blocking on the current frame.
    //
    // Uses McAccess.setItemInUse() which mirrors Raven's mixin-based approach:
    // Raven redirects getItemInUseCount() during ItemRenderer.renderItemInFirstPerson
    // via @Redirect — purely visual. Since we can't add a mixin trivially, we
    // set the player's fields at tick-end instead. This is logically equivalent
    // and has the same visual effect without the one-frame delay of onOverlay.

    @Override
    public void onTick() {
        if (!forceBlockAnimation.getValue() || !isHoldingSword()) return;
        // Mirror Raven's onRenderTick: setItemInUse(isBlocking || isLagging)
        // This ensures the block animation is forced when we're blocking/lagging
        // AND properly cleared when we're not (matching vanilla game state).
        McAccess.setItemInUse(McAccess.thePlayer(), isBlocking || isLagging);
    }

    // ── PacketListener ──────────────────────────────────────────────────

    @Override
    public boolean onSend(Object packet) {
        if (packet == null || PacketUtil.isDispatching()) return false;
        if (!preventDelayAttacks.getValue()) return false;

        // ── During lag ──────────────────────────────────────────────────
        if (isLagging) {
            // C02 attack during lag: behavior depends on holdThroughLag toggle.
            if (PacketHelper.isAttackUseEntity(packet)) {
                if (holdThroughLag.getValue()) {
                    // Hold Through Lag: buffer C02 instead of force-flushing.
                    // Still send synthetic C07 RELEASE_USE_ITEM immediately to
                    // clear server-side isUsingItem (preventing MultiActionsA).
                    // The attack stays queued with other held packets and will
                    // release on the normal duration-expiry flush (drainAll).
                    // isLagging stays true; lag continues to expiry as usual.
                    McAccess.sendReleaseUseItem(McAccess.thePlayer());
                    outbound.offer(packet);
                    return true;   // cancel send — C02 is now buffered
                }

                // ── OFF (default): current raven-bS behavior unchanged ──
                // Bit 1: Send synthetic C07 RELEASE_USE_ITEM to clear the
                // server's isUsingItem.  This prevents Grim MultiActionsA
                // (attack_while_using) which would otherwise fire because
                // the server still has isUsingItem=true from the pre-lag C08.
                McAccess.sendReleaseUseItem(McAccess.thePlayer());

                // Bit 2: Release the lag window — deactivates the outbound
                // queue and drains all buffered packets (including the natural
                // C07 from stopUsingItem, C03 movements, etc.) in FIFO order.
                // The drain sends packets via PacketUtil.sendPacketReleased,
                // which bypasses our PacketEvents listeners via SEND_FAST_TRACK.
                // These drained packets reach the server BEFORE the C02 below:
                //   C07 (natural, buffered) → C03 → ... → C07 (synthetic) → C02
                // Only the first C07 matters — it clears isUsingItem. The
                // synthetic C07 above is a no-op on the server (isUsingItem
                // already false from the natural one).
                releaseLag();

                // Bit 3: Reblock immediately if blockAgainImmediately is on.
                // This matches raven-bS onSendPacket behavior:
                //   releaseLag() + startBlocking(tickCounter)
                // The game's blockHitTimer (set to 5 by attackEntity())
                // prevents C08 from being sent in the same tick as C02,
                // so the reblock is naturally deferred by the game itself.
                if (blockAgainImmediately.getValue() && isHoldingSword() && McAccess.currentScreen() == null) {
                    startBlocking(tickCounter);
                }

                // Let C02 pass through unmodified
                return false;
            }

            // C0A swing during lag: drop it — a swing arriving at the server
            // while isUsingItem=true flags Grim MultiActionsE
            // (swing_while_using).  The synthetic C07 we send on C02 attack
            // clears isUsingItem first, so the C02 is clean.  But a C0A
            // arriving without a preceding C07 would flag.
            if (PacketHelper.isAnimationPacket(packet)) {
                return true;
            }

            // C08 (use item / block placement) during lag: buffer it.
            // The natural C08 from re-blocking attempts should not reach the
            // server during the lag window — it would reset isUsingItem and
            // reset the 72000-tick timeout unnecessarily.  Buffering ensures
            // it drains after lag ends.
            if (PacketHelper.isSendUseItem(packet)) {
                outbound.offer(packet);
                return true;
            }

            // C07 RELEASE_USE_ITEM during lag: buffer it (not drop!).
            // The game naturally generates C07 from stopUsingItem() when the
            // use-item key is released while isUsingItem=true.  By buffering
            // this C07 instead of dropping it, we ensure that when lag is
            // released (on C02 attack or expiry), the C07 reaches the server
            // BEFORE C02 — matching raven-bS's global LagRequest behavior.
            // If we dropped C07 here, the server would never receive it, and
            // C02 would arrive with isUsingItem=true → MultiActionsA flag.
            if (PacketHelper.isReleaseUseItem(packet)) {
                outbound.offer(packet);
                return true;
            }

            // Keepalive/transaction/chat: pass through immediately (prevent
            // timeout/desync).
            if (PacketHelper.isKeepAlive(packet)
                    || PacketHelper.isClientConfirmTransaction(packet)
                    || PacketHelper.isChat(packet)) {
                return false;
            }

            // Full outbound blink: buffer everything else (C03 movement,
            // C0B entity action, etc.).  Released in FIFO order when lag
            // ends, so drained C07→C03→...→C02 ordering is preserved.
            outbound.offer(packet);
            return true;
        }

        // ── Not lagging ──────────────────────────────────────────────────

        // C0A swing animation: pass through (raven-bS does not drop C0A).
        // During normal blocking (non-lag), the server's isUsingItem will
        // be true, but C0A with isUsingItem=true is normal vanilla behavior
        // (you can swing your arm while blocking).  Grim MultiActionsE only
        // flags when C0A arrives with isUsingItem=true AND there was no C07
        // in the same tick — during lag, we drop C0A and send synthetic C07
        // before C02; outside lag, we let C0A through naturally.
        // Dropping C0A outside lag would cause Vulcan BadPackets (swung=false)
        // and visual desync (no arm swing animation).
        if (PacketHelper.isAnimationPacket(packet)) {
            return false;
        }

        // ── C02 attack (non-lagging) ────────────────────────────────────
        // Vanilla order for attacking while blocking:
        //   C08 (block start) → … → C02 (attack) → … → C07 (release when RMB lifted)
        //
        // We reblock (C08) before C02 passes so the server sees a fresh block
        // start.  No synthetic C07 is sent here — the server remains in
        // isUsingItem=true, which is correct for repeated attacks while
        // blocking (the player hasn't stopped blocking).  Sending C07 before
        // C02 causes Grim PacketOrderI (wrong interaction order).
        if (PacketHelper.isAttackUseEntity(packet)) {
            // Reblock immediately if blockAgainImmediately is on (raven-bS
            // behavior).  startBlocking() now sends C08 synchronously via
            // sendUseItem(), so the server sees the block start before the
            // attack arrives — preventing PacketOrderB (pre-attack).
            if (blockAgainImmediately.getValue() && isHoldingSword()
                    && McAccess.currentScreen() == null) {
                startBlocking(tickCounter);
            }

            return false;
        }

        return false;
    }

    @Override
    public boolean onReceive(Object packet) {
        // S08 server teleport/respawn/join: release lag (drain queue) but
        // keep isBlocking=true so blocking resumes immediately after teleport.
        // Raven does NOT reset blocking state on teleport -- the module
        // re-evaluates conditions naturally on the next tick.
        if (PacketHelper.isPlayerPosLook(packet)) {
            releaseLag();
            return false;
        }

        return false;
    }

    // ── Target finding ────────────────────────────────────────────────────

    private Object findTarget(Object player, Object world) {
        Class<?> playerCls = McAccess.gameClass("net.minecraft.entity.player.EntityPlayer");
        if (playerCls == null)
            return null;

        double rangeSq = range.getValue() * range.getValue();

        // 1. Check mouse-over target first (raven-bS priority)
        Object mop = McAccess.objectMouseOver();
        if (mop != null) {
            Object hitEntity = McAccess.invoke(mop, "func_78288_c", new Class<?>[0]); // entityHit
            if (hitEntity != null && playerCls.isInstance(hitEntity)
                    && isValidTarget(hitEntity, player, rangeSq)) {
                return hitEntity;
            }
        }

        // 2. Fall back to closest
        return findClosestTarget(player, world, playerCls, rangeSq);
    }

    private Object findClosestTarget(Object player, Object world,
                                     Class<?> playerCls, double rangeSq) {
        Object best = null;
        double bestDist = rangeSq;

        for (Object entity : McAccess.getWorldEntities(world)) {
            if (entity == null || !playerCls.isInstance(entity))
                continue;
            if (!isValidTarget(entity, player, rangeSq))
                continue;

            double dx = McAccess.entityPosX(entity) - McAccess.entityPosX(player);
            double dy = McAccess.entityPosY(entity) - McAccess.entityPosY(player);
            double dz = McAccess.entityPosZ(entity) - McAccess.entityPosZ(player);
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < bestDist) {
                bestDist = distSq;
                best = entity;
            }
        }

        return best;
    }

    private boolean isValidTarget(Object entity, Object player, double rangeSq) {
        if (entity == player)
            return false;
        if (McAccess.getBool(entity, "field_70128_L"))
            return false; // isDead
        if (ignoreTeammates.getValue() && isTeammate(entity))
            return false;

        double dx = McAccess.entityPosX(entity) - McAccess.entityPosX(player);
        double dy = McAccess.entityPosY(entity) - McAccess.entityPosY(player);
        double dz = McAccess.entityPosZ(entity) - McAccess.entityPosZ(player);
        return dx * dx + dy * dy + dz * dz <= rangeSq;
    }

    // ── Condition checks ──────────────────────────────────────────────────

    private boolean checkConditions(boolean lmbDown, boolean rmbDown) {
        if (requireLmb.getValue() && !lmbDown) return false;
        if (requireRmb.getValue() && !rmbDown) return false;
        return true;
    }

    private boolean shouldPredictiveBlock() {
        Object player = McAccess.thePlayer();
        if (player == null) return false;
        int selfHurtTime = McAccess.getInt(player, "field_70737_aN");
        int triggerTick = (int) Math.round(maxHurtTimeMs.getValue() / 50.0);
        triggerTick = Math.max(1, Math.min(10, triggerTick));
        return selfHurtTime == triggerTick;
    }

    private static boolean isClickSourceActive() {
        Module ac = ModuleManager.INSTANCE.getModule("AutoClicker");
        return ac instanceof AutoClickerModule && ac.isEnabled();
    }

    // ── Blocking control ──────────────────────────────────────────────────

    private void startBlocking(int currentTick) {
        if (!isHoldingSword()) return;
        // Raven-bS: skip clearItemInUse — clearing the player's activeItemStack
        // creates a window where isUsingItem()=false despite the module tracking
        // isBlocking=true. The game re-establishes state later in the tick via
        // rightClickMouse(), but that's fragile. clearBlockHitTimer zeros the
        // 5-tick blockHitDelay so sendUseItem won't silently drop C08 after attack.
        McAccess.clearBlockHitTimer();
        // Clear client-side item-in-use state so sendUseItem() sends C08
        // instead of toggling to C07. Without this, C08 is never sent
        // (pressUseItemKeyOnce only increments KeyBinding.pressTime; it does
        // NOT call rightClickMouse → sendUseItem).  The resulting C02-before-C08
        // ordering causes Grim PacketOrderB (pre-attack).
        McAccess.clearItemInUse(McAccess.thePlayer());
        McAccess.setUseItemKeyState(true);
        McAccess.pressUseItemKeyOnce();
        // Immediately send C08 so it reaches the server before any C02
        // attack sent later in this same tick.
        McAccess.sendUseItem();
        isBlocking = true;
        blockStartTick = currentTick;
    }

    private void stopBlocking(boolean forceRelease) {
        if (!isBlocking && !forceRelease) return;
        McAccess.setUseItemKeyState(false);
        isBlocking = false;
        blockStartTick = -1;
    }

    // ── Lag control ───────────────────────────────────────────────────────

    private boolean shouldStartLag() {
        double chance = lagChance.getValue();
        if (chance <= 0.0) return false;
        if (chance >= 100.0) return true;
        return Math.random() * 100.0 < chance;
    }

    private void startLag(int currentTick) {
        if (isLagging) return;
        outbound.activate();
        isLagging = true;
        lagStartTick = currentTick;
    }

    private void releaseLag() {
        if (!isLagging) return;
        outbound.deactivate();
        outbound.drainAll(releaseHeldPacket);
        isLagging = false;
        lagStartTick = -1;
    }

    // ── Public query ──────────────────────────────────────────────────────

    /** Checked by WTapModule / KillAura to suppress C0A swings during lag. */
    public boolean isLagging() {
        return isLagging;
    }

    /** Checked by KillAura / VanillaModuleDriver to know if blocking is active. */
    public boolean isActive() {
        return isEnabled() && (isBlocking || isLagging);
    }

    /** Called by VanillaModuleDriver.noteAttack to release lag on swing. */
    public void noteAttack(Object target) {
        if (!isEnabled() || target == null) return;
        this.attackedThisTick = true;
        this.lastAttackTick = tickCounter;
        if (!isLagging || !preventDelayAttacks.getValue()) return;
        releaseLag();
    }

    // ── State reset ───────────────────────────────────────────────────────

    private void resetState(boolean releaseUseKey) {
        releaseLag();
        stopBlocking(releaseUseKey);
        manualBlock = false;

        // Do NOT manually send C07 RELEASE_USE_ITEM — it puts Grim into
        // "releasing=true" state and cascades into false flagging. The correct
        // mechanism: just call setUseItemKeyState(false) in stopBlocking() and
        // leave itemInUse fields set. When the physical RMB is released
        // (isKeyDown()=false) while isUsingItem()=true, the vanilla
        // updateEntityActionState() fires stopUsingItem() which sends C07
        // naturally in the correct packet context, next tick.
        // Raven-bS: if RMB is still physically held, re-press the use-item key
        if (releaseUseKey && McAccess.isPhysicalRmbDown()
                && McAccess.currentScreen() == null && isHoldingSword()) {
            McAccess.setUseItemKeyState(true);
        }
        currentTarget = null;
        lastSelfHurtTime = 0;
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static boolean isHoldingSword() {
        Object player = McAccess.thePlayer();
        if (player == null) return false;
        Object stack = McAccess.invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null) return false;
        Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
        if (item == null) return false;
        Class<?> sword = McAccess.gameClass("net.minecraft.item.ItemSword");
        return sword != null && sword.isInstance(item);
    }

    private static boolean isTeammate(Object entity) {
        Object player = McAccess.thePlayer();
        if (player == null || entity == null) return false;
        try {
            Object team = McAccess.invoke(entity, "func_96124_cp", new Class<?>[0]);
            if (team != null) {
                Object ourTeam = McAccess.invoke(player, "func_96124_cp", new Class<?>[0]);
                if (ourTeam != null) {
                    Object teamName = McAccess.invoke(team, "func_96661_b", new Class<?>[0]);
                    Object ourName = McAccess.invoke(ourTeam, "func_96661_b", new Class<?>[0]);
                    return teamName != null && ourName != null && teamName.equals(ourName);
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}

package gnu.client.module.modules.network;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.LagrangeOutboundTrack;
import gnu.client.runtime.packet.LagrangeOutboundTrack.EnumLagDirection;
import gnu.client.runtime.packet.LagrangeOutboundTrack.LagRequest;
import gnu.client.runtime.packet.LagrangeOutboundTrack.Timeout;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;
import gnu.client.runtime.packet.PacketUtil;
import gnu.client.util.RenderHelper;

import java.util.EnumSet;

/**
 * Raven-bS {@code LagRange} — request-driven outbound lag (exact BiTrackNode parity).
 *
 * <p>Uses {@link LagrangeOutboundTrack} as a global {@code BiTrackLagNodeQueue}:
 * {@code startLag()} creates a {@link LagRequest}({@code ONLY_OUTBOUND}, {@link ModuleBackedTimeout})
 * and registers it with the track. While the request is active (not timed out), all outbound
 * packets are queued. {@code flushLag()} = {@code forceTimeOut()}, and the track's
 * {@code tick()} loop automatically releases queued packets on the next game tick.
 */
public final class LagrangeModule extends Module implements PacketListener {

    private static final double MINIMUM_DISTANCE_SQ = 3.0 * 3.0;
    private static final long INDICATOR_INTERP_MS = 80L;
    private static final double POS_EPS = 1.0e-6;

    private final SliderSetting range = addSetting(new SliderSetting("Range", 6.0f, 3.0f, 10.0f));
    private final SliderSetting maximumDelay = addSetting(new SliderSetting("Maximum delay", 200.0f, 50.0f, 1000.0f));
    private final BoolSetting sprintReset = addSetting(new BoolSetting("Sprint reset", true));
    private final BoolSetting blockSword = addSetting(new BoolSetting("Block sword", true));
    private final BoolSetting usedSplashPotion = addSetting(new BoolSetting("Used splash potion", true));
    private final BoolSetting holdingWeapon = addSetting(new BoolSetting("Holding weapon", true));
    private final BoolSetting realPositionIndicator = addSetting(new BoolSetting("Server ESP", true));
    private final BoolSetting showInFirstPerson = addSetting(new BoolSetting("Show in first person", false));
    private final SliderSetting espRed = addSetting(new SliderSetting("Red", 255.0f, 0.0f, 255.0f));
    private final SliderSetting espGreen = addSetting(new SliderSetting("Green", 0.0f, 0.0f, 255.0f));
    private final SliderSetting espBlue = addSetting(new SliderSetting("Blue", 0.0f, 0.0f, 255.0f));
    private final BoolSetting espFilled = addSetting(new BoolSetting("Filled", false));
    private final SliderSetting espLineWidth = addSetting(new SliderSetting("Line Width", 2.0f, 1.0f, 5.0f));

    /** Raven {@code UnifiedLagHandler}'s BiTrack queue — shared across all lag modules. */
    private static final LagrangeOutboundTrack BIDI_TRACK = new LagrangeOutboundTrack();

    /** Raven {@code LagRequest} for THIS module's outbound lag. Null when not lagging. */
    private LagRequest outboundLag;

    private Object currentTarget;
    private double lastDistSq = -1.0;
    private boolean isLagging;
    private int lastSelfHurtTime;
    private int lastTargetHurtTime;
    private int hitMarkedEntityId = -1;
    private boolean lastSprintState;
    private boolean lastBlockingState;

    private double serverPosX;
    private double serverPosY;
    private double serverPosZ;
    private boolean serverPosValid;

    private double indicatorFromX;
    private double indicatorFromY;
    private double indicatorFromZ;
    private double indicatorToX;
    private double indicatorToY;
    private double indicatorToZ;
    private boolean indicatorValid;
    private long indicatorInterpStartMs;

    public LagrangeModule() {
        super("Lagrange", "Raven Lag Range outbound delay", Category.COMBAT);
    }

    // ── Raven UnifiedLagHandler singleton access ───────────────────────

    /** Get the shared BiTrack queue (raven {@code Raven.lagHandler}). */
    public static LagrangeOutboundTrack getBiDiTrack() {
        return BIDI_TRACK;
    }

    /** Raven {@code Raven.lagHandler.releaseExpiredPackets(OUTBOUND, maxAgeMs)}. */
    public static void releaseExpiredOutbound(long maxAgeMs) {
        BIDI_TRACK.releaseExpiredPackets(EnumLagDirection.OUTBOUND, maxAgeMs, LagrangeModule::passThrough);
    }

    /** Raven {@code flushAll} for outbound — used by KnockbackDelay on S12. */
    public static void flushOutboundTrack() {
        BIDI_TRACK.drainAll(EnumLagDirection.OUTBOUND, LagrangeModule::passThrough);
    }

    /** Raven {@code addToSendQueue} path with fast-track (matches {@code PacketUtil.sendPacketReleased}). */
    private static void passThrough(Object packet) {
        if (packet == null) return;
        updateServerPositionFromPacketStatic(packet);
        PacketUtil.sendPacketReleased(packet);
    }

    /** Raven {@code UnifiedLagHandler.updateServerPosition} — static mirror of instance method. */
    private static void updateServerPositionFromPacketStatic(Object packet) {
        // Position tracking is per-module instance; static pass-through delegates to instance if needed
    }

    // ── Raven ModuleBackedTimeout ───────────────────────────────────────

    /**
     * Raven {@code ModuleBackedTimeout} — times out when module is disabled or
     * {@code forceTimeOut()} is called.
     */
    private final class ModuleBackedTimeout extends Timeout {
        @Override
        protected boolean shouldHaveTimedOut() {
            return !isEnabled();
        }
    }

    // ── Module lifecycle ────────────────────────────────────────────────

    @Override
    public void onEnable() {
        disableConflictingOutboundLag("Blink");
        resetState();
        PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        flushLag();
        if (outboundLag != null) {
            BIDI_TRACK.drainAll(EnumLagDirection.OUTBOUND, this::passThroughOutbound);
            outboundLag = null;
        }
        resetState();
        PacketEvents.unregister(this);
    }

    @Override
    public void onTickStart() {
        if (!isEnabled() || isSuspendedByBlink())
            return;
        // clickMouse fires BEFORE EntityPlayerSP.onUpdate in 1.8.9, so onTick
        // would fire AFTER the attack, causing PacketOrderB/Reach issues.
        // Run the track drain in onTickStart's finally block instead — releases
        // queued packets right after combat logic, BEFORE any C02/C03 is sent.
        try {
            tickCombatLogic();
        } finally {
            BIDI_TRACK.tick(this::passThroughOutbound, null);
        }
    }

    @Override
    public void onTick() {
        // no-op — track drain moved to onTickStart finally block
    }

    // ── Raven LagRange combat logic ─────────────────────────────────────

    private void tickCombatLogic() {
        Object mc = McAccess.getMinecraft();
        Object player = McAccess.thePlayer(mc);
        Object world = McAccess.theWorld(mc);
        if (player == null || world == null || McAccess.getBool(player, "field_70128_L")) {
            if (isLagging)
                flushLag();
            resetState();
            return;
        }
        if (McAccess.currentScreen(mc) != null) {
            if (isLagging)
                flushLag();
            resetState();
            return;
        }

        double rangeSq = range.getValue() * range.getValue();
        boolean moving = isMoving(player);
        Object nextTarget = findTarget(player, world, rangeSq);
        if (!sameTarget(nextTarget)) {
            if (isLagging)
                flushLag();
            lastDistSq = -1.0;
            hitMarkedEntityId = -1;
            lastTargetHurtTime = nextTarget != null ? McAccess.getInt(nextTarget, "field_70737_aN") : 0;
        }
        currentTarget = nextTarget;

        if (currentTarget != null) {
            double distSq = distanceSqFromEyeToClosestOnAabb(player, currentTarget);
            if (isLagging) {
                if (distSq > rangeSq) {
                    flushLag();
                    lastDistSq = distSq;
                    hitMarkedEntityId = -1;
                    lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                    return;
                }
                if (lastDistSq >= 0 && distSq >= lastDistSq) {
                    boolean hitHold = hitMarkedEntityId == McAccess.entityId(currentTarget)
                            && distSq <= MINIMUM_DISTANCE_SQ
                            && McAccess.getInt(player, "field_70737_aN") == 0;
                    if (!hitHold) {
                        flushLag();
                        lastDistSq = distSq;
                        lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                        return;
                    }
                }
                int hurtTime = McAccess.getInt(player, "field_70737_aN");
                if (hurtTime > lastSelfHurtTime) {
                    flushLag();
                    hitMarkedEntityId = -1;
                    lastSelfHurtTime = hurtTime;
                    lastDistSq = distSq;
                    lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                    return;
                }
                lastSelfHurtTime = hurtTime;

                // Raven {@code Raven.lagHandler.releaseExpiredPackets(OUTBOUND, maxDelay)}
                BIDI_TRACK.releaseExpiredPackets(
                        EnumLagDirection.OUTBOUND,
                        (long) maximumDelay.getValue().floatValue(),
                        this::passThroughOutbound);

                if (holdingWeapon.getValue() && !isHoldingWeapon(player)) {
                    flushLag();
                    lastDistSq = distSq;
                    lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                    return;
                }
                if (sprintReset.getValue()) {
                    boolean sprintingNow = isSprinting(player);
                    if (sprintingNow && !lastSprintState) {
                        flushLag();
                        lastSprintState = true;
                        lastDistSq = distSq;
                        lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                        return;
                    }
                    lastSprintState = sprintingNow;
                }
                if (blockSword.getValue()) {
                    boolean blockingNow = isBlocking(player);
                    if (blockingNow && !lastBlockingState) {
                        flushLag();
                        lastBlockingState = true;
                        lastDistSq = distSq;
                        lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                        return;
                    }
                    lastBlockingState = blockingNow;
                }
                if (usedSplashPotion.getValue() && isUsingSplashPotion(player)) {
                    flushLag();
                    lastDistSq = distSq;
                    lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                    return;
                }
                lastDistSq = distSq;
                lastTargetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
                return;
            }

            int hurtTime = McAccess.getInt(player, "field_70737_aN");
            if (hurtTime > lastSelfHurtTime)
                hitMarkedEntityId = -1;
            lastSelfHurtTime = hurtTime;
            lastSprintState = isSprinting(player);
            lastBlockingState = isBlocking(player);
            int targetHurtTime = McAccess.getInt(currentTarget, "field_70737_aN");
            if (hurtTime == 0 && lastTargetHurtTime == 0 && targetHurtTime > 0)
                hitMarkedEntityId = McAccess.entityId(currentTarget);
            lastTargetHurtTime = targetHurtTime;

            boolean closing = lastDistSq >= 0 && distSq < lastDistSq;
            boolean outsideMinDist = distSq > MINIMUM_DISTANCE_SQ;
            boolean weaponOk = !holdingWeapon.getValue() || isHoldingWeapon(player);
            boolean hitMarkedHere = hitMarkedEntityId == McAccess.entityId(currentTarget);
            boolean hitStart = hitMarkedHere && distSq <= MINIMUM_DISTANCE_SQ && hurtTime == 0 && moving && weaponOk;
            lastDistSq = distSq;
            if (hurtTime == 0 && weaponOk && moving && ((closing && outsideMinDist) || hitStart))
                startLag();
        } else {
            if (isLagging)
                flushLag();
            lastDistSq = -1.0;
            hitMarkedEntityId = -1;
            lastTargetHurtTime = 0;
        }
    }

    // ── Raven packet interception ───────────────────────────────────────

    @Override
    public boolean onSend(Object packet) {
        if (!isEnabled() || isSuspendedByBlink() || PacketUtil.isDispatching() || packet == null)
            return false;

        // Raven UnifiedLagHandler: ALL outbound packets go through queue.tick.
        // No isLagrangeSendExempt / isBlockIntercept bypass — those break FIFO ordering.
        // The track's short-circuit handles non-lagging state: if currentlyAwaiting is
        // null and track empty, the packet passes through without queueing.

        // Pre-attack flush before C02 reaches the track (Raven AttackEvent via noteForgeAttack).
        // forceTimeOut only — the C0A (from swingItem) or C02 arrives next and BIDI_TRACK.tick()
        // naturally releases queued stale C03s in FIFO order through the timed-out request loop.
        if (PacketHelper.isAttackUseEntity(packet)) {
            flushLag();
        }

        // Raven {@code UnifiedLagHandler.onSendPacket} → queue.tick(packet, OUTBOUND)
        boolean cancelled = BIDI_TRACK.tick(
                packet,
                EnumLagDirection.OUTBOUND,
                this::passThroughOutbound);

        if (!cancelled) {
            updateServerPositionFromPacket(packet);
        }
        return cancelled;
    }

    @Override
    public boolean onReceive(Object packet) {
        return false;
    }

    @Override
    public void onRender(float partialTicks) {
        if (!realPositionIndicator.getValue() || !isLagging || !serverPosValid || !McAccess.isInGame())
            return;
        Object mc = McAccess.getMinecraft();
        if (isFirstPersonView(mc) && !showInFirstPerson.getValue())
            return;

        long nowMs = System.currentTimeMillis();
        if (!indicatorValid) {
            indicatorFromX = serverPosX;
            indicatorFromY = serverPosY;
            indicatorFromZ = serverPosZ;
            indicatorToX = serverPosX;
            indicatorToY = serverPosY;
            indicatorToZ = serverPosZ;
            indicatorInterpStartMs = nowMs;
            indicatorValid = true;
        } else if (serverPosChanged(serverPosX, serverPosY, serverPosZ, indicatorToX, indicatorToY, indicatorToZ)) {
            double te = Math.min(1.0, (nowMs - indicatorInterpStartMs) / (double) INDICATOR_INTERP_MS);
            indicatorFromX = lerp(indicatorFromX, indicatorToX, te);
            indicatorFromY = lerp(indicatorFromY, indicatorToY, te);
            indicatorFromZ = lerp(indicatorFromZ, indicatorToZ, te);
            indicatorToX = serverPosX;
            indicatorToY = serverPosY;
            indicatorToZ = serverPosZ;
            indicatorInterpStartMs = nowMs;
        }

        double t = Math.min(1.0, (nowMs - indicatorInterpStartMs) / (double) INDICATOR_INTERP_MS);
        double drawX = lerp(indicatorFromX, indicatorToX, t);
        double drawY = lerp(indicatorFromY, indicatorToY, t);
        double drawZ = lerp(indicatorFromZ, indicatorToZ, t);

        double[] vp = McAccess.getViewerPos(mc, partialTicks);
        float fr = espRed.getValue() / 255.0f;
        float fg = espGreen.getValue() / 255.0f;
        float fb = espBlue.getValue() / 255.0f;
        float lw = espLineWidth.getValue();

        RenderHelper.begin();
        drawGhostBox(drawX - vp[0], drawY - vp[1], drawZ - vp[2], fr, fg, fb, lw);
        RenderHelper.end();
    }

    // ── Raven request lifecycle ─────────────────────────────────────────

    /**
     * Raven {@code LagRange.startLag()}:
     * Creates a new {@link LagRequest} and registers it with the handler.
     */
    private void startLag() {
        isLagging = true;
        outboundLag = new LagRequest(EnumSet.of(EnumLagDirection.OUTBOUND), new ModuleBackedTimeout());
        BIDI_TRACK.requestLag(outboundLag);
        clearIndicatorInterp();
        syncServerPosFromPlayer();
    }

    /**
     * Raven {@code LagRange.flushLag()}:
     * Forces the timeout on the active request. The track's tick loop will
     * automatically release all queued packets on the next game tick.
     */
    public void flushLag() {
        if (!isLagging)
            return;
        isLagging = false;
        if (outboundLag != null) {
            outboundLag.getTimeout().forceTimeOut();
            outboundLag = null;
        }
        clearIndicatorInterp();
    }

    /**
     * Flush + drain ALL remaining packets immediately (on disable / blink pause).
     */
    public void flushQueueNow() {
        flushLag();
        BIDI_TRACK.drainAll(EnumLagDirection.OUTBOUND, this::passThroughOutbound);
    }

    /**
     * Blink holds outbound packets; drain lag queue but keep Lagrange enabled for resume.
     */
    public void pauseForBlink() {
        flushLag();
        BIDI_TRACK.drainAll(EnumLagDirection.OUTBOUND, this::passThroughOutbound);
    }

    /**
     * Raven {@code AttackEvent}: pre-attack flush. Called from KillAura/other
     * modules before sending the attack packet.
     */
    public void noteForgeAttack(Object target) {
        if (isLagging) {
            // Raven AttackEvent: forceTimeOut only. The C0A/C02 packets that follow
            // go through onSend -> BIDI_TRACK.tick() which naturally releases queued
            // packets in FIFO order through the track's timed-out request loop.
            // No drainAll burst — that causes unnatural stale-C03 floods flagged by Grim.
            flushLag();
        }
    }

    private boolean isSuspendedByBlink() {
        Module blink = ModuleManager.INSTANCE.getModule("Blink");
        return blink != null && blink.isEnabled();
    }

    public void abortLagNow() {
        flushLag();
    }

    public boolean hasQueuedPackets() {
        return BIDI_TRACK.hasQueuedPackets();
    }

    // ── Packet release ──────────────────────────────────────────────────

    /**
     * Raven {@code passThroughChannel} — send packet with speed,
     * update server position. Matches raven {@code goThrough}.
     */
    private void passThroughOutbound(Object packet) {
        if (packet == null) return;
        updateServerPositionFromPacket(packet);
        PacketUtil.sendPacketReleased(packet);
    }

    // ── State management ────────────────────────────────────────────────

    private void resetState() {
        currentTarget = null;
        lastDistSq = -1.0;
        isLagging = false;
        lastSelfHurtTime = 0;
        lastTargetHurtTime = 0;
        hitMarkedEntityId = -1;
        lastSprintState = false;
        lastBlockingState = false;
        serverPosValid = false;
        outboundLag = null;
        clearIndicatorInterp();
    }

    // ── Server position tracking ────────────────────────────────────────

    /** Raven {@code UnifiedLagHandler.updateServerPosition} — every sent position C03. */
    private void updateServerPositionFromPacket(Object packet) {
        if (!PacketHelper.isPlayerMovement(packet) || !PacketHelper.c03HasPosition(packet))
            return;
        double x = PacketHelper.c03PosX(packet);
        double y = PacketHelper.c03PosY(packet);
        double z = PacketHelper.c03PosZ(packet);
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z))
            return;
        serverPosX = x;
        serverPosY = y;
        serverPosZ = z;
        serverPosValid = true;
    }

    private void syncServerPosFromPlayer() {
        Object player = McAccess.thePlayer(McAccess.getMinecraft());
        if (player == null)
            return;
        serverPosX = McAccess.getDouble(player, "field_70165_t");
        serverPosY = McAccess.getDouble(player, "field_70163_u");
        serverPosZ = McAccess.getDouble(player, "field_70161_v");
        serverPosValid = true;
    }

    // ── Indicator helpers ───────────────────────────────────────────────

    private static boolean isFirstPersonView(Object mc) {
        Object settings = McAccess.getObject(mc, "field_71474_y");
        if (settings == null)
            return true;
        return McAccess.getInt(settings, "field_74320_O") == 0;
    }

    private void clearIndicatorInterp() {
        indicatorValid = false;
    }

    private static boolean serverPosChanged(double ax, double ay, double az,
                                             double bx, double by, double bz) {
        return Math.abs(ax - bx) > POS_EPS || Math.abs(ay - by) > POS_EPS || Math.abs(az - bz) > POS_EPS;
    }

    private static double lerp(double from, double to, double t) {
        if (t <= 0.0) return from;
        if (t >= 1.0) return to;
        return from + (to - from) * t;
    }

    // ── Target helpers ──────────────────────────────────────────────────

    private Object findTarget(Object player, Object world, double maxRangeSq) {
        Object mop = McAccess.objectMouseOver();
        if (mop != null) {
            Object entityHit = McAccess.getObject(mop, "field_72308_g");
            if (entityHit != null && entityHit != player && isPlayerEntity(entityHit)) {
                double distSq = distanceSqFromEyeToClosestOnAabb(player, entityHit);
                if (distSq <= maxRangeSq && McAccess.getInt(entityHit, "field_70725_aQ") <= 0)
                    return entityHit;
            }
        }

        Object entitiesObj = McAccess.getObject(world, "field_73010_i");
        if (!(entitiesObj instanceof Iterable))
            return null;
        Object best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Object entity : (Iterable<?>) entitiesObj) {
            if (entity == null || entity == player || !isPlayerEntity(entity))
                continue;
            if (McAccess.getInt(entity, "field_70725_aQ") > 0)
                continue;
            double distSq = distanceSqFromEyeToClosestOnAabb(player, entity);
            if (distSq > maxRangeSq || distSq >= bestDistSq)
                continue;
            best = entity;
            bestDistSq = distSq;
        }
        return best;
    }

    private boolean sameTarget(Object nextTarget) {
        if (currentTarget == null || nextTarget == null)
            return currentTarget == nextTarget;
        return McAccess.entityId(currentTarget) == McAccess.entityId(nextTarget);
    }

    private static double distanceSqFromEyeToClosestOnAabb(Object player, Object entity) {
        double px = McAccess.getDouble(player, "field_70165_t");
        double py = McAccess.getDouble(player, "field_70163_u") + McAccess.getFloat(player, "field_70131_O") * 0.85;
        double pz = McAccess.getDouble(player, "field_70161_v");

        double ex = McAccess.getDouble(entity, "field_70165_t");
        double ey = McAccess.getDouble(entity, "field_70163_u");
        double ez = McAccess.getDouble(entity, "field_70161_v");
        double halfW = McAccess.getFloat(entity, "field_70130_N") * 0.5;
        double h = McAccess.getFloat(entity, "field_70131_O");

        double cx = clamp(px, ex - halfW, ex + halfW);
        double cy = clamp(py, ey, ey + h);
        double cz = clamp(pz, ez - halfW, ez + halfW);
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean isPlayerEntity(Object entity) {
        if (entity == null) return false;
        Class<?> c = entity.getClass();
        while (c != null) {
            if (c.getName().contains("EntityPlayer")) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private boolean isMoving(Object player) {
        Object movementInput = McAccess.getObject(player, "field_71158_b");
        if (movementInput == null) return false;
        float forward = McAccess.getFloat(movementInput, "field_78902_a");
        float strafe = McAccess.getFloat(movementInput, "field_78900_b");
        return forward != 0.0f || strafe != 0.0f;
    }

    private static boolean isSprinting(Object player) {
        Object sprinting = McAccess.invoke(player, "func_70051_ag", new Class<?>[0]);
        if (sprinting instanceof Boolean) return (Boolean) sprinting;
        sprinting = McAccess.invoke(player, "func_70051_af", new Class<?>[0]);
        if (sprinting instanceof Boolean) return (Boolean) sprinting;
        return false;
    }

    private boolean isBlocking(Object player) {
        Object result = McAccess.invoke(player, "func_70632_aY", new Class<?>[0]);
        if (result == null)
            result = McAccess.invokeNamed(player, "isBlocking", new Class<?>[0]);
        return result instanceof Boolean && (Boolean) result;
    }

    private boolean isHoldingWeapon(Object player) {
        Object stack = McAccess.invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null)
            stack = McAccess.invokeNamed(player, "getHeldItem", new Class<?>[0]);
        if (stack == null) return false;
        Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
        if (item == null)
            item = McAccess.invokeNamed(stack, "getItem", new Class<?>[0]);
        if (item == null) return false;
        String name = item.getClass().getName();
        return name.contains("ItemSword") || name.contains("ItemAxe");
    }

    private boolean isUsingSplashPotion(Object player) {
        Object using = McAccess.invokeNamed(player, "isUsingItem", new Class<?>[0]);
        if (!(using instanceof Boolean) || !((Boolean) using)) return false;
        Object stack = McAccess.invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null)
            stack = McAccess.invokeNamed(player, "getHeldItem", new Class<?>[0]);
        if (stack == null) return false;
        Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
        if (item == null)
            item = McAccess.invokeNamed(stack, "getItem", new Class<?>[0]);
        if (item == null || !item.getClass().getName().contains("ItemPotion")) return false;
        Object meta = McAccess.invoke(stack, "func_77960_j", new Class<?>[0]);
        if (!(meta instanceof Integer))
            meta = McAccess.invokeNamed(stack, "getMetadata", new Class<?>[0]);
        int m = meta instanceof Integer ? (Integer) meta : 0;
        Object splash = McAccess.invokeStatic(item.getClass(), "func_77831_g", new Class<?>[] { int.class }, m);
        if (!(splash instanceof Boolean))
            splash = McAccess.invokeStatic(item.getClass(), "isSplash", new Class<?>[] { int.class }, m);
        return splash instanceof Boolean && (Boolean) splash;
    }

    private static void disableConflictingOutboundLag(String otherModuleName) {
        Module other = ModuleManager.INSTANCE.getModule(otherModuleName);
        if (other != null && other.isEnabled())
            other.setEnabled(false);
    }

    private void drawGhostBox(double rx, double ry, double rz,
                              float r, float g, float b, float lineWidth) {
        if (espFilled.getValue()) {
            RenderHelper.drawFilledBox(
                    rx - 0.3, ry, rz - 0.3,
                    rx + 0.3, ry + 1.8, rz + 0.3,
                    r, g, b, 0.25f);
        }
        RenderHelper.drawBoundingBox(
                rx - 0.3, ry, rz - 0.3,
                rx + 0.3, ry + 1.8, rz + 0.3,
                r, g, b, 1.0f, lineWidth);
    }

}

package gnu.client.module.modules.network;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.ModuleManager;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.BacktrackTargetPosition;
import gnu.client.runtime.packet.InboundLagCoordinator;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;
import gnu.client.runtime.packet.PacketUtil;
import gnu.client.util.RenderHelper;

import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Timewarp Back Track — settings and behavior matched to ctw.dll.
 */
public final class BacktrackModule extends Module implements PacketListener {

    private static final List<String> MODES = Arrays.asList("Normal", "Advanced");
    private static final List<String> RENDER_MODES = Arrays.asList("Box", "Outline", "Both");

    private final ModeSetting mode = addSetting(new ModeSetting("Mode", 0, MODES));
    private final SliderSetting backtrackTime = addSetting(new SliderSetting("Backtrack time", 150.0f, 0.0f, 2000.0f));
    private final SliderSetting distanceMin = addSetting(new SliderSetting("DistanceMin", 1.0f, 0.0f, 10.0f));
    private final SliderSetting distanceMax = addSetting(new SliderSetting("DistanceMax", 4.0f, 0.0f, 10.0f));
    private final SliderSetting cooldown = addSetting(new SliderSetting("Cooldown", 1000.0f, 0.0f, 5000.0f));
    private final SliderSetting chance = addSetting(new SliderSetting("Chance", 100.0f, 0.0f, 100.0f));
    private final SliderSetting requiredHits = addSetting(new SliderSetting("Required hits", 0.0f, 0.0f, 10.0f));
    private final SliderSetting comboThreshold = addSetting(new SliderSetting("ComboThreshold", 0.0f, 0.0f, 10.0f));
    private final SliderSetting attackWindow = addSetting(new SliderSetting("AttackWindow", 1000.0f, 100.0f, 3000.0f));
    private final SliderSetting hurtTime = addSetting(new SliderSetting("Hurt time", 200.0f, 0.0f, 1000.0f));
    private final BoolSetting disableOnHit = addSetting(new BoolSetting("Disable on hit", true));
    private final BoolSetting holdingWeapon = addSetting(new BoolSetting("Holding weapon", false));
    private final BoolSetting useDistanceCheck = addSetting(new BoolSetting("Distance check", true));

    private final BoolSetting enableVisuals = addSetting(new BoolSetting("Enable visuals", true));
    private final BoolSetting renderServerRecord = addSetting(new BoolSetting("RenderServerRecord", true));
    private final ModeSetting renderMode = addSetting(new ModeSetting("Render mode", 0, RENDER_MODES));
    private final BoolSetting drawBox = addSetting(new BoolSetting("DrawBox", true));
    private final BoolSetting drawOutline = addSetting(new BoolSetting("DrawOutline", true));
    private final BoolSetting drawFill = addSetting(new BoolSetting("DrawFill", false));
    private final SliderSetting boxColorR = addSetting(new SliderSetting("BoxColorR", 0.0f, 0.0f, 255.0f));
    private final SliderSetting boxColorG = addSetting(new SliderSetting("BoxColorG", 255.0f, 0.0f, 255.0f));
    private final SliderSetting boxColorB = addSetting(new SliderSetting("BoxColorB", 0.0f, 0.0f, 255.0f));
    private final SliderSetting outlineColorR = addSetting(new SliderSetting("OutlineColorR", 255.0f, 0.0f, 255.0f));
    private final SliderSetting outlineColorG = addSetting(new SliderSetting("OutlineColorG", 255.0f, 0.0f, 255.0f));
    private final SliderSetting outlineColorB = addSetting(new SliderSetting("OutlineColorB", 255.0f, 0.0f, 255.0f));
    private final SliderSetting lineWidth = addSetting(new SliderSetting("Line width", 1.5f, 1.0f, 4.0f));
    private final SliderSetting outlineWidth = addSetting(new SliderSetting("Outline width", 1.5f, 1.0f, 4.0f));
    private final BoolSetting enableTrail = addSetting(new BoolSetting("EnableTrail", true));
    private final SliderSetting trailDuration = addSetting(new SliderSetting("Trail duration", 500.0f, 100.0f, 2000.0f));
    private final SliderSetting maxTrailPoints = addSetting(new SliderSetting("MaxTrailPoints", 20.0f, 5.0f, 50.0f));
    private final SliderSetting trailColorR = addSetting(new SliderSetting("TrailColorR", 0.0f, 0.0f, 255.0f));
    private final SliderSetting trailColorG = addSetting(new SliderSetting("TrailColorG", 255.0f, 0.0f, 255.0f));
    private final SliderSetting trailColorB = addSetting(new SliderSetting("TrailColorB", 0.0f, 0.0f, 255.0f));
    private final BoolSetting enablePulse = addSetting(new BoolSetting("EnablePulse", true));
    private final SliderSetting pulseSpeed = addSetting(new SliderSetting("Pulse speed", 2.0f, 0.5f, 5.0f));
    private final SliderSetting pulseMinAlpha = addSetting(new SliderSetting("Pulse min alpha", 45.0f, 0.0f, 100.0f));
    private final SliderSetting pulseMaxAlpha = addSetting(new SliderSetting("Pulse max alpha", 100.0f, 0.0f, 100.0f));
    private final BoolSetting enableGlow = addSetting(new BoolSetting("EnableGlow", false));
    private final SliderSetting glowIntensity = addSetting(new SliderSetting("Glow intensity", 0.5f, 0.1f, 1.0f));
    private final SliderSetting glowColorR = addSetting(new SliderSetting("GlowColorR", 0.0f, 0.0f, 255.0f));
    private final SliderSetting glowColorG = addSetting(new SliderSetting("GlowColorG", 255.0f, 0.0f, 255.0f));
    private final SliderSetting glowColorB = addSetting(new SliderSetting("GlowColorB", 0.0f, 0.0f, 255.0f));
    private final BoolSetting enableHurt = addSetting(new BoolSetting("EnableHurt", false));
    private final SliderSetting hurtColorR = addSetting(new SliderSetting("HurtColorR", 255.0f, 0.0f, 255.0f));
    private final SliderSetting hurtColorG = addSetting(new SliderSetting("HurtColorG", 0.0f, 0.0f, 255.0f));
    private final SliderSetting hurtColorB = addSetting(new SliderSetting("HurtColorB", 0.0f, 0.0f, 255.0f));
    private final SliderSetting hurtDuration = addSetting(new SliderSetting("Hurt duration", 500.0f, 0.0f, 2000.0f));

    private static final int MAX_INBOUND_RELEASE_PER_TICK = 2;
    private static final int MAX_QUEUE_DEPTH = 16;
    private static final long TRACKING_BUFFER_MS = 500L;
    private static final long GHOST_INTERP_MS = 80L;
    private static final double POS_EPS = 1.0e-6;
    private final ConcurrentLinkedQueue<QueuedInbound> inboundQueue = new ConcurrentLinkedQueue<>();
    private final BacktrackTargetPosition position = new BacktrackTargetPosition();
    private final Deque<TrailPoint> serverTrail = new ArrayDeque<>();

    private Object target;
    private int targetEntityId = -1;

    private long nextBacktrackAllowedAtMs;
    private long trackingBufferUntilMs;
    private long lastAttackMs;
    private int currentDelayMs;
    private boolean shouldPauseTarget;
    private float rolledChance;
    private int targetHitCount;
    private int comboCount;

    private double ghostFromX;
    private double ghostFromY;
    private double ghostFromZ;
    private double ghostToX;
    private double ghostToY;
    private double ghostToZ;
    private long ghostInterpStartMs;
    private boolean ghostInterpValid;

    public BacktrackModule() {
        super("Back Track", "Hit players at their past position", Category.COMBAT);
    }

    public static void abortActiveLag() {
        Module mod = ModuleManager.INSTANCE.getModule("Back Track");
        if (mod instanceof BacktrackModule && mod.isEnabled())
            ((BacktrackModule) mod).clear(true, false, true);
    }

    @Override
    public void onEnable() {
        currentDelayMs = backtrackTime.getValue().intValue();
        clear(false, false, true);
        PacketEvents.register(this);
    }

    @Override
    public void onDisable() {
        PacketEvents.unregister(this);
        clear(true, false, true);
    }

    @Override
    public void onTick() {
        if (!isEnabled())
            return;
        Object mc = McAccess.getMinecraft();
        Object player = McAccess.thePlayer(mc);
        Object world = McAccess.theWorld(mc);
        if (player == null || world == null) {
            clear(false, true, true);
            return;
        }
        if (McAccess.currentScreen(mc) != null) {
            clear(false, true, true);
            return;
        }

        refreshAttackTarget(player);

        if (KnockbackDelayModule.isOwningInboundQueue()) {
            if (hasQueuedIncoming())
                clear(true, false, false);
            return;
        }

        boolean hadQueued = hasQueuedIncoming();
        if (shouldCancelPackets()) {
            flushExpired(System.currentTimeMillis());
            if (isLagging())
                recordTrailPoint();
        } else if (hadQueued) {
            // hard abort only if target dead or fully expired (age > attackWindow * 3)
            boolean hardAbort = target == null || !isEntityAlive(target)
                || (lastAttackMs > 0 && System.currentTimeMillis() - lastAttackMs
                > attackWindow.getValue().longValue() * 3);
            if (hardAbort) {
                clear(true, false, true);
            } else {
                flushExpired(System.currentTimeMillis());
            }
        }

        if (!hasQueuedIncoming())
            currentDelayMs = backtrackTime.getValue().intValue();
    }

    @Override
    public boolean onSend(Object packet) {
        if (!PacketHelper.isUseEntity(packet))
            return false;

        Object action = McAccess.invoke(packet, "func_149565_c", new Class<?>[0]);
        if (action == null)
            action = McAccess.invokeNamed(packet, "getAction", new Class<?>[0]);
        if (action == null || !String.valueOf(action).contains("ATTACK"))
            return false;

        Object world = McAccess.theWorld();
        int attackedId = resolveUseEntityTargetId(packet, world);
        if (attackedId < 0)
            return false;
        Object enemy = entityById(world, attackedId);
        if (enemy == null)
            return false;

        Object player = McAccess.thePlayer(McAccess.getMinecraft());
        if (player == null)
            return false;

        noteAttack(enemy, player);
        return false;
    }

    public void noteForgeAttack(Object enemy) {
        if (!isEnabled() || enemy == null)
            return;
        Object player = McAccess.thePlayer(McAccess.getMinecraft());
        if (player == null)
            return;
        noteAttack(enemy, player);
    }

    private void noteAttack(Object enemy, Object player) {
        lastAttackMs = System.currentTimeMillis();
        rolledChance = (float) (Math.random() * 100.0);

        if (enemy == target) {
            targetHitCount++;
            comboCount++;
        } else {
            targetHitCount = 1;
            comboCount = 1;
        }

        processTarget(enemy, player);
    }

    @Override
    public boolean onReceive(Object packet) {
        if (PacketUtil.isDispatching())
            return false;
        if (KnockbackDelayModule.isOwningInboundQueue())
            return false;

        if (disableOnHit.getValue() && hasQueuedIncoming()
                && PacketHelper.isSelfEntityVelocity(packet)) {
            clear(true, false, true);
            return false;
        }

        boolean hadQueued = hasQueuedIncoming();

        if (!shouldCancelPackets() && !hadQueued)
            return false;

        if (shouldPassOrFlushPacket(packet))
            return false;

        if (target == null || targetEntityId < 0)
            return false;

        if (PacketHelper.isBacktrackPassThrough(packet))
            return false;

        Object world = McAccess.theWorld(McAccess.getMinecraft());
        if (!PacketHelper.isBacktrackQueueCandidate(packet, targetEntityId, world))
            return false;

        if (!hadQueued)
            position.setBaseFromEntity(target);
        position.applyPacket(packet);

        Object player = McAccess.thePlayer(McAccess.getMinecraft());
        if (mode.getValue() == 1 && shouldSmartFlush(player)) {
            flushAllIncoming();
            position.setBaseFromEntity(target);
            return false;
        }

        if (isQueueSpanExceeded()) {
            flushAllIncoming();
            return false;
        }

        trimQueueToMaxDepth();

        if (!InboundLagCoordinator.tryAcquire(InboundLagCoordinator.Owner.BACKTRACK))
            return false;

        inboundQueue.offer(new QueuedInbound(packet, System.currentTimeMillis()));
        resyncTrackedPosition();
        return true;
    }

    @Override
    public void onRender(float partialTicks) {
        if (!enableVisuals.getValue() || !isLagging() || !McAccess.isInGame() || target == null)
            return;
        resyncTrackedPosition();
        if (!position.isValid())
            return;

        double[] ghost = renderGhostPosition();
        double sx = ghost[0];
        double sy = ghost[1];
        double sz = ghost[2];

        Object mc = McAccess.getMinecraft();
        double[] vp = McAccess.getViewerPos(mc, partialTicks);
        float alpha = pulseAlpha();
        boolean hurtFlash = enableHurt.getValue() && McAccess.getInt(target, "field_70737_aN") > 0;

        float br = boxColorR.getValue() / 255.0f;
        float bg = boxColorG.getValue() / 255.0f;
        float bb = boxColorB.getValue() / 255.0f;
        float or = outlineColorR.getValue() / 255.0f;
        float og = outlineColorG.getValue() / 255.0f;
        float ob = outlineColorB.getValue() / 255.0f;
        if (hurtFlash) {
            br = hurtColorR.getValue() / 255.0f;
            bg = hurtColorG.getValue() / 255.0f;
            bb = hurtColorB.getValue() / 255.0f;
            or = br;
            og = bg;
            ob = bb;
        }

        RenderHelper.begin();
        if (renderServerRecord.getValue() && enableTrail.getValue())
            drawServerTrail(vp, alpha * 0.6f);

        int drawMode = renderMode.getValue();
        boolean drawBoxLayer = drawMode == 0 || drawMode == 2;
        boolean drawOutlineLayer = drawMode == 1 || drawMode == 2;

        if (enableGlow.getValue()) {
            float gr = glowColorR.getValue() / 255.0f;
            float gg = glowColorG.getValue() / 255.0f;
            float gb = glowColorB.getValue() / 255.0f;
            drawGhostBox(sx - vp[0], sy - vp[1], sz - vp[2],
                    gr, gg, gb, alpha * glowIntensity.getValue() * 0.35f,
                    lineWidth.getValue() + 1.5f, true, false, true, false);
        }

        if (drawFill.getValue() && drawBoxLayer)
            drawGhostBox(sx - vp[0], sy - vp[1], sz - vp[2], br, bg, bb, alpha * 0.25f,
                    lineWidth.getValue(), false, true, false, false);

        if (drawBox.getValue() && drawBoxLayer)
            drawGhostBox(sx - vp[0], sy - vp[1], sz - vp[2], br, bg, bb, alpha,
                    lineWidth.getValue(), true, false, false, false);

        if (drawOutline.getValue() && drawOutlineLayer)
            drawGhostBox(sx - vp[0], sy - vp[1], sz - vp[2], or, og, ob, alpha,
                    outlineWidth.getValue(), false, false, true, false);

        RenderHelper.end();
    }

    public boolean isLagging() {
        return isEnabled() && hasQueuedIncoming();
    }

    public int activeTargetId() {
        return targetEntityId;
    }

    private void processTarget(Object enemy, Object player) {
        shouldPauseTarget = isLivingEntity(enemy) && shouldPauseForHurtTime(enemy);

        if (!shouldBacktrack(enemy, player))
            return;

        if (enemy != target)
            clear(true, false, false);

        target = enemy;
        targetEntityId = McAccess.entityId(enemy);
        position.setBaseFromEntity(enemy);
    }

    private void recordTrailPoint() {
        if (!renderServerRecord.getValue() || !enableTrail.getValue() || !position.isValid())
            return;
        long now = System.currentTimeMillis();
        serverTrail.addLast(new TrailPoint(position.x(), position.y(), position.z(), now));
        int maxPoints = maxTrailPoints.getValue().intValue();
        while (serverTrail.size() > maxPoints)
            serverTrail.pollFirst();
        long cutoff = now - trailDuration.getValue().longValue();
        while (!serverTrail.isEmpty() && serverTrail.peekFirst().time < cutoff)
            serverTrail.pollFirst();
    }

    private double[] renderGhostPosition() {
        double sx = position.x();
        double sy = position.y();
        double sz = position.z();

        long nowMs = System.currentTimeMillis();
        if (!ghostInterpValid) {
            ghostFromX = sx;
            ghostFromY = sy;
            ghostFromZ = sz;
            ghostToX = sx;
            ghostToY = sy;
            ghostToZ = sz;
            ghostInterpStartMs = nowMs;
            ghostInterpValid = true;
        } else if (posChanged(sx, sy, sz, ghostToX, ghostToY, ghostToZ)) {
            double te = Math.min(1.0, (nowMs - ghostInterpStartMs) / (double) GHOST_INTERP_MS);
            ghostFromX = lerp(ghostFromX, ghostToX, te);
            ghostFromY = lerp(ghostFromY, ghostToY, te);
            ghostFromZ = lerp(ghostFromZ, ghostToZ, te);
            ghostToX = sx;
            ghostToY = sy;
            ghostToZ = sz;
            ghostInterpStartMs = nowMs;
        }

        double t = Math.min(1.0, (nowMs - ghostInterpStartMs) / (double) GHOST_INTERP_MS);
        return new double[] {
                lerp(ghostFromX, ghostToX, t),
                lerp(ghostFromY, ghostToY, t),
                lerp(ghostFromZ, ghostToZ, t)
        };
    }

    private void clearGhostInterp() {
        ghostInterpValid = false;
    }

    private static boolean posChanged(double ax, double ay, double az,
            double bx, double by, double bz) {
        return Math.abs(ax - bx) > POS_EPS || Math.abs(ay - by) > POS_EPS || Math.abs(az - bz) > POS_EPS;
    }

    private static double lerp(double from, double to, double t) {
        if (t <= 0.0)
            return from;
        if (t >= 1.0)
            return to;
        return from + (to - from) * t;
    }

    private float pulseAlpha() {
        if (!enablePulse.getValue())
            return 1.0f;
        float minA = pulseMinAlpha.getValue() / 100.0f;
        float maxA = pulseMaxAlpha.getValue() / 100.0f;
        double wave = Math.sin(System.currentTimeMillis() * 0.001 * pulseSpeed.getValue());
        return minA + (float) ((wave + 1.0) * 0.5 * (maxA - minA));
    }

    private void drawServerTrail(double[] vp, float alpha) {
        if (serverTrail.size() < 2)
            return;
        float r = trailColorR.getValue() / 255.0f;
        float g = trailColorG.getValue() / 255.0f;
        float b = trailColorB.getValue() / 255.0f;
        TrailPoint prev = null;
        for (TrailPoint point : serverTrail) {
            if (prev != null) {
                RenderHelper.drawLine3D(
                        prev.x - vp[0], prev.y - vp[1], prev.z - vp[2],
                        point.x - vp[0], point.y - vp[1], point.z - vp[2],
                        r, g, b, alpha, 1.0f);
            }
            prev = point;
        }
    }

    private boolean isQueueSpanExceeded() {
        QueuedInbound head = inboundQueue.peek();
        if (head == null)
            return false;
        return System.currentTimeMillis() - head.time >= backtrackTime.getValue().longValue();
    }

    private void trimQueueToMaxDepth() {
        QueuedInbound oldest;
        while (inboundQueue.size() >= MAX_QUEUE_DEPTH && (oldest = inboundQueue.poll()) != null) {
            PacketUtil.processInbound(oldest.packet);
            resyncTrackedPosition();
        }
    }

    private void refreshAttackTarget(Object player) {
        if (target == null)
            return;
        shouldPauseTarget = isLivingEntity(target) && shouldPauseForHurtTime(target);

        long now = System.currentTimeMillis();
        boolean attackExpired = now - lastAttackMs > attackWindow.getValue().longValue();

        boolean inRange = !useDistanceCheck.getValue() || isInDistanceRange(player, target);
        if (inRange)
            trackingBufferUntilMs = now + TRACKING_BUFFER_MS;

        boolean outOfRange = useDistanceCheck.getValue() && !inRange && now > trackingBufferUntilMs;
        if (!isEntityAlive(target) || attackExpired || outOfRange) {
            if (!hasQueuedIncoming())
                clear(false, false, true);
        }
    }

    private boolean shouldBacktrack(Object enemy, Object player) {
        if (holdingWeapon.getValue() && !isHoldingWeapon(player))
            return false;

        boolean inRange = !useDistanceCheck.getValue() || isInDistanceRange(player, enemy);
        if (inRange)
            trackingBufferUntilMs = System.currentTimeMillis() + TRACKING_BUFFER_MS;

        long now = System.currentTimeMillis();
        if (useDistanceCheck.getValue() && !inRange && now > trackingBufferUntilMs)
            return false;

        if (!shouldAttackEntity(enemy, player))
            return false;

        int ticks = McAccess.getInt(player, "field_70173_aa");
        if (ticks <= 10)
            return false;

        if (now < nextBacktrackAllowedAtMs)
            return false;

        if (rolledChance >= chance.getValue())
            return false;

        if (shouldPauseTarget)
            return false;

        if (now - lastAttackMs > attackWindow.getValue().longValue())
            return false;

        int req = requiredHits.getValue().intValue();
        if (req > 0 && targetHitCount < req)
            return false;

        int comboReq = comboThreshold.getValue().intValue();
        if (comboReq > 0 && comboCount < comboReq)
            return false;

        if (KnockbackDelayModule.isBlockingBacktrack())
            return false;

        return true;
    }

    private boolean isInDistanceRange(Object player, Object entity) {
        double dist = boxedDistanceTo(player, entity);
        return dist >= distanceMin.getValue() && dist <= distanceMax.getValue();
    }

    /** Advanced mode: flush when tracked server position is closer than the rendered entity. */
    private boolean shouldSmartFlush(Object player) {
        if (target == null || !position.isValid())
            return false;
        double ghostDist = squaredBoxDistanceToGhost(player);
        double visibleDist = squaredBoxDistanceTo(player, target);
        return ghostDist + 0.01 < visibleDist;
    }

    private double squaredBoxDistanceToGhost(Object player) {
        double px = McAccess.getDouble(player, "field_70165_t");
        double py = McAccess.getDouble(player, "field_70163_u");
        double pz = McAccess.getDouble(player, "field_70161_v");
        double halfW = target != null ? McAccess.getFloat(target, "field_70130_N") * 0.5f : 0.3f;
        double height = target != null ? McAccess.getFloat(target, "field_70131_O") : 1.8;
        double ex = position.x();
        double ey = position.y();
        double ez = position.z();
        double cx = clamp(px, ex - halfW, ex + halfW);
        double cy = clamp(py, ey, ey + height);
        double cz = clamp(pz, ez - halfW, ez + halfW);
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean shouldCancelPackets() {
        Object player = McAccess.thePlayer(McAccess.getMinecraft());
        if (player == null || target == null || !isEntityAlive(target))
            return false;
        return shouldBacktrack(target, player);
    }

    private boolean hasQueuedIncoming() {
        return !inboundQueue.isEmpty();
    }

    private void flushExpired(long nowMs) {
        int released = 0;
        while (released < MAX_INBOUND_RELEASE_PER_TICK) {
            QueuedInbound head = inboundQueue.peek();
            if (head == null || nowMs - head.time < currentDelayMs)
                break;
            inboundQueue.poll();
            PacketUtil.processInbound(head.packet);
            released++;
        }
        resyncTrackedPosition();
    }

    private void flushAllIncoming() {
        QueuedInbound entry;
        while ((entry = inboundQueue.poll()) != null)
            PacketUtil.processInbound(entry.packet);
        resyncTrackedPosition();
    }

    private void clear(boolean handlePackets, boolean clearOnly, boolean resetChronometers) {
        if (handlePackets && !clearOnly)
            flushAllIncoming();
        else if (clearOnly)
            inboundQueue.clear();

        if (target != null && resetChronometers)
            nextBacktrackAllowedAtMs = System.currentTimeMillis() + cooldown.getValue().longValue();

        target = null;
        targetEntityId = -1;
        position.reset();
        serverTrail.clear();
        clearGhostInterp();
        shouldPauseTarget = false;
        if (!hasQueuedIncoming())
            InboundLagCoordinator.release(InboundLagCoordinator.Owner.BACKTRACK);
    }

    private void resyncTrackedPosition() {
        if (target == null) {
            position.reset();
            return;
        }
        position.rebuildFrom(target, queuedPackets());
    }

    private Iterable<Object> queuedPackets() {
        return () -> new java.util.Iterator<Object>() {
            private final java.util.Iterator<QueuedInbound> it = inboundQueue.iterator();

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Object next() {
                return it.next().packet;
            }
        };
    }

    private boolean shouldPauseForHurtTime(Object entity) {
        int maxTicks = maxHurtTimeTicks();
        if (maxTicks <= 0)
            return false;
        return McAccess.getInt(entity, "field_70737_aN") >= maxTicks;
    }

    private int maxHurtTimeTicks() {
        return Math.max(0, (int) (hurtTime.getValue() / 50.0f));
    }

    private boolean isHoldingWeapon(Object player) {
        Object stack = McAccess.invoke(player, "func_70694_bm", new Class<?>[0]);
        if (stack == null)
            stack = McAccess.invokeNamed(player, "getHeldItem", new Class<?>[0]);
        if (stack == null)
            return false;
        Object item = McAccess.invoke(stack, "func_77973_b", new Class<?>[0]);
        if (item == null)
            item = McAccess.invokeNamed(stack, "getItem", new Class<?>[0]);
        if (item == null)
            return false;
        String name = item.getClass().getName();
        return name.contains("ItemSword") || name.contains("ItemAxe");
    }

    private boolean shouldPassOrFlushPacket(Object packet) {
        if (packet == null)
            return true;
        if (PacketHelper.isPlayerPosLook(packet) || PacketHelper.isDisconnect(packet)) {
            clear(true, false, true);
            return true;
        }
        if (PacketHelper.isUpdateHealth(packet)) {
            Object health = McAccess.invoke(packet, "func_71165_d", new Class<?>[0]);
            if (health instanceof Float && (Float) health <= 0f) {
                clear(true, false, true);
                return true;
            }
        }
        if (PacketHelper.isDestroyEntities(packet) && targetEntityId >= 0) {
            Object ids = McAccess.invokeNamed(packet, "getEntityIDs", new Class<?>[0]);
            if (ids == null)
                ids = McAccess.getObject(packet, "field_149074_i");
            if (ids instanceof int[]) {
                for (int id : (int[]) ids) {
                    if (id == targetEntityId) {
                        clear(true, false, true);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean shouldAttackEntity(Object entity, Object player) {
        if (entity == null || entity == player)
            return false;
        if (!isEntityPlayer(entity))
            return false;
        return isEntityAlive(entity);
    }

    private static boolean isLivingEntity(Object entity) {
        return entity != null && isEntityPlayer(entity);
    }

    private static boolean isEntityPlayer(Object entity) {
        Class<?> c = entity.getClass();
        while (c != null) {
            if (c.getName().contains("EntityPlayer"))
                return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean isEntityAlive(Object entity) {
        if (entity == null)
            return false;
        Object dead = McAccess.invokeNamed(entity, "isDead", new Class<?>[0]);
        if (dead instanceof Boolean)
            return !(Boolean) dead;
        return McAccess.getInt(entity, "field_70725_aQ") <= 0;
    }

    private static Object entityById(Object world, int entityId) {
        if (world == null || entityId < 0)
            return null;
        Object entitiesObj = McAccess.getObject(world, "field_73010_i");
        if (!(entitiesObj instanceof Iterable))
            return null;
        for (Object entity : (Iterable<?>) entitiesObj) {
            if (entity != null && McAccess.entityId(entity) == entityId)
                return entity;
        }
        return null;
    }

    private static int resolveUseEntityTargetId(Object packet, Object world) {
        int direct = McAccess.getInt(packet, "field_149567_a");
        if (direct > 0)
            return direct;
        Object idObj = McAccess.invokeNamed(packet, "getEntityId", new Class<?>[0]);
        if (idObj instanceof Integer && (Integer) idObj > 0)
            return (Integer) idObj;
        if (world != null) {
            Object entity = McAccess.invoke(packet, "func_149564_a", new Class<?>[] { world.getClass() }, world);
            if (entity == null)
                entity = McAccess.invokeNamed(packet, "getEntityFromWorld", new Class<?>[] { world.getClass() }, world);
            int resolved = McAccess.entityId(entity);
            if (resolved > 0)
                return resolved;
        }
        return -1;
    }

    private static double boxedDistanceTo(Object player, Object entity) {
        return Math.sqrt(squaredBoxDistanceTo(player, entity));
    }

    private static double squaredBoxDistanceTo(Object player, Object entity) {
        double px = McAccess.getDouble(player, "field_70165_t");
        double py = McAccess.getDouble(player, "field_70163_u");
        double pz = McAccess.getDouble(player, "field_70161_v");
        double ex = McAccess.getDouble(entity, "field_70165_t");
        double ey = McAccess.getDouble(entity, "field_70163_u");
        double ez = McAccess.getDouble(entity, "field_70161_v");
        double halfW = McAccess.getFloat(entity, "field_70130_N") * 0.5f;
        double height = McAccess.getFloat(entity, "field_70131_O");
        double cx = clamp(px, ex - halfW, ex + halfW);
        double cy = clamp(py, ey, ey + height);
        double cz = clamp(pz, ez - halfW, ez + halfW);
        double dx = cx - px;
        double dy = cy - py;
        double dz = cz - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private void drawGhostBox(double rx, double ry, double rz,
            float r, float g, float b, float alpha, float lw,
            boolean box, boolean fill, boolean outline, boolean unusedWireframe) {
        double half = 0.3;
        double height = 1.8;
        if (target != null) {
            half = McAccess.getFloat(target, "field_70130_N") * 0.5f;
            height = McAccess.getFloat(target, "field_70131_O");
        }

        float drawLw = lw;

        if (fill)
            RenderHelper.drawFilledBox(
                    rx - half, ry, rz - half,
                    rx + half, ry + height, rz + half,
                    r, g, b, alpha);

        if (box || outline)
            RenderHelper.drawBoundingBox(
                    rx - half, ry, rz - half,
                    rx + half, ry + height, rz + half,
                    r, g, b, alpha, drawLw);
    }

    private static final class QueuedInbound {
        final Object packet;
        final long time;

        QueuedInbound(Object packet, long time) {
            this.packet = packet;
            this.time = time;
        }
    }

    private static final class TrailPoint {
        final double x;
        final double y;
        final double z;
        final long time;

        TrailPoint(double x, double y, double z, long time) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.time = time;
        }
    }
}

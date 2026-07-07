package gnu.client.module.modules.player;

import gnu.client.module.Category;
import gnu.client.module.Module;
import gnu.client.module.setting.BoolSetting;
import gnu.client.module.setting.ModeSetting;
import gnu.client.module.setting.SliderSetting;
import gnu.client.runtime.PlayerUpdateHook;
import gnu.client.runtime.RotationState;
import gnu.client.runtime.ScaffoldItemSpoofHook;
import gnu.client.runtime.mc.McAccess;
import gnu.client.runtime.packet.PacketEvents;
import gnu.client.runtime.packet.PacketHelper;
import gnu.client.runtime.packet.PacketListener;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenMyau-style 1.8.9 scaffold port.
 *
 * <p>This intentionally avoids the previous packet/silent-rotation pipeline.
 * It uses the same basic flow as OpenMyau: find support block, find/calculate a
 * face hit vec, request the OpenMyau-style pre-motion rotation, then place
 * through vanilla PlayerControllerMP.onPlayerRightClick.
 */
public final class ScaffoldModule extends Module implements PacketListener {

  private static final int ROT_NONE = 0;
  private static final int ROT_DEFAULT = 1;
  private static final int ROT_BACKWARDS = 2;
  private static final int ROT_SIDEWAYS = 3;
  private static final int ROT_GODBRIDGE = 4;
  private static final int ROT_SMOOTH = 5;

  private static final int MOVEFIX_NONE = 0;
  private static final int MOVEFIX_SILENT = 1;

  private static final int TELLY_RESET = 0;
  private static final int TELLY_REVERSE = 1;

  private static final int SPRINT_NONE = 0;
  private static final int SPRINT_VANILLA = 1;

  private static final int TOWER_NONE = 0;
  private static final int TOWER_MOTION = 1;
  private static final int TOWER_PULLDOWN = 2;
  private static final int TOWER_KARHU = 3;
  private static final int TOWER_VULCAN = 4;
  private static final int TOWER_HYPIXEL = 5;

  private static final int KEEPY_NONE = 0;
  private static final int KEEPY_VANILLA = 1;
  private static final int KEEPY_EXTRA = 2;
  private static final int KEEPY_TELLY = 3;

  private static final float ROTATION_PRIORITY = 3.0f;

  private static volatile boolean forceSneak;
  private static volatile boolean forceJump;
  private static volatile boolean tellyForwardMoveFixSkip;

  private final ModeSetting rotationMode = addSetting(new ModeSetting("rotations", ROT_BACKWARDS,
      Arrays.asList("NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS", "GODBRIDGE", "SMOOTH")));
  private final SliderSetting rotationSpeed = addSetting(new SliderSetting("rotation-speed", 45.0f, 1.0f, 180.0f));
  private final ModeSetting moveFix = addSetting(new ModeSetting("move-fix", MOVEFIX_SILENT,
      Arrays.asList("NONE", "SILENT")));
  private final ModeSetting sprintMode = addSetting(new ModeSetting("sprint", SPRINT_NONE,
      Arrays.asList("NONE", "VANILLA")));
  private final SliderSetting groundMotion = addSetting(new SliderSetting("ground-motion", 100.0f, 0.0f, 100.0f));
  private final SliderSetting airMotion = addSetting(new SliderSetting("air-motion", 100.0f, 0.0f, 100.0f));
  private final SliderSetting speedMotion = addSetting(new SliderSetting("speed-motion", 100.0f, 0.0f, 100.0f));
  private final BoolSetting telly = addSetting(new BoolSetting("telly", false));
  private final ModeSetting tellyResetMode = addSetting(new ModeSetting("telly-reset", TELLY_RESET,
      Arrays.asList("RESET", "REVERSE")));
  private final SliderSetting tellyStraightTicks = addSetting(new SliderSetting("telly-straight", 3.0f, 0.0f, 5.0f));
  private final SliderSetting tellyJumpTicks = addSetting(new SliderSetting("telly-jump", 0.0f, 0.0f, 10.0f));
  private final ModeSetting tower = addSetting(new ModeSetting("tower", TOWER_NONE,
      Arrays.asList("NONE", "MOTION", "PULLDOWN", "KARHU", "VULCAN", "HYPIXEL")));
  private final SliderSetting towerMotion = addSetting(new SliderSetting("tower-motion", 0.42f, 0.0f, 1.0f));
  private final SliderSetting towerTriggerHeight = addSetting(new SliderSetting("tower-trigger-height", 0.78f, 0.76f, 1.0f));
  private final SliderSetting towerSlow = addSetting(new SliderSetting("tower-slow", 1.0f, 0.0f, 3.0f));
  private final SliderSetting towerPulldownTrigger = addSetting(new SliderSetting("tower-pulldown-trigger", 0.1f, 0.0f, 0.2f));
  private final SliderSetting towerKarhuTimer = addSetting(new SliderSetting("tower-karhu-timer", 5.0f, 0.1f, 10.0f));
  private final SliderSetting towerKarhuTrigger = addSetting(new SliderSetting("tower-karhu-trigger", 0.06f, 0.0f, 0.2f));
  private final BoolSetting towerKarhuPulldown = addSetting(new BoolSetting("tower-karhu-pulldown", true));
  private final ModeSetting keepY = addSetting(new ModeSetting("keep-y", KEEPY_NONE,
      Arrays.asList("NONE", "VANILLA", "EXTRA", "TELLY")));
  private final BoolSetting keepYOnPress = addSetting(new BoolSetting("keep-y-on-press", false));
  private final BoolSetting multiPlace = addSetting(new BoolSetting("multi-place", false));
  private final BoolSetting safeWalk = addSetting(new BoolSetting("safe-walk", true));
  private final BoolSetting swing = addSetting(new BoolSetting("swing", false));
  private final BoolSetting itemSpoof = addSetting(new BoolSetting("item-spoof", false));
  private final BoolSetting blockCounter = addSetting(new BoolSetting("block-counter", true));

  private int rotationTick;
  private int lastSlot = -1;
  /** Hotbar slot scaffold places blocks from (may differ from client slot when item-spoof is on). */
  private int blockSlot = -1;
  /** Last hotbar index we told the server via C09 (dedupes BadPackets duplicate slot). */
  private int serverReportedSlot = -1;
  /** Client slot switch queued for C09 immediately before the next placement packet. */
  private int pendingServerSlot = -1;
  /** True while scaffold is sending its own C09 (do not treat as user hotbar scroll). */
  private boolean sendingServerSlot;
  private int blockCount = -1;
  private float yaw = -180.0f;
  private float pitch = 0.0f;
  private boolean canRotate;
  private boolean towering;
  private boolean towerWasOnGround = true;
  private double towerJumpOffY = Double.NaN;
  private int towerAirTicks;
  private boolean towerKarhuTimerActive;
  private boolean towerPulldownPending;
  private boolean wasTowerJump;
  private int stage;
  private int startY = 256;
  private boolean shouldKeepY;
  private int targetFacing = -1;
  private boolean ranPreUpdate;
  private int scaffoldCycleTick = -1;
  private int tellyTicksUntilJump;
  private int tellyAirTicks;
  private boolean tellyStraightJumpActive;
  private boolean tellyAwaitingPlacement;
  private boolean tellyWasOnGround;
  private boolean tellyDoNotAimActive;
  private boolean tellyDoNotAimWasActive;
  private boolean tellyRotTransition;
  private Object cycleWorld;
  private boolean cyclePrioritizePlacement;
  private boolean cycleAllowTellyAirPlacement;
  private float lastSentYaw = Float.MIN_VALUE;
  private ScaffoldPlacement.BlockData pendingBlockData;
  private Object pendingHit;
  private boolean pendingPlace;
  private float lastSentPitch;
  private int placementsThisTick;
  private boolean hasExactBlockAim;

  public ScaffoldModule() {
    super("Scaffold", "Automatically place blocks under you", Category.PLAYER);
  }

  @Override
  public void onEnable() {
    Object player = McAccess.thePlayer();
    lastSlot = player == null ? -1 : McAccess.getHotbarSlot(player);
    serverReportedSlot = lastSlot;
    blockSlot = -1;
    pendingServerSlot = -1;
    blockCount = -1;
    rotationTick = 3;
    yaw = -180.0f;
    pitch = 0.0f;
    canRotate = false;
    resetTowerState();
    stage = 0;
    startY = player == null ? 256 : floor(McAccess.entityPosY(player));
    shouldKeepY = false;
    towering = false;
    targetFacing = -1;
    forceSneak = false;
    forceJump = false;
    tellyDoNotAimActive = false;
    tellyDoNotAimWasActive = false;
    tellyRotTransition = false;
    tellyForwardMoveFixSkip = false;
    RotationState.reset();
    tellyTicksUntilJump = 0;
    tellyAirTicks = 0;
    tellyStraightJumpActive = false;
    tellyAwaitingPlacement = false;
    tellyWasOnGround = true;
    scaffoldCycleTick = -1;
    clearPendingPlacement();
    PacketEvents.register(this);
  }

  @Override
  public void onDisable() {
    Object player = McAccess.thePlayer();
    if (player != null && lastSlot >= 0 && lastSlot <= 8) {
      McAccess.setHotbarSlot(player, lastSlot);
      notifyServerSlot(lastSlot);
      McAccess.setSneaking(player, false);
    }
    serverReportedSlot = -1;
    blockSlot = -1;
    pendingServerSlot = -1;
    forceSneak = false;
    forceJump = false;
    tellyDoNotAimActive = false;
    tellyDoNotAimWasActive = false;
    tellyRotTransition = false;
    tellyForwardMoveFixSkip = false;
    tellyStraightJumpActive = false;
    tellyAwaitingPlacement = false;
    tellyWasOnGround = true;
    scaffoldCycleTick = -1;
    RotationState.reset();
    clearPendingPlacement();
    resetTowerState();
    McAccess.resetTimer();
    PacketEvents.unregister(this);
  }

  @Override
  public void onTick() {
    if (ranPreUpdate) {
      ranPreUpdate = false;
      return;
    }
    runScaffoldCycle(false);
  }

  private void runScaffoldCycle(boolean deferPlacement) {
    if (!McAccess.isInGame() || McAccess.currentScreen() != null) {
      scaffoldCycleTick = -1;
      forceSneak = false;
      forceJump = false;
      clearPendingPlacement();
      clearRotationState();
      RotationState.applyState(false, 0.0f, 0.0f, 0.0f, -1);
      return;
    }

    Object player = McAccess.thePlayer();
    Object world = McAccess.theWorld();
    if (player == null || world == null) {
      scaffoldCycleTick = -1;
      forceSneak = false;
      forceJump = false;
      clearPendingPlacement();
      clearRotationState();
      RotationState.applyState(false, 0.0f, 0.0f, 0.0f, -1);
      return;
    }

    int tick = McAccess.getInt(player, "field_70173_aa");
    if (tick == scaffoldCycleTick)
      return;
    scaffoldCycleTick = tick;
    placementsThisTick = 0;
    hasExactBlockAim = false;

    cycleWorld = world;
    clearPendingPlacement();
    clearRotationState();

    if (rotationTick > 0)
      rotationTick--;

    updateKeepY(player);
    guardClientHotbarSlot(player);
    updateBlockSlot(player);
    updateTelly(player);
    applySafeWalk(player, world);
    applyTower(player, world);
    boolean towerJump = isTowerJumpActive(player);
    boolean tellyDoNotAim = !towerJump && isTellyDoNotAim(player);
    boolean prioritizePlacement = shouldPrioritizePlacement(player, tellyDoNotAim);
    cyclePrioritizePlacement = prioritizePlacement;
    boolean allowTellyAirPlacement = shouldAllowTellyAirPlacement(player, prioritizePlacement, tellyDoNotAim);
    cycleAllowTellyAirPlacement = allowTellyAirPlacement;
    if (telly.getValue() && tellyDoNotAim != tellyDoNotAimWasActive) {
      tellyRotTransition = true;
      if (tellyDoNotAim) {
        rotationTick = Math.max(rotationTick, 1);
      } else {
        // Straight window ended — snap block aim and place same tick.
        tellyRotTransition = false;
        rotationTick = 0;
      }
    }
    tellyDoNotAimWasActive = tellyDoNotAim;
    tellyDoNotAimActive = tellyDoNotAim;
    tellyForwardMoveFixSkip = isTellyForwardLookWindow(player, tellyDoNotAim);
    if (tellyDoNotAim && !allowTellyAirPlacement)
      prepareTellyRotation(player);
    else
      prepareBaseRotation(player);

    if (!towerJump && (!tellyDoNotAim || allowTellyAirPlacement))
      snapMovementFacingYaw(player);

    ScaffoldPlacement.BlockData blockData = ScaffoldPlacement.getBlockData(player, world, startY, stage, shouldKeepY);
    ScaffoldPlacement.AimData aimData = null;
    if (blockData != null && (!tellyDoNotAim || allowTellyAirPlacement)) {
      aimData = resolveBlockAim(player, blockData, pitch);
      if (aimData != null) {
        yaw = aimData.yaw;
        pitch = aimData.pitch;
        hasExactBlockAim = true;
        if (rotationMode.getValue() != ROT_NONE)
          canRotate = true;
      }
    }

    applyRotation(player);

    queuePlacement(player, blockData, towerJump, aimData, deferPlacement, prioritizePlacement,
        allowTellyAirPlacement);

    applySprint(player);

    if (targetFacing >= 0 && rotationTick <= 0 && placementsThisTick < maxPlacementsPerTick()) {
      int bx = floor(McAccess.entityPosX(player));
      int by = floor(McAccess.entityPosY(player)) - 1;
      int bz = floor(McAccess.entityPosZ(player));
      Object pos = McAccess.newInstance("net.minecraft.util.BlockPos",
          new Class<?>[] {int.class, int.class, int.class}, bx, by, bz);
      if (validatedPlacementHit(player, pos, targetFacing) != null)
        executePlacement(player, pos, targetFacing, null, deferPlacement);
      targetFacing = -1;
    }
  }

  private void queuePlacement(Object player, ScaffoldPlacement.BlockData blockData,
                              boolean towerJump, ScaffoldPlacement.AimData aimData,
                              boolean deferPlacement, boolean prioritizePlacement,
                              boolean allowTellyAirPlacement) {
    boolean rotationPlacementDelay = (rotationTick > 0 || (tellyRotTransition && tellyDoNotAimActive))
        && !towerJump;
    if (rotationPlacementDelay && (prioritizePlacement || allowTellyAirPlacement))
      rotationPlacementDelay = false;
    if (telly.getValue() && !McAccess.isOnGround() && isTellyMoving() && !isTowering(player))
      rotationPlacementDelay = false;

    if (blockData == null || !canPlaceThisTick(player, towerJump, allowTellyAirPlacement)
        || (rotationPlacementDelay && !towerJump)
        || !isHoldingPlaceable(player))
      return;

    Object hit = resolvePlacementHit(player, blockData, towerJump, aimData);
    if (hit == null)
      return;
    executePlacement(player, blockData.blockPos, blockData.faceOrdinal, hit, deferPlacement);
  }

  private void executePlacement(Object player, Object blockPos, int faceOrdinal,
                                Object hit, boolean deferPlacement) {
    if (deferPlacement) {
      pendingBlockData = new ScaffoldPlacement.BlockData(blockPos, faceOrdinal);
      pendingHit = hit;
      pendingPlace = true;
      return;
    }
    place(blockPos, faceOrdinal, hit);
  }

  private void clearPendingPlacement() {
    pendingBlockData = null;
    pendingHit = null;
    pendingPlace = false;
  }

  /** Kept for PlayerUpdateHook compatibility after the previous implementation. */
  public static void onPreUpdate(Object player) {
    gnu.client.module.Module module = gnu.client.module.ModuleManager.instance().getModule("Scaffold");
    if (module instanceof ScaffoldModule && module.isEnabled())
      ((ScaffoldModule) module).preUpdate(player);
  }

  private void preUpdate(Object player) {
    if (player == null)
      return;
    ranPreUpdate = true;
    runScaffoldCycle(true);
  }

  /**
   * OpenMyau PRE parity: place after silent rotation is applied to the entity but
   * before {@code onUpdateWalkingPlayer} sends the flying packet (C08 then C03).
   */
  public static void onBeforeWalkingPlace(Object player) {
    gnu.client.module.Module module = gnu.client.module.ModuleManager.instance().getModule("Scaffold");
    if (module instanceof ScaffoldModule && module.isEnabled())
      ((ScaffoldModule) module).beforeWalkingPlace(player);
  }

  private void beforeWalkingPlace(Object player) {
    if (player == null)
      return;
    flushPendingServerSlot(player);
    if (pendingPlace && pendingBlockData != null) {
      int faceOrdinal = pendingBlockData.faceOrdinal;
      Object blockPos = pendingBlockData.blockPos;
      Object hit = pendingHit;
      clearPendingPlacement();
      if (isHoldingPlaceable(player))
        tryPlace(player, blockPos, faceOrdinal, hit);
    }
    if ((!multiPlace.getValue()) || !isHoldingPlaceable(player) || blockCount <= 0)
      return;
    for (int i = 0; i < 2; i++) {
      if (placementsThisTick >= maxPlacementsPerTick())
        break;
      ScaffoldPlacement.BlockData blockData = ScaffoldPlacement.getBlockData(
          player, cycleWorld, startY, stage, shouldKeepY);
      if (blockData == null)
        break;
      Object hit = resolvePlacementHit(player, blockData, false, null);
      if (hit == null)
        break;
      tryPlace(player, blockData.blockPos, blockData.faceOrdinal);
    }
  }

  private int maxPlacementsPerTick() {
    return multiPlace.getValue() ? 3 : 1;
  }

  private void updateKeepY(Object player) {
    if (McAccess.isOnGround()) {
      boolean keepYAllowed = keepY.getValue() != KEEPY_NONE
          && (!keepYOnPress.getValue() || McAccess.isUseItemKeyDown())
          && !McAccess.isJumpKeyHeld();
      boolean preserveTellyKeepY = telly.getValue() && isTellyMoving() && keepYAllowed && stage > 0;

      if (stage > 0 && !preserveTellyKeepY)
        stage--;
      else if (stage < 0)
        stage++;

      boolean canStartKeepY = stage == 0 && keepYAllowed;
      if (canStartKeepY)
        stage = 1;

      if (!shouldKeepY && !preserveTellyKeepY)
        startY = floor(McAccess.entityPosY(player));
      shouldKeepY = false;
      towering = false;
    }
  }

  private void updateTelly(Object player) {
    if (!telly.getValue()) {
      forceJump = false;
      tellyTicksUntilJump = 0;
      tellyAirTicks = 0;
      tellyStraightJumpActive = false;
      return;
    }

    boolean onGround = McAccess.isOnGround();
    if (onGround) {
      tellyWasOnGround = true;
      tellyAirTicks = 0;
      tellyTicksUntilJump++;
    } else {
      tellyWasOnGround = false;
      tellyAirTicks++;
      if (tellyStraightJumpActive && tellyAirTicks > tellyStraightTicks())
        tellyAwaitingPlacement = true;
      forceJump = false;
    }

    if (!isTellyMoving() || !hasTellyBlocks(player) || isTowering(player)) {
      forceJump = false;
      return;
    }

    if (onGround && tellyTicksUntilJump >= tellyJumpTicks()) {
      // Always jump when delay is met — even while awaiting placement. Movement input runs
      // before deferred placement, so blocking jump here adds a full ground tick of lag.
      forceJump = true;
      tellyTicksUntilJump = 0;
      // Fresh straight + sprint window every jump; stale awaiting skips straight on later jumps.
      tellyStraightJumpActive = true;
      tellyAwaitingPlacement = false;
    } else if (onGround) {
      forceJump = false;
    }
  }

  /**
   * LiquidBounce {@code ScaffoldTellyFeature.doNotAim}: forward look once jump delay is met
   * ({@code ticksUntilJump >= jumpTicks}), then keep it through {@code telly-straight} air ticks
   * ({@code airTicks <= straight}) after a telly jump.
   */
  private boolean isTellyDoNotAim(Object player) {
    if (!telly.getValue() || !isTellyMoving() || !hasTellyBlocks(player) || isTowering(player))
      return false;
    if (!tellyStraightJumpActive)
      return false;
    if (McAccess.isOnGround()) {
      // Landing tick after straight: block-aim for placement, not forward look.
      if (tellyAwaitingPlacement)
        return false;
      return tellyTicksUntilJump >= tellyJumpTicks();
    }
    // Air straight is tick-count only — never let stale awaiting skip the window on later jumps.
    return tellyAirTicks <= tellyStraightTicks();
  }

  private boolean isTellyForwardLookWindow(Object player, boolean tellyDoNotAim) {
    if (!telly.getValue() || player == null)
      return false;
    if (!isTellyMoving() || !hasTellyBlocks(player) || isTowering(player))
      return false;
    // Skip fixStrafe only — silent forward yaw + MoveFixHook yaw swap still apply.
    return tellyDoNotAim;
  }

  private void prepareTellyRotation(Object player) {
    if (tellyResetMode.getValue() == TELLY_REVERSE) {
      yaw = ScaffoldPlacement.quantize(Math.round(McAccess.getYaw() / 45.0f) * 45.0f);
      pitch = ScaffoldPlacement.quantize(Math.max(45.0f, McAccess.getPitch()));
      canRotate = true;
    } else {
      // RESET: movement-forward yaw (not frozen camera look)
      yaw = ScaffoldPlacement.quantize(currentMoveYaw(player));
      pitch = ScaffoldPlacement.quantize(0.0f);
      canRotate = true;
    }
  }

  private void updateBlockSlot(Object player) {
    if (player == null)
      return;
    Object inv = McAccess.getObject(player, "field_71071_by");
    if (inv == null)
      return;

    int searchSlot = blockSlot >= 0 && blockSlot <= 8
        ? blockSlot
        : McAccess.getHotbarSlot(player);
    Object stack = stackInHotbarSlot(inv, searchSlot);
    int count = isPlaceableStack(stack) ? McAccess.getStackSize(stack) : 0;
    blockCount = Math.min(blockCount, count);
    if (blockCount > 0) {
      selectBlockSlot(player, searchSlot);
      return;
    }

    int slot = searchSlot;
    if (blockCount == 0)
      slot--;
    for (int i = slot; i > slot - 9; i--) {
      int hotbarSlot = (i % 9 + 9) % 9;
      Object candidate = McAccess.getStackInSlot(inv, hotbarSlot);
      if (!isPlaceableStack(candidate))
        continue;
      blockCount = McAccess.getStackSize(candidate);
      selectBlockSlot(player, hotbarSlot);
      return;
    }
    blockSlot = -1;
    blockCount = 0;
  }

  /** OpenMyau parity: client/server hotbar uses the block stack; item-spoof is render-only. */
  private void selectBlockSlot(Object player, int hotbarSlot) {
    if (hotbarSlot < 0 || hotbarSlot > 8)
      return;
    blockSlot = hotbarSlot;
    if (hotbarSlot != McAccess.getHotbarSlot(player)) {
      McAccess.setHotbarSlot(player, hotbarSlot);
      if (shouldSyncServerSlot(hotbarSlot))
        pendingServerSlot = hotbarSlot;
    }
  }

  /**
   * Revert accidental hotbar scroll/number-key switches while scaffold owns a block slot.
   */
  private void guardClientHotbarSlot(Object player) {
    if (player == null || blockSlot < 0 || blockSlot > 8 || blockCount <= 0)
      return;
    if (ScaffoldItemSpoofHook.isRenderSpoofActive())
      return;
    if (McAccess.getHotbarSlot(player) == blockSlot)
      return;
    McAccess.setHotbarSlot(player, blockSlot);
    if (shouldSyncServerSlot(blockSlot))
      pendingServerSlot = blockSlot;
  }

  /**
   * Skip redundant C09 when item-spoof already has the block stack in the selected slot,
   * or when the server was already told about this slot.
   */
  private boolean shouldSyncServerSlot(int slot) {
    if (slot < 0 || slot > 8 || slot == serverReportedSlot)
      return false;
    if (itemSpoof.getValue() && slot == lastSlot && slot == blockSlot)
      return false;
    return true;
  }

  private Object stackInHotbarSlot(Object inv, int slot) {
    if (inv == null || slot < 0 || slot > 8)
      return null;
    return McAccess.getStackInSlot(inv, slot);
  }

  /** Send C09 right before C08 so the server uses the same block slot as the client. */
  private void flushPendingServerSlot(Object player) {
    if (player == null) {
      pendingServerSlot = -1;
      return;
    }
    if (pendingServerSlot >= 0) {
      if (shouldSyncServerSlot(pendingServerSlot))
        notifyServerSlot(pendingServerSlot);
      pendingServerSlot = -1;
      return;
    }
    int syncSlot = blockSlot >= 0 && blockSlot <= 8
        ? blockSlot
        : McAccess.getHotbarSlot(player);
    if (shouldSyncServerSlot(syncSlot))
      notifyServerSlot(syncSlot);
  }

  private Object getPlacementStack(Object player) {
    if (player == null)
      return null;
    if (blockSlot >= 0 && blockSlot <= 8) {
      Object inv = McAccess.getObject(player, "field_71071_by");
      Object stack = stackInHotbarSlot(inv, blockSlot);
      if (isPlaceableStack(stack))
        return stack;
    }
    return McAccess.getHeldItemStack(player);
  }

  private void ensureClientBlockSlot(Object player) {
    if (player == null || blockSlot < 0 || blockSlot > 8)
      return;
    if (McAccess.getHotbarSlot(player) != blockSlot)
      McAccess.setHotbarSlot(player, blockSlot);
  }

  /**
   * OpenMyau item-spoof: render hooks show {@link #lastSlot}; placement uses the block slot.
   */
  private void notifyServerSlot(int slot) {
    if (!shouldSyncServerSlot(slot))
      return;
    sendingServerSlot = true;
    try {
      McAccess.sendHeldItemChange(slot);
    } finally {
      sendingServerSlot = false;
    }
    serverReportedSlot = slot;
  }

  /** Client hotbar index to show when item-spoof render hooks are active (OpenMyau {@code getSlot}). */
  public int getSpoofSlot() {
    return lastSlot;
  }

  public boolean isItemSpoofEnabled() {
    return itemSpoof.getValue();
  }

  private void prepareBaseRotation(Object player) {
    float currentYaw = currentMoveYaw(player);
    float reportedYaw = PlayerUpdateHook.lastReportedYaw(player);
    float yawDiffTo180 = wrapTo(currentYaw - 180.0f, reportedYaw);
    float diagonalYaw = isDiagonal(currentYaw)
        ? yawDiffTo180
        : wrapTo(currentYaw - 135.0f * (((currentYaw + 180.0f) % 90.0f) < 45.0f ? 1.0f : -1.0f),
            reportedYaw);

    if (canRotate)
      return;

    switch (rotationMode.getValue()) {
      case ROT_DEFAULT:
        yaw = ScaffoldPlacement.quantize(diagonalYaw);
        pitch = ScaffoldPlacement.quantize(85.0f);
        canRotate = true;
        break;
      case ROT_BACKWARDS:
        yaw = ScaffoldPlacement.quantize(yawDiffTo180);
        pitch = ScaffoldPlacement.quantize(85.0f);
        canRotate = true;
        break;
      case ROT_SIDEWAYS:
        yaw = ScaffoldPlacement.quantize(diagonalYaw);
        pitch = ScaffoldPlacement.quantize(85.0f);
        canRotate = true;
        break;
      case ROT_GODBRIDGE:
        yaw = ScaffoldPlacement.quantize(Math.round(currentYaw / 45.0f) * 45.0f);
        pitch = ScaffoldPlacement.quantize(79.3f);
        canRotate = true;
        break;
      case ROT_SMOOTH:
        yaw = ScaffoldPlacement.quantize(isDiagonal(currentYaw) ? diagonalYaw : yawDiffTo180);
        pitch = ScaffoldPlacement.quantize(85.0f);
        canRotate = true;
        break;
      default:
        yaw = McAccess.getYaw();
        pitch = McAccess.getPitch();
        canRotate = false;
        break;
    }
  }

  private float yawBaseForAim(Object player) {
    float reportedYaw = PlayerUpdateHook.lastReportedYaw(player);
    return yaw == -180.0f && pitch == 0.0f ? reportedYaw : wrapTo(yaw, reportedYaw);
  }

  private ScaffoldPlacement.AimData resolveBlockAim(Object player,
                                                    ScaffoldPlacement.BlockData blockData,
                                                    float basePitch) {
    if (player == null || blockData == null)
      return null;
    float reportedYaw = PlayerUpdateHook.lastReportedYaw(player);
    float moveYaw = currentMoveYaw(player);
    float presetYaw = yawBaseForAim(player);
    float cameraYaw = McAccess.getYaw();
    float[] bases = new float[] {presetYaw, moveYaw, reportedYaw, cameraYaw};
    return ScaffoldPlacement.findAimData(blockData, player, bases, basePitch);
  }

  private void clearRotationState() {
    canRotate = false;
    lastSentYaw = Float.MIN_VALUE;
  }

  /**
   * OpenMyau parity: snap backwards/sideways preset yaw only when block aim is within 90°
   * of the movement-facing preset.
   */
  private void snapMovementFacingYaw(Object player) {
    if (!canRotate || !isForwardPressed())
      return;

    float currentYaw = currentMoveYaw(player);
    float reportedYaw = PlayerUpdateHook.lastReportedYaw(player);
    float yawDiffTo180 = wrapTo(currentYaw - 180.0f, reportedYaw);
    float diagonalYaw = isDiagonal(currentYaw)
        ? yawDiffTo180
        : wrapTo(currentYaw - 135.0f * (((currentYaw + 180.0f) % 90.0f) < 45.0f ? 1.0f : -1.0f),
            reportedYaw);

    if (Math.abs(ScaffoldPlacement.wrapAngle(yawDiffTo180 - yaw)) >= 90.0f)
      return;

    switch (rotationMode.getValue()) {
      case ROT_BACKWARDS:
        yaw = ScaffoldPlacement.quantize(yawDiffTo180);
        break;
      case ROT_SIDEWAYS:
        yaw = ScaffoldPlacement.quantize(diagonalYaw);
        break;
      default:
        break;
    }
  }

  private void applyRotation(Object player) {
    if (rotationMode.getValue() == ROT_NONE) {
      RotationState.applyState(false, 0.0f, 0.0f, 0.0f, -1);
      lastSentYaw = Float.MIN_VALUE;
      return;
    }

    // Telly straight / reverse: smoothly rotate toward forward look — never break straight for emergency aim.
    if (tellyDoNotAimActive && telly.getValue()) {
      sendTellyTransitionRotation(player, yaw, pitch);
      return;
    }

    if (isTowerJumpActive(player)) {
      applyTowerRotation(player);
      return;
    }
    towering = false;
    applyNormalRotation(player);
  }

  private void applyNormalRotation(Object player) {
    float reportedYaw = PlayerUpdateHook.lastReportedYaw(player);
    float reportedPitch = PlayerUpdateHook.lastReportedPitch(player);
    float targetYaw = yaw;
    float targetPitch = pitch;

    if (cyclePrioritizePlacement || cycleAllowTellyAirPlacement || hasExactBlockAim) {
      tellyRotTransition = false;
      sendSilentRotation(targetYaw, targetPitch);
      return;
    }

    if (useTellyTransitionRotation()) {
      sendTellyTransitionRotation(player, targetYaw, targetPitch);
      return;
    }

    if (rotationMode.getValue() == ROT_SMOOTH) {
      sendSilentRotation(
          smoothYaw(reportedYaw, targetYaw),
          smoothPitch(reportedPitch, targetPitch));
    } else {
      sendSilentRotation(targetYaw, targetPitch);
    }
  }

  /** Downward silent rotation while jump-towering (LiquidBounce aimOnTower / OpenMyau isTowering). */
  private void applyTowerRotation(Object player) {
    float reportedYaw = PlayerUpdateHook.lastReportedYaw(player);
    float reportedPitch = PlayerUpdateHook.lastReportedPitch(player);
    float targetYaw = canRotate ? yaw : ScaffoldPlacement.quantize(currentMoveYaw(player));
    float targetPitch = canRotate ? pitch : 85.0f;
    if (targetPitch < 75.0f)
      targetPitch = 85.0f;
    targetYaw = ScaffoldPlacement.quantize(targetYaw);
    targetPitch = ScaffoldPlacement.quantize(targetPitch);

    if (rotationMode.getValue() == ROT_SMOOTH) {
      sendSilentRotation(
          smoothYaw(reportedYaw, targetYaw),
          smoothPitch(reportedPitch, targetPitch));
    } else {
      sendSilentRotation(targetYaw, targetPitch);
    }
    towering = true;
  }

  private boolean useTellyTransitionRotation() {
    return telly.getValue() && tellyDoNotAimActive;
  }

  /** Smooth rotation between backwards block aim and telly straight forward look. */
  private void sendTellyTransitionRotation(Object player, float targetYaw, float targetPitch) {
    float reportedYaw = PlayerUpdateHook.lastReportedYaw(player);
    float reportedPitch = PlayerUpdateHook.lastReportedPitch(player);
    float sentYaw = smoothYaw(reportedYaw, targetYaw);
    float sentPitch = smoothPitch(reportedPitch, targetPitch);
    sendSilentRotation(sentYaw, sentPitch);

    float yawRemaining = Math.abs(ScaffoldPlacement.wrapAngle(targetYaw - sentYaw));
    float pitchRemaining = Math.abs(targetPitch - sentPitch);
    if (yawRemaining > 1.0f || pitchRemaining > 1.0f) {
      rotationTick = Math.max(rotationTick, 1);
      tellyRotTransition = true;
    } else if (!tellyDoNotAimActive) {
      tellyRotTransition = false;
    }
  }

  private void sendSilentRotation(float sentYaw, float sentPitch) {
    PlayerUpdateHook.requestRotation(sentYaw, sentPitch);
    lastSentYaw = sentYaw;
    lastSentPitch = sentPitch;
    boolean moveFixEnabled = moveFix.getValue() == MOVEFIX_SILENT;
    float moveFixYaw = moveFixEnabled ? sentYaw : McAccess.getYaw();
    RotationState.applyState(
        true,
        sentYaw,
        sentPitch,
        moveFixYaw,
        moveFixEnabled ? (int) ROTATION_PRIORITY : -1);
  }

  private Object resolvePlacementHit(Object player, ScaffoldPlacement.BlockData blockData,
                                     boolean towerJump, ScaffoldPlacement.AimData aimData) {
    if (blockData == null || player == null)
      return null;
    if (aimData != null && aimData.hitVec != null)
      return aimData.hitVec;
    return validatedPlacementHit(player, blockData.blockPos, blockData.faceOrdinal);
  }

  /** Raycast at the rotation that will be sent with the placement packet (Grim RotationPlace). */
  private Object validatedPlacementHit(Object player, Object blockPos, int faceOrdinal) {
    if (player == null || blockPos == null)
      return null;
    float yaw;
    float pitch;
    if (rotationMode.getValue() == ROT_NONE) {
      yaw = McAccess.getYaw();
      pitch = McAccess.getPitch();
    } else {
      if (lastSentYaw == Float.MIN_VALUE)
        return null;
      yaw = lastSentYaw;
      pitch = lastSentPitch;
    }
    return ScaffoldPlacement.findPlacementHit(player,
        new ScaffoldPlacement.BlockData(blockPos, faceOrdinal), yaw, pitch);
  }

  private boolean canPlaceThisTick(Object player, boolean towerJump, boolean allowTellyAirPlacement) {
    if (towerJump)
      return true;
    if (allowTellyAirPlacement)
      return true;
    if (!telly.getValue() || !isTellyMoving() || isTowering(player))
      return true;
    return !isTellyDoNotAim(player);
  }

  private void place(Object blockPos, int faceOrdinal, Object hitVec) {
    Object player = McAccess.thePlayer();
    if (player != null)
      tryPlace(player, blockPos, faceOrdinal, hitVec);
  }

  private boolean tryPlace(Object player, Object blockPos, int faceOrdinal) {
    return tryPlace(player, blockPos, faceOrdinal, null);
  }

  private boolean tryPlace(Object player, Object blockPos, int faceOrdinal, Object preferredHit) {
    if (blockPos == null || player == null)
      return false;
    flushPendingServerSlot(player);
    Object stack = getPlacementStack(player);
    if (!isPlaceableStack(stack) || McAccess.getStackSize(stack) < 1)
      return false;
    if (placementsThisTick >= maxPlacementsPerTick())
      return false;
    Object world = McAccess.theWorld();
    if (world == null)
      return false;
    if (!ScaffoldPlacement.isValidSupport(world, blockPos))
      return false;
    if (!ScaffoldPlacement.isPlacementTargetClear(world, blockPos, faceOrdinal))
      return false;
    Object hitVec = validatedPlacementHit(player, blockPos, faceOrdinal);
    if (hitVec == null)
      hitVec = preferredHit;
    if (hitVec == null)
      return false;
    Object facing = ScaffoldPlacement.enumFacing(faceOrdinal);
    if (facing == null)
      return false;
    McAccess.clearRightClickDelay();
    ensureClientBlockSlot(player);
    boolean placed = McAccess.onPlayerRightClick(player, world, stack, blockPos, facing, hitVec);
    if (!placed)
      placed = McAccess.sendBlockPlacementPacket(stack, blockPos, facing, hitVec);
    if (placed && swing.getValue())
      McAccess.swingItem();
    if (placed) {
      placementsThisTick++;
      blockCount--;
      Object held = getPlacementStack(player);
      int heldCount = isPlaceableStack(held) ? McAccess.getStackSize(held) : 0;
      blockCount = Math.min(blockCount, heldCount);
      if (telly.getValue())
        tellyAwaitingPlacement = false;
      return true;
    }
    return false;
  }

  private void applySafeWalk(Object player, Object world) {
    boolean enable = safeWalk.getValue() && McAccess.isOnGround()
        && McAccess.getMotionY() <= 0.0 && nearEdge(player, world);
    boolean sneak = enable || McAccess.isSneakKeyHeld();
    forceSneak = enable;
    McAccess.setSneaking(player, sneak);
  }

  private boolean nearEdge(Object player, Object world) {
    Object aabb = McAccess.getEntityBoundingBox(player);
    if (aabb == null || world == null)
      return false;
    int by = floor(McAccess.aabbMinY(aabb) - 0.01);
    int minX = floor(McAccess.aabbMinX(aabb) + McAccess.getMotionX());
    int maxX = floor(McAccess.aabbMaxX(aabb) + McAccess.getMotionX());
    int minZ = floor(McAccess.aabbMinZ(aabb) + McAccess.getMotionZ());
    int maxZ = floor(McAccess.aabbMaxZ(aabb) + McAccess.getMotionZ());
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (!McAccess.isReplaceable(world, x, by, z))
          return false;
      }
    }
    return McAccess.isMovementKeyHeld();
  }

  private void applyTower(Object player, Object world) {
    boolean onGround = McAccess.isOnGround();

    if (!isTowerJumpActive(player)) {
      resetTowerState();
      towerWasOnGround = onGround;
      return;
    }

    // LiquidBounce: per-tick support check — skip motion this tick, not the whole tower session.
    if (!hasTowerSupport(player, world)) {
      towerJumpOffY = Double.NaN;
      towerWasOnGround = onGround;
      return;
    }

    if (towerWasOnGround && !onGround)
      onTowerJump(player);

    towerWasOnGround = onGround;
    if (!onGround)
      towerAirTicks++;
    else
      towerAirTicks = 0;

    switch (tower.getValue()) {
      case TOWER_MOTION:
        applyTowerMotion(player);
        break;
      case TOWER_PULLDOWN:
        applyTowerPulldown(player, world);
        break;
      case TOWER_KARHU:
        applyTowerKarhu(player, world);
        break;
      case TOWER_VULCAN:
        applyTowerVulcan(player);
        break;
      case TOWER_HYPIXEL:
        applyTowerHypixel(player);
        break;
      default:
        break;
    }
  }

  private void onTowerJump(Object player) {
    towerJumpOffY = McAccess.entityPosY(player);
    int mode = tower.getValue();
    if (mode == TOWER_PULLDOWN)
      towerPulldownPending = true;
    if (mode == TOWER_KARHU) {
      towerKarhuTimerActive = true;
      McAccess.setTimerSpeed(towerKarhuTimer.getValue());
      if (towerKarhuPulldown.getValue())
        towerPulldownPending = true;
    }
  }

  private void resetTowerState() {
    if (towerKarhuTimerActive)
      McAccess.resetTimer();
    towerJumpOffY = Double.NaN;
    towerAirTicks = 0;
    towerKarhuTimerActive = false;
    towerPulldownPending = false;
    wasTowerJump = false;
  }

  /** LiquidBounce ScaffoldTowerMotion */
  private void applyTowerMotion(Object player) {
    if (Double.isNaN(towerJumpOffY))
      return;
    double y = McAccess.entityPosY(player);
    if (y <= towerJumpOffY + towerTriggerHeight.getValue())
      return;

    double truncated = Math.floor(y);
    McAccess.setEntityPosition(player, McAccess.entityPosX(player), truncated, McAccess.entityPosZ(player));

    float slow = towerSlow.getValue();
    McAccess.setMotion(
        McAccess.getMotionX() * slow,
        towerMotion.getValue(),
        McAccess.getMotionZ() * slow);
    towerJumpOffY = truncated;
  }

  /** LiquidBounce ScaffoldTowerPulldown */
  private void applyTowerPulldown(Object player, Object world) {
    if (!towerPulldownPending || McAccess.isOnGround())
      return;
    if (McAccess.getMotionY() >= towerPulldownTrigger.getValue())
      return;
    if (!hasTowerSupport(player, world)) {
      towerPulldownPending = false;
      return;
    }
    McAccess.setMotion(McAccess.getMotionX(), -1.0, McAccess.getMotionZ());
    towerPulldownPending = false;
  }

  /** LiquidBounce ScaffoldTowerKarhu */
  private void applyTowerKarhu(Object player, Object world) {
    if (!towerKarhuPulldown.getValue() || !towerPulldownPending || McAccess.isOnGround())
      return;
    if (McAccess.getMotionY() >= towerKarhuTrigger.getValue())
      return;
    if (!hasTowerSupport(player, world)) {
      towerPulldownPending = false;
      return;
    }
    McAccess.setMotion(McAccess.getMotionX(), McAccess.getMotionY() - 1.0, McAccess.getMotionZ());
    towerPulldownPending = false;
  }

  /** LiquidBounce ScaffoldTowerVulcan */
  private void applyTowerVulcan(Object player) {
    Object tickEntity = player;
    int tick = tickEntity == null ? 0 : McAccess.getInt(tickEntity, "field_70173_aa");
    boolean moving = McAccess.isMovementKeyHeld();
    if (tick % 2 == 0) {
      McAccess.setMotion(McAccess.getMotionX(), 0.7, McAccess.getMotionZ());
    } else {
      McAccess.setMotion(McAccess.getMotionX(), moving ? 0.42 : 0.6, McAccess.getMotionZ());
    }
  }

  /** LiquidBounce ScaffoldTowerHypixel */
  private void applyTowerHypixel(Object player) {
    if (!McAccess.isMovementKeyHeld()) {
      double px = McAccess.entityPosX(player);
      if (px % 1.0 != 0.0) {
        double snap = Math.round(px) - px;
        if (snap > 0.281)
          snap = 0.281;
        McAccess.setMotion(snap, McAccess.getMotionY(), McAccess.getMotionZ());
      }
    }

    if (towerAirTicks > 14) {
      McAccess.setMotion(McAccess.getMotionX() * 0.6, McAccess.getMotionY() - 0.09, McAccess.getMotionZ() * 0.6);
      return;
    }

    switch (towerAirTicks % 3) {
      case 0: {
        double speed = 0.247 - ThreadLocalRandom.current().nextFloat() / 100.0;
        double yawRad = Math.toRadians(McAccess.getYaw());
        McAccess.setMotion(-Math.sin(yawRad) * speed, 0.42, Math.cos(yawRad) * speed);
        break;
      }
      case 2:
        McAccess.setMotion(
            McAccess.getMotionX(),
            1.0 - (McAccess.entityPosY(player) % 1.0),
            McAccess.getMotionZ());
        break;
      default:
        break;
    }
  }

  private boolean hasTowerSupport(Object player, Object world) {
    if (player == null || world == null)
      return false;
    Object aabb = McAccess.getEntityBoundingBox(player);
    if (aabb == null)
      return isBlockBelow(player, world);
    int minX = floor(McAccess.aabbMinX(aabb) - 0.5);
    int maxX = floor(McAccess.aabbMaxX(aabb) + 0.5);
    int minZ = floor(McAccess.aabbMinZ(aabb) - 0.5);
    int maxZ = floor(McAccess.aabbMaxZ(aabb) + 0.5);
    int minY = floor(McAccess.aabbMinY(aabb) - 1.05);
    int maxY = floor(McAccess.aabbMinY(aabb) - 0.01);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        for (int y = minY; y <= maxY; y++) {
          if (!McAccess.isReplaceable(world, x, y, z))
            return true;
        }
      }
    }
    return false;
  }

  private boolean isBlockBelow(Object player, Object world) {
    if (player == null || world == null)
      return false;
    int bx = floor(McAccess.entityPosX(player));
    int by = floor(McAccess.entityPosY(player)) - 1;
    int bz = floor(McAccess.entityPosZ(player));
    return !McAccess.isReplaceable(world, bx, by, bz);
  }

  /**
   * LiquidBounce {@code isTowering} — jump held + tower mode + blocks; no manual look or RMB.
   */
  private boolean isTowerJumpActive(Object player) {
    if (player == null || tower.getValue() == TOWER_NONE) {
      wasTowerJump = false;
      return false;
    }
    if (!McAccess.isJumpKeyHeld()) {
      wasTowerJump = false;
      return false;
    }
    if (blockCount <= 0 || !isHoldingPlaceable(player)) {
      wasTowerJump = false;
      return false;
    }
    wasTowerJump = true;
    return true;
  }

  boolean wantsTowerJumpInput() {
    return wasTowerJump && McAccess.isOnGround();
  }

  private boolean isTowering(Object player) {
    if (!isTowerJumpActive(player))
      return false;
    return keepY.getValue() == KEEPY_TELLY && stage > 0 && !telly.getValue()
        || McAccess.isJumpKeyHeld();
  }

  @Override
  public int sendPriority() {
    return 50;
  }

  @Override
  public boolean onSend(Object packet) {
    if (!isEnabled() || packet == null || gnu.client.runtime.packet.PacketUtil.isDispatching())
      return false;
    if (handleHotbarGuard(packet))
      return true;
    return handleVulcanTower(packet);
  }

  /** Cancel foreign C09 hotbar changes while scaffold controls the block slot. */
  private boolean handleHotbarGuard(Object packet) {
    if (!PacketHelper.isHeldItemChange(packet) || sendingServerSlot)
      return false;
    if (blockSlot < 0 || blockSlot > 8 || blockCount <= 0)
      return false;
    Object player = McAccess.thePlayer();
    if (player == null)
      return false;
    guardClientHotbarSlot(player);
    return true;
  }

  private boolean handleVulcanTower(Object packet) {
    if (tower.getValue() != TOWER_VULCAN)
      return false;
    Object player = McAccess.thePlayer();
    Object world = McAccess.theWorld();
    if (player == null || world == null || !isTowerJumpActive(player))
      return false;
    if (!hasTowerSupport(player, world))
      return false;
    if (McAccess.isMovementKeyHeld() || !PacketHelper.c03HasPosition(packet))
      return false;
    int tick = McAccess.getInt(player, "field_70173_aa");
    if (tick % 2 != 0)
      return false;
    double x = PacketHelper.c03PosX(packet);
    double z = PacketHelper.c03PosZ(packet);
    if (Double.isNaN(x) || Double.isNaN(z))
      return false;
    PacketHelper.c03SetPosition(packet, x + 0.1, PacketHelper.c03PosY(packet), z + 0.1);
    return false;
  }

  @Override
  public boolean onReceive(Object packet) {
    return false;
  }

  private float[] applyMotionScale(float forward, float strafe) {
    float speed = McAccess.isOnGround() ? groundMotion.getValue() / 100.0f : airMotion.getValue() / 100.0f;
    if (Math.abs(speed - 1.0f) < 0.001f)
      return new float[] {forward, strafe};
    return new float[] {forward * speed, strafe * speed};
  }

  private void applySprint(Object player) {
    if (shouldEnableTellySprint(player)) {
      McAccess.setSprintKeyState(true);
      McAccess.setClientSprinting(player, true);
      return;
    }
    if (shouldSuppressSprint(player)) {
      McAccess.setSprintKeyState(false);
      McAccess.setClientSprinting(player, false);
    }
  }

  /** Sprint while telly straight: server looks forward and W moves forward on server. */
  private boolean shouldEnableTellySprint(Object player) {
    if (!shouldUseTellySprint() || player == null || !telly.getValue())
      return false;
    if (tellyResetMode.getValue() != TELLY_RESET)
      return false;
    if (!isTellyMoving() || !hasTellyBlocks(player) || isTowering(player))
      return false;
    if (!isTellyDoNotAim(player))
      return false;
    if (forceSneak)
      return false;
    if (safeWalk.getValue() && McAccess.isOnGround() && cycleWorld != null && nearEdge(player, cycleWorld))
      return false;
    return isServerForwardMoving();
  }

  /** Snap and place immediately once telly straight window ends (air or landing). */
  private boolean shouldPrioritizePlacement(Object player, boolean tellyDoNotAim) {
    if (player == null || cycleWorld == null)
      return false;
    if (!telly.getValue() || !tellyStraightJumpActive || tellyDoNotAim)
      return false;
    return tellyAwaitingPlacement || !McAccess.isOnGround();
  }

  /** Allow block placement once the straight window ends — including landing ticks. */
  private boolean shouldAllowTellyAirPlacement(Object player, boolean prioritizePlacement,
                                               boolean tellyDoNotAim) {
    if (prioritizePlacement)
      return true;
    if (!telly.getValue() || player == null || cycleWorld == null)
      return false;
    if (!isTellyMoving() || isTowering(player))
      return false;
    if (!tellyStraightJumpActive)
      return true;
    return !tellyDoNotAim;
  }

  private boolean isServerForwardMoving() {
    if (!isForwardPressed())
      return false;
    if (moveFix.getValue() != MOVEFIX_SILENT || !RotationState.isActived())
      return true;
    if (RotationState.getPriority() != (int) ROTATION_PRIORITY)
      return true;
    if (tellyForwardMoveFixSkip)
      return true;
    return !shouldSuppressSprintForBackwardsServerLook();
  }

  public static boolean shouldSuppressSprintKey() {
    gnu.client.module.Module module = gnu.client.module.ModuleManager.instance().getModule("Scaffold");
    if (!(module instanceof ScaffoldModule) || !module.isEnabled())
      return false;
    return ((ScaffoldModule) module).shouldSuppressSprint(McAccess.thePlayer());
  }

  private boolean shouldSuppressSprint(Object player) {
    if (isTellyResetSprintWindow(player))
      return false;
    if (isTowering(player))
      return false;
    if (shouldSuppressSprintForBackwardsServerLook())
      return true;
    return sprintMode.getValue() == SPRINT_NONE;
  }

  /**
   * Movefix sends a server yaw that can face backwards while the player moves where
   * they look. Sprinting with that server-facing triggers Grim — suppress it.
   */
  private boolean shouldSuppressSprintForBackwardsServerLook() {
    if (moveFix.getValue() != MOVEFIX_SILENT || !RotationState.isActived())
      return false;
    if (RotationState.getPriority() != (int) ROTATION_PRIORITY || !isForwardPressed())
      return false;
    float cameraYaw = McAccess.getYaw();
    float serverYaw = RotationState.getSmoothedYaw();
    return Math.abs(ScaffoldPlacement.wrapAngle(cameraYaw - serverYaw)) > 90.0f;
  }

  private boolean isTellyResetSprintWindow(Object player) {
    if (!telly.getValue() || tellyResetMode.getValue() != TELLY_RESET || player == null)
      return false;
    if (!isTellyMoving() || !hasTellyBlocks(player) || isTowering(player))
      return false;
    return isTellyDoNotAim(player);
  }

  public static void patchMovementInput(Object movInput) {
    if (movInput == null)
      return;
    gnu.client.module.Module module = gnu.client.module.ModuleManager.instance().getModule("Scaffold");
    ScaffoldModule scaffold = module instanceof ScaffoldModule && module.isEnabled()
        ? (ScaffoldModule) module : null;

    boolean sneak = McAccess.getBool(movInput, "field_78899_d") || forceSneak;
    boolean jump = McAccess.getBool(movInput, "field_78901_c") || forceJump || shouldForceImmediateTellyJump();
    if (shouldTowerJumpInput())
      jump = true;
    float forward = McAccess.getFloat(movInput, "field_78900_b");
    float strafe = McAccess.getFloat(movInput, "field_78902_a");
    if (scaffold != null
        && scaffold.moveFix.getValue() == MOVEFIX_SILENT
        && RotationState.isActived()
        && RotationState.getPriority() == (int) ROTATION_PRIORITY
        && !tellyForwardMoveFixSkip
        && isForwardPressed()) {
      // Client look (camera yaw + keys) → server-relative input for silent yaw.
      float[] fixed = fixStrafe(McAccess.getYaw(), RotationState.getSmoothedYaw(), sneak);
      forward = fixed[0];
      strafe = fixed[1];
      if (scaffold.shouldSuppressSprintForBackwardsServerLook()) {
        Object player = McAccess.thePlayer();
        if (player != null) {
          McAccess.setSprintKeyState(false);
          McAccess.setClientSprinting(player, false);
        }
      }
    }
    if (scaffold != null) {
      float[] scaled = scaffold.applyMotionScale(forward, strafe);
      forward = scaled[0];
      strafe = scaled[1];
    }
    McAccess.setBool(movInput, "field_78899_d", sneak);
    McAccess.setBool(movInput, "field_78901_c", jump);
    McAccess.setFloat(movInput, "field_78900_b", forward);
    McAccess.setFloat(movInput, "field_78902_a", strafe);
  }

  /** OpenMyau {@code MoveUtil.fixStrafe} — 8-direction input, ±1 only (Grim-safe). */
  private static float[] fixStrafe(float cameraYaw, float serverYaw, boolean sneak) {
    int forwardKey = forwardKeyValue();
    int strafeKey = leftKeyValue();
    if (forwardKey == 0 && strafeKey == 0)
      return new float[] {0.0f, 0.0f};
    float angle = ScaffoldPlacement.wrapAngle(
        adjustYaw(cameraYaw, forwardKey, strafeKey) - serverYaw + 22.5f);
    float forward;
    float strafe;
    switch (((int) (angle + 180.0f) / 45) % 8) {
      case 0: forward = -1.0f; strafe = 0.0f; break;
      case 1: forward = -1.0f; strafe = 1.0f; break;
      case 2: forward = 0.0f; strafe = 1.0f; break;
      case 3: forward = 1.0f; strafe = 1.0f; break;
      case 4: forward = 1.0f; strafe = 0.0f; break;
      case 5: forward = 1.0f; strafe = -1.0f; break;
      case 6: forward = 0.0f; strafe = -1.0f; break;
      case 7: forward = -1.0f; strafe = -1.0f; break;
      default: forward = 0.0f; strafe = 0.0f; break;
    }
    if (sneak) {
      forward *= 0.3f;
      strafe *= 0.3f;
    }
    return new float[] {forward, strafe};
  }

  private static boolean isForwardPressed() {
    return McAccess.isForwardKeyHeld() != McAccess.isBackKeyHeld()
        || McAccess.isLeftKeyHeld() != McAccess.isRightKeyHeld();
  }

  private static int forwardKeyValue() {
    int value = 0;
    if (McAccess.isForwardKeyHeld())
      value++;
    if (McAccess.isBackKeyHeld())
      value--;
    return value;
  }

  private static int leftKeyValue() {
    int value = 0;
    if (McAccess.isLeftKeyHeld())
      value++;
    if (McAccess.isRightKeyHeld())
      value--;
    return value;
  }

  private static boolean shouldTowerJumpInput() {
    gnu.client.module.Module module = gnu.client.module.ModuleManager.instance().getModule("Scaffold");
    if (!(module instanceof ScaffoldModule) || !module.isEnabled())
      return false;
    return ((ScaffoldModule) module).wantsTowerJumpInput();
  }

  private static boolean shouldForceImmediateTellyJump() {
    gnu.client.module.Module module = gnu.client.module.ModuleManager.instance().getModule("Scaffold");
    if (!(module instanceof ScaffoldModule) || !module.isEnabled())
      return false;
    return ((ScaffoldModule) module).shouldForceImmediateTellyJump(McAccess.thePlayer());
  }

  private boolean shouldForceImmediateTellyJump(Object player) {
    if (!telly.getValue() || tellyJumpTicks() != 0 || player == null)
      return false;
    if (!McAccess.isOnGround() || !isTellyMoving() || isTowering(player))
      return false;
    if (!hasTellyBlocks(player))
      return false;

    forceJump = true;
    tellyTicksUntilJump = 0;
    tellyStraightJumpActive = true;
    tellyAwaitingPlacement = false;
    return true;
  }

  private boolean shouldUseTellySprint() {
    if (sprintMode.getValue() == SPRINT_VANILLA)
      return true;
    Module sprint = gnu.client.module.ModuleManager.instance().getModule("Sprint");
    return sprint != null && sprint.isEnabled();
  }

  private boolean isHoldingPlaceable(Object player) {
    return isPlaceableStack(getPlacementStack(player));
  }

  private boolean hasTellyBlocks(Object player) {
    return blockCount > 0 || isHoldingPlaceable(player);
  }

  private boolean isPlaceableStack(Object stack) {
    if (stack == null || McAccess.getStackSize(stack) < 1)
      return false;
    return McAccess.getBlockFromItemStack(stack) != null;
  }

  private float currentMoveYaw(Object player) {
    return adjustYaw(McAccess.getYaw(), forwardValue(), leftValue());
  }

  private static float adjustYaw(float yaw, float forward, float strafe) {
    if (forward < 0.0f)
      yaw += 180.0f;
    if (strafe != 0.0f) {
      float multiplier = forward == 0.0f ? 1.0f : 0.5f * Math.signum(forward);
      yaw += -90.0f * multiplier * Math.signum(strafe);
    }
    return ScaffoldPlacement.wrapAngle(yaw);
  }

  private int forwardValue() {
    int value = 0;
    if (McAccess.isForwardKeyHeld())
      value++;
    if (McAccess.isBackKeyHeld())
      value--;
    return value;
  }

  private int leftValue() {
    int value = 0;
    if (McAccess.isLeftKeyHeld())
      value++;
    if (McAccess.isRightKeyHeld())
      value--;
    return value;
  }

  private boolean isTellyMoving() {
    return forwardValue() != 0 || leftValue() != 0;
  }

  private int tellyStraightTicks() {
    return Math.max(0, Math.round(tellyStraightTicks.getValue()));
  }

  private int tellyJumpTicks() {
    return Math.max(0, Math.round(tellyJumpTicks.getValue()));
  }

  private float smoothYaw(float current, float target) {
    return ScaffoldPlacement.quantize(current + cappedDelta(ScaffoldPlacement.wrapAngle(target - current)));
  }

  private float smoothPitch(float current, float target) {
    float pitch = current + cappedDelta(target - current);
    return ScaffoldPlacement.quantize(ScaffoldPlacement.clampPitch(pitch));
  }

  private float cappedDelta(float delta) {
    float maxStep = Math.max(1.0f, rotationSpeed.getValue());
    if (Math.abs(delta) <= maxStep)
      return delta;
    return Math.signum(delta) * maxStep;
  }

  private boolean isDiagonal(float yaw) {
    float absYaw = Math.abs(yaw % 90.0f);
    return absYaw > 20.0f && absYaw < 70.0f;
  }

  private static float wrapTo(float angle, float target) {
    return target + ScaffoldPlacement.wrapAngle(angle - target);
  }

  private static int floor(double v) {
    int i = (int) v;
    return v < i ? i - 1 : i;
  }
}

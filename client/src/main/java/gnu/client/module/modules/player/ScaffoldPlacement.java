package gnu.client.module.modules.player;

import gnu.client.runtime.mc.McAccess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OpenMyau-style scaffold placement helpers, adapted to reflection-only 1.8.9.
 */
final class ScaffoldPlacement {

  // EnumFacing ordinals (1.8.9): 0=DOWN, 1=UP, 2=NORTH, 3=SOUTH, 4=WEST, 5=EAST
  static final int FACE_DOWN = 0;
  static final int FACE_UP = 1;
  static final int FACE_NORTH = 2;
  static final int FACE_SOUTH = 3;
  static final int FACE_WEST = 4;
  static final int FACE_EAST = 5;

  private static final int[] PLACE_FACES = {FACE_UP, FACE_NORTH, FACE_SOUTH, FACE_WEST, FACE_EAST};
  private static final double[] PLACE_OFFSETS = {
      0.03125, 0.09375, 0.15625, 0.21875,
      0.28125, 0.34375, 0.40625, 0.46875,
      0.53125, 0.59375, 0.65625, 0.71875,
      0.78125, 0.84375, 0.90625, 0.96875
  };

  private ScaffoldPlacement() {}

  static final class BlockData {
    final Object blockPos;
    final int faceOrdinal;

    BlockData(Object blockPos, int faceOrdinal) {
      this.blockPos = blockPos;
      this.faceOrdinal = faceOrdinal;
    }
  }

  static final class AimData {
    final Object hitVec;
    final float yaw;
    final float pitch;
    final boolean raytraceExact;

    AimData(Object hitVec, float yaw, float pitch, boolean raytraceExact) {
      this.hitVec = hitVec;
      this.yaw = yaw;
      this.pitch = pitch;
      this.raytraceExact = raytraceExact;
    }
  }

  static BlockData getBlockData(Object player, Object world, int startY, int stage, boolean shouldKeepY) {
    if (player == null || world == null)
      return null;

    int playerY = floor(McAccess.entityPosY(player));
    int targetX = floor(McAccess.entityPosX(player));
    int targetY = (stage != 0 && !shouldKeepY ? Math.min(playerY, startY) : playerY) - 1;
    int targetZ = floor(McAccess.entityPosZ(player));
    if (!McAccess.isReplaceable(world, targetX, targetY, targetZ))
      return null;

    List<int[]> positions = new ArrayList<>();
    double reach = McAccess.getBlockReachDistance();
    for (int x = -4; x <= 4; x++) {
      for (int y = -4; y <= 0; y++) {
        for (int z = -4; z <= 4; z++) {
          int bx = targetX + x;
          int by = targetY + y;
          int bz = targetZ + z;
          if (McAccess.isReplaceable(world, bx, by, bz))
            continue;
          if (isInteractable(world, bx, by, bz))
            continue;
          if (distanceTo(player, bx + 0.5, by + 0.5, bz + 0.5) > reach)
            continue;
          if (stage != 0 && !shouldKeepY && by >= startY)
            continue;
          for (int face : PLACE_FACES) {
            int[] adj = offset(bx, by, bz, face);
            if (McAccess.isReplaceable(world, adj[0], adj[1], adj[2])) {
              positions.add(new int[] {bx, by, bz});
              break;
            }
          }
        }
      }
    }

    if (positions.isEmpty())
      return null;
    positions.sort(Comparator.comparingDouble(pos ->
        distSq(pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
            targetX + 0.5, targetY + 0.5, targetZ + 0.5)));

    int[] best = positions.get(0);
    int face = bestFacing(best[0], best[1], best[2], targetX, targetY, targetZ);
    Object pos = blockPos(best[0], best[1], best[2]);
    return pos == null || face < 0 ? null : new BlockData(pos, face);
  }

  static AimData findAimData(BlockData data, float baseYaw, float basePitch) {
    if (data == null || data.blockPos == null)
      return null;
    Object player = McAccess.thePlayer();
    if (player == null)
      return null;

    int bx = blockPosX(data.blockPos);
    int by = blockPosY(data.blockPos);
    int bz = blockPosZ(data.blockPos);
    if (bx == Integer.MIN_VALUE)
      return null;

    double[] xs = PLACE_OFFSETS;
    double[] ys = PLACE_OFFSETS;
    double[] zs = PLACE_OFFSETS;
    switch (data.faceOrdinal) {
      case FACE_NORTH: zs = new double[] {0.0}; break;
      case FACE_SOUTH: zs = new double[] {1.0}; break;
      case FACE_WEST: xs = new double[] {0.0}; break;
      case FACE_EAST: xs = new double[] {1.0}; break;
      case FACE_DOWN: ys = new double[] {0.0}; break;
      case FACE_UP: ys = new double[] {1.0}; break;
      default: break;
    }

    AimData best = null;
    float bestDiff = Float.MAX_VALUE;
    for (double dx : xs) {
      for (double dy : ys) {
        for (double dz : zs) {
          Object vec = vec3(bx + dx, by + dy, bz + dz);
          float[] rotations = rotationsTo(vec, player, baseYaw, basePitch);
          float aimYaw = quantize(rotations[0]);
          float aimPitch = quantize(clampPitch(rotations[1]));
          Object hit = hitVecForRotation(data.blockPos, data.faceOrdinal, aimYaw, aimPitch);
          if (hit == null)
            continue;
          float diff = Math.abs(wrapAngle(aimYaw - baseYaw)) + Math.abs(aimPitch - basePitch);
          if (best == null || diff < bestDiff) {
            best = new AimData(hit, aimYaw, aimPitch, true);
            bestDiff = diff;
          }
        }
      }
    }

    return best;
  }

  /** Try several yaw bases — telly air often needs movement/camera yaw, not backwards preset. */
  static AimData findAimData(BlockData data, Object player, float[] baseYaws, float basePitch) {
    if (data == null || baseYaws == null)
      return null;
    AimData best = null;
    float bestDiff = Float.MAX_VALUE;
    for (float baseYaw : baseYaws) {
      AimData candidate = findAimData(data, baseYaw, basePitch);
      if (candidate == null)
        continue;
      float diff = Math.abs(wrapAngle(candidate.yaw - baseYaw)) + Math.abs(candidate.pitch - basePitch);
      if (best == null || diff < bestDiff) {
        best = candidate;
        bestDiff = diff;
      }
    }
    return best;
  }

  static Object hitVecForRotation(Object blockPos, int faceOrdinal, float yaw, float pitch) {
    if (blockPos == null)
      return null;
    Object mop = McAccess.raycastBlocks(McAccess.getBlockReachDistance(), yaw, pitch);
    if (!matchesRaycast(mop, blockPos, faceOrdinal))
      return null;
    return McAccess.getObject(mop, "field_72307_f");
  }

  /**
   * Resolve a placement hit vec for the rotation actually sent this tick.
   * Returns null when the look vector does not hit the planned block face — callers
   * must skip placement instead of falling back to face-center (Grim RotationPlace).
   */
  static Object findPlacementHit(Object player, BlockData data, float yaw, float pitch) {
    if (player == null || data == null)
      return null;
    return hitVecForRotation(data.blockPos, data.faceOrdinal, yaw, pitch);
  }

  /** Grim PositionPlace — eye must be on the correct side of the clicked face. */
  static boolean isFaceVisible(Object player, Object blockPos, int faceOrdinal) {
    if (player == null || blockPos == null)
      return false;
    int bx = blockPosX(blockPos);
    int by = blockPosY(blockPos);
    int bz = blockPosZ(blockPos);
    if (bx == Integer.MIN_VALUE)
      return false;

    double ex = McAccess.entityPosX(player);
    double ey = McAccess.entityPosY(player) + eyeHeight(player);
    double ez = McAccess.entityPosZ(player);
    double margin = 0.03;
    switch (faceOrdinal) {
      case FACE_NORTH: return ez < bz + margin;
      case FACE_SOUTH: return ez > bz + 1.0 - margin;
      case FACE_WEST: return ex < bx + margin;
      case FACE_EAST: return ex > bx + 1.0 - margin;
      case FACE_UP: return ey > by + 1.0 - margin;
      case FACE_DOWN: return ey < by + margin;
      default: return false;
    }
  }

  /** Grim AirLiquidPlace — the cell receiving the new block must be replaceable. */
  static boolean isPlacementTargetClear(Object world, Object blockPos, int faceOrdinal) {
    if (world == null || blockPos == null)
      return false;
    int x = blockPosX(blockPos);
    int y = blockPosY(blockPos);
    int z = blockPosZ(blockPos);
    if (x == Integer.MIN_VALUE)
      return false;
    int[] dest = offset(x, y, z, faceOrdinal);
    return McAccess.isReplaceable(world, dest[0], dest[1], dest[2]);
  }

  /** Grim AirLiquidPlace — support block must be solid on the client world. */
  static boolean isValidSupport(Object world, Object blockPos) {
    if (world == null || blockPos == null)
      return false;
    int x = blockPosX(blockPos);
    int y = blockPosY(blockPos);
    int z = blockPosZ(blockPos);
    if (x == Integer.MIN_VALUE)
      return false;
    return !McAccess.isReplaceable(world, x, y, z);
  }

  static Object faceHitVec(int bx, int by, int bz, int faceOrdinal) {
    double x = bx + 0.5;
    double y = by + 0.5;
    double z = bz + 0.5;
    switch (faceOrdinal) {
      case 0: y = by; break;
      case 1: y = by + 1.0; break;
      case 2: z = bz; break;
      case 3: z = bz + 1.0; break;
      case 4: x = bx; break;
      case 5: x = bx + 1.0; break;
      default: break;
    }
    return vec3(x, y, z);
  }

  static Object getClickVec(Object blockPos, int faceOrdinal) {
    int bx = blockPosX(blockPos);
    int by = blockPosY(blockPos);
    int bz = blockPosZ(blockPos);
    if (bx == Integer.MIN_VALUE)
      return null;
    return faceHitVec(bx, by, bz, faceOrdinal);
  }

  static Object enumFacing(int ordinal) {
    Class<?> facingCls = McAccess.gameClass("net.minecraft.util.EnumFacing");
    if (facingCls == null)
      return null;
    Object[] values = facingCls.getEnumConstants();
    if (values == null || ordinal < 0 || ordinal >= values.length)
      return null;
    return values[ordinal];
  }

  static boolean matchesRaycast(Object mop, Object expectedPos, int expectedFace) {
    return matchesRaycast(mop, expectedPos, expectedFace, Double.NEGATIVE_INFINITY);
  }

  static boolean matchesRaycast(Object mop, Object expectedPos, int expectedFace, double minPlacementY) {
    if (mop == null || expectedPos == null)
      return false;
    if (!isBlockHit(mop))
      return false;
    Object hitPos = McAccess.invoke(mop, "func_178782_a", new Class<?>[0]);
    if (hitPos == null || !hitPos.equals(expectedPos))
      return false;
    Object sideHit = McAccess.getObject(mop, "field_178784_b");
    if (sideHit == null)
      return false;
    if (facingOrdinal(sideHit) != expectedFace)
      return false;
    Object hit = McAccess.getObject(mop, "field_72307_f");
    return hit == null || hitY(hit) + 1.0E-5 >= minPlacementY;
  }

  static boolean matchesRaycastBlock(Object mop, Object expectedPos) {
    if (mop == null || expectedPos == null)
      return false;
    if (!isBlockHit(mop))
      return false;
    Object hitPos = McAccess.invoke(mop, "func_178782_a", new Class<?>[0]);
    return hitPos != null && hitPos.equals(expectedPos);
  }

  private static boolean isBlockHit(Object mop) {
    Object type = McAccess.getObject(mop, "field_72313_a");
    if (type == null)
      return false;
    return "BLOCK".equals(String.valueOf(type));
  }

  private static int facingOrdinal(Object facing) {
    if (facing instanceof Enum<?>)
      return ((Enum<?>) facing).ordinal();
    return McAccess.getInt(facing, "ordinal");
  }

  static int[] offset(int x, int y, int z, int faceOrdinal) {
    switch (faceOrdinal) {
      case 0: return new int[] {x, y - 1, z};
      case 1: return new int[] {x, y + 1, z};
      case 2: return new int[] {x, y, z - 1};
      case 3: return new int[] {x, y, z + 1};
      case 4: return new int[] {x - 1, y, z};
      case 5: return new int[] {x + 1, y, z};
      default: return new int[] {x, y, z};
    }
  }

  private static int offX(int faceOrdinal) {
    return faceOrdinal == FACE_WEST ? -1 : faceOrdinal == FACE_EAST ? 1 : 0;
  }

  private static int offY(int faceOrdinal) {
    return faceOrdinal == FACE_DOWN ? -1 : faceOrdinal == FACE_UP ? 1 : 0;
  }

  private static int offZ(int faceOrdinal) {
    return faceOrdinal == FACE_NORTH ? -1 : faceOrdinal == FACE_SOUTH ? 1 : 0;
  }

  private static Object blockPos(int x, int y, int z) {
    return McAccess.newInstance("net.minecraft.util.BlockPos",
        new Class<?>[] {int.class, int.class, int.class}, x, y, z);
  }

  private static Object vec3(double x, double y, double z) {
    return McAccess.newInstance("net.minecraft.util.Vec3",
        new Class<?>[] {double.class, double.class, double.class}, x, y, z);
  }

  private static double distanceTo(Object player, double x, double y, double z) {
    double dx = McAccess.entityPosX(player) - x;
    double dy = McAccess.entityPosY(player) + eyeHeight(player) - y;
    double dz = McAccess.entityPosZ(player) - z;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  private static double distSq(double x1, double y1, double z1, double x2, double y2, double z2) {
    double dx = x1 - x2;
    double dy = y1 - y2;
    double dz = z1 - z2;
    return dx * dx + dy * dy + dz * dz;
  }

  private static float[] rotationsTo(Object hitVec, Object player) {
    return rotationsTo(hitVec, player, McAccess.getYaw(), McAccess.getPitch());
  }

  private static float[] rotationsTo(Object hitVec, Object player, float baseYaw, float basePitch) {
    double hx = hitX(hitVec);
    double hy = hitY(hitVec);
    double hz = hitZ(hitVec);
    double eyeY = McAccess.entityPosY(player) + eyeHeight(player);
    double dx = hx - McAccess.entityPosX(player);
    double dz = hz - McAccess.entityPosZ(player);
    double dy = hy - eyeY;
    double horiz = Math.sqrt(dx * dx + dz * dz);
    float targetYaw = (float) (Math.atan2(dz, dx) * 57.2957795) - 90.0f;
    float targetPitch = (float) (-(Math.atan2(dy, horiz) * 57.2957795));
    float yaw = quantize(baseYaw + wrapAngle(targetYaw - baseYaw));
    float pitch = quantize(basePitch + wrapAngle(targetPitch - basePitch));
    return new float[] {yaw, clampPitch(pitch)};
  }

  private static double hitX(Object vec) {
    return McAccess.getDouble(vec, "field_72450_a");
  }

  private static double hitY(Object vec) {
    return McAccess.getDouble(vec, "field_72448_b");
  }

  private static double hitZ(Object vec) {
    return McAccess.getDouble(vec, "field_72449_c");
  }

  private static double eyeHeight(Object player) {
    Object eye = McAccess.invoke(player, "func_70047_e", new Class<?>[0]);
    return eye instanceof Float ? (Float) eye : 1.62;
  }

  static float wrapAngle(float angle) {
    Class<?> math = McAccess.gameClass("net.minecraft.util.MathHelper");
    if (math == null)
      return angle;
    Object result = McAccess.invokeStatic(math, "func_76142_g", new Class<?>[] {float.class}, angle);
    return result instanceof Float ? (Float) result : angle;
  }

  static float clampPitch(float pitch) {
    return Math.max(-90.0f, Math.min(90.0f, pitch));
  }

  static float quantize(float angle) {
    double gcd = McAccess.getMouseSensitivityGcd();
    if (gcd <= 0.0)
      gcd = 0.0096;
    return (float) (angle - angle % gcd);
  }

  private static int bestFacing(int bx, int by, int bz, int tx, int ty, int tz) {
    int bestFace = -1;
    double bestDist = Double.MAX_VALUE;
    for (int face : PLACE_FACES) {
      int[] adj = offset(bx, by, bz, face);
      if (adj[1] > ty)
        continue;
      double dist = distSq(adj[0] + 0.5, adj[1] + 0.5, adj[2] + 0.5,
          tx + 0.5, ty + 0.5, tz + 0.5);
      if (bestFace < 0 || dist < bestDist || (dist == bestDist && face == FACE_UP)) {
        bestDist = dist;
        bestFace = face;
      }
    }
    return bestFace;
  }

  private static boolean isInteractable(Object world, int x, int y, int z) {
    Object block = McAccess.getBlockFromState(McAccess.getBlockState(world, x, y, z));
    if (block == null)
      return false;
    String cls = block.getClass().getName();
    return cls.contains("Container") || cls.contains("Workbench") || cls.contains("Anvil")
        || cls.contains("Bed") || cls.contains("Door") || cls.contains("TrapDoor")
        || cls.contains("Fence") || cls.contains("Button") || cls.contains("Lever")
        || cls.contains("Jukebox");
  }

  static int blockPosX(Object pos) {
    Object x = McAccess.invoke(pos, "func_177958_n", new Class<?>[0]);
    if (x == null)
      x = McAccess.invokeNamed(pos, "getX", new Class<?>[0]);
    return x instanceof Integer ? (Integer) x : Integer.MIN_VALUE;
  }

  static int blockPosY(Object pos) {
    Object y = McAccess.invoke(pos, "func_177956_o", new Class<?>[0]);
    if (y == null)
      y = McAccess.invokeNamed(pos, "getY", new Class<?>[0]);
    return y instanceof Integer ? (Integer) y : Integer.MIN_VALUE;
  }

  static int blockPosZ(Object pos) {
    Object z = McAccess.invoke(pos, "func_177952_p", new Class<?>[0]);
    if (z == null)
      z = McAccess.invokeNamed(pos, "getZ", new Class<?>[0]);
    return z instanceof Integer ? (Integer) z : Integer.MIN_VALUE;
  }

  private static int floor(double v) {
    int i = (int) v;
    return v < i ? i - 1 : i;
  }
}

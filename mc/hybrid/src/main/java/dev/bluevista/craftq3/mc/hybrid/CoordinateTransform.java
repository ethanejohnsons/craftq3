package dev.bluevista.craftq3.mc.hybrid;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * The single mapping between Quake III coordinates (right handed, +Z up, units) and Minecraft
 * coordinates (+Y up, blocks): {@code MC = origin + (q.x, q.z, -q.y) / unitsPerBlock}. This is a
 * rotation with uniform scale, so it preserves orientation.
 *
 * <ul>
 *   <li>Positions use the origin; directions and displacements ({@code toQuakeDirection}) do not.
 *   <li>Unit normals use the rotation only and stay unit length; never convert a normal as a scaled
 *       displacement.
 *   <li>Axis-aligned boxes reorder their endpoints on the flipped axis (Minecraft Z is Quake -Y).
 *   <li>Yaw: Quake yaw 0 faces +X and 90 faces +Y; Minecraft yaw 0 faces +Z (south) and 90 faces
 *       -X. Pitch is positive looking down in both.
 * </ul>
 *
 * Every method is allocation-free except the {@link Vec3f} conveniences. Instances are immutable.
 */
public final class CoordinateTransform {
  /** The interop scale both modes use unless configured otherwise. */
  public static final double DEFAULT_UNITS_PER_BLOCK = 32;

  /** Minecraft server ticks per second, for velocity conversion. */
  public static final double TICKS_PER_SECOND = 20;

  private final double unitsPerBlock;
  private final double blocksPerUnit;
  private final double originX;
  private final double originY;
  private final double originZ;

  /**
   * @param unitsPerBlock Quake units per Minecraft block, finite and in [1, 4096]
   * @param originX the Minecraft position of the Quake origin
   */
  public CoordinateTransform(double unitsPerBlock, double originX, double originY, double originZ) {
    if (!Double.isFinite(unitsPerBlock) || unitsPerBlock < 1 || unitsPerBlock > 4096)
      throw new IllegalArgumentException("Units per block must be finite and in [1, 4096]");
    if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ))
      throw new IllegalArgumentException("Origin must be finite");
    this.unitsPerBlock = unitsPerBlock;
    this.blocksPerUnit = 1 / unitsPerBlock;
    this.originX = originX;
    this.originY = originY;
    this.originZ = originZ;
  }

  /** The default scale anchored at a Minecraft block corner. */
  public static CoordinateTransform at(int blockX, int blockY, int blockZ) {
    return new CoordinateTransform(DEFAULT_UNITS_PER_BLOCK, blockX, blockY, blockZ);
  }

  public double unitsPerBlock() {
    return unitsPerBlock;
  }

  public double originX() {
    return originX;
  }

  public double originY() {
    return originY;
  }

  public double originZ() {
    return originZ;
  }

  /** True when the origin is a block corner, so block faces map to exact multiples of the scale. */
  public boolean gridAligned() {
    return originX == Math.rint(originX)
        && originY == Math.rint(originY)
        && originZ == Math.rint(originZ);
  }

  // ---- positions, componentwise -------------------------------------------------------------

  public float quakeX(double minecraftX) {
    return (float) ((minecraftX - originX) * unitsPerBlock);
  }

  public float quakeY(double minecraftZ) {
    return (float) ((originZ - minecraftZ) * unitsPerBlock);
  }

  public float quakeZ(double minecraftY) {
    return (float) ((minecraftY - originY) * unitsPerBlock);
  }

  public double minecraftX(double quakeX) {
    return originX + quakeX * blocksPerUnit;
  }

  public double minecraftY(double quakeZ) {
    return originY + quakeZ * blocksPerUnit;
  }

  public double minecraftZ(double quakeY) {
    return originZ - quakeY * blocksPerUnit;
  }

  // ---- positions, vectors -------------------------------------------------------------------

  /** Writes the Quake position of a Minecraft position into {@code out[0..2]}. */
  public void toQuake(double x, double y, double z, float[] out) {
    out[0] = quakeX(x);
    out[1] = quakeY(z);
    out[2] = quakeZ(y);
  }

  /** Writes the Minecraft position of a Quake position into {@code out[0..2]}. */
  public void toMinecraft(double qx, double qy, double qz, double[] out) {
    out[0] = minecraftX(qx);
    out[1] = minecraftY(qz);
    out[2] = minecraftZ(qy);
  }

  public Vec3f toQuake(double x, double y, double z) {
    return new Vec3f(quakeX(x), quakeY(z), quakeZ(y));
  }

  /** The Minecraft position of {@code q} as {@code {x, y, z}}. */
  public double[] toMinecraft(Vec3f q) {
    double[] out = new double[3];
    toMinecraft(q.x(), q.y(), q.z(), out);
    return out;
  }

  // ---- directions, displacements, velocities ------------------------------------------------

  /** A Minecraft displacement (blocks) as a Quake displacement (units), without translation. */
  public void toQuakeDirection(double dx, double dy, double dz, float[] out) {
    out[0] = (float) (dx * unitsPerBlock);
    out[1] = (float) (0 - dz * unitsPerBlock);
    out[2] = (float) (dy * unitsPerBlock);
  }

  /** A Quake displacement (units) as a Minecraft displacement (blocks), without translation. */
  public void toMinecraftDirection(double qx, double qy, double qz, double[] out) {
    out[0] = qx * blocksPerUnit;
    out[1] = qz * blocksPerUnit;
    out[2] = 0 - qy * blocksPerUnit;
  }

  /** Minecraft velocity in blocks per tick as Quake velocity in units per second. */
  public void velocityToQuake(double dx, double dy, double dz, float[] out) {
    toQuakeDirection(dx * TICKS_PER_SECOND, dy * TICKS_PER_SECOND, dz * TICKS_PER_SECOND, out);
  }

  /** Quake velocity in units per second as Minecraft velocity in blocks per tick. */
  public void velocityToMinecraft(double qx, double qy, double qz, double[] out) {
    toMinecraftDirection(qx / TICKS_PER_SECOND, qy / TICKS_PER_SECOND, qz / TICKS_PER_SECOND, out);
  }

  // ---- normals (rotation only) ---------------------------------------------------------------

  /** A Quake unit normal as a Minecraft unit normal: rotation only, renormalised. */
  public static void normalToMinecraft(double nx, double ny, double nz, double[] out) {
    double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
    double scale = length == 0 ? 0 : 1 / length;
    out[0] = nx * scale;
    out[1] = nz * scale;
    out[2] = 0 - ny * scale;
  }

  /** A Minecraft unit normal as a Quake unit normal: rotation only, renormalised. */
  public static void normalToQuake(double nx, double ny, double nz, float[] out) {
    double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
    double scale = length == 0 ? 0 : 1 / length;
    out[0] = (float) (nx * scale);
    out[1] = (float) (0 - nz * scale);
    out[2] = (float) (ny * scale);
  }

  // ---- boxes ---------------------------------------------------------------------------------

  /**
   * A Minecraft box as a Quake box: {@code mins} and {@code maxs} receive the componentwise minimum
   * and maximum, so the flipped axis is reordered.
   */
  public void boxToQuake(
      double minX,
      double minY,
      double minZ,
      double maxX,
      double maxY,
      double maxZ,
      float[] mins,
      float[] maxs) {
    float ax = quakeX(minX);
    float bx = quakeX(maxX);
    float ay = quakeY(minZ);
    float by = quakeY(maxZ);
    float az = quakeZ(minY);
    float bz = quakeZ(maxY);
    mins[0] = Math.min(ax, bx);
    maxs[0] = Math.max(ax, bx);
    mins[1] = Math.min(ay, by);
    maxs[1] = Math.max(ay, by);
    mins[2] = Math.min(az, bz);
    maxs[2] = Math.max(az, bz);
  }

  /**
   * A Quake box as a Minecraft box, written as {@code {minX, minY, minZ, maxX, maxY, maxZ}} into
   * {@code out}.
   */
  public void boxToMinecraft(float[] mins, float[] maxs, double[] out) {
    double ax = minecraftX(mins[0]);
    double bx = minecraftX(maxs[0]);
    double ay = minecraftY(mins[2]);
    double by = minecraftY(maxs[2]);
    double az = minecraftZ(mins[1]);
    double bz = minecraftZ(maxs[1]);
    out[0] = Math.min(ax, bx);
    out[1] = Math.min(ay, by);
    out[2] = Math.min(az, bz);
    out[3] = Math.max(ax, bx);
    out[4] = Math.max(ay, by);
    out[5] = Math.max(az, bz);
  }

  // ---- angles --------------------------------------------------------------------------------

  /** Minecraft yaw (degrees) facing the same way as Quake yaw {@code quakeYaw}, in (-180, 180]. */
  public static float minecraftYaw(float quakeYaw) {
    return wrapDegrees(-quakeYaw - 90);
  }

  /** Quake yaw (degrees) facing the same way as Minecraft yaw {@code minecraftYaw}. */
  public static float quakeYaw(float minecraftYaw) {
    return wrapDegrees(-minecraftYaw - 90);
  }

  /** Pitch is positive looking down in both games. */
  public static float minecraftPitch(float quakePitch) {
    return wrapDegrees(quakePitch);
  }

  public static float quakePitch(float minecraftPitch) {
    return wrapDegrees(minecraftPitch);
  }

  /** Wraps an angle into (-180, 180]. */
  public static float wrapDegrees(float degrees) {
    float wrapped = degrees % 360;
    if (wrapped > 180) wrapped -= 360;
    if (wrapped <= -180) wrapped += 360;
    return wrapped;
  }

  /** The same mapping anchored elsewhere. */
  public CoordinateTransform withOrigin(double x, double y, double z) {
    return new CoordinateTransform(unitsPerBlock, x, y, z);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CoordinateTransform t
        && t.unitsPerBlock == unitsPerBlock
        && t.originX == originX
        && t.originY == originY
        && t.originZ == originZ;
  }

  @Override
  public int hashCode() {
    return Double.hashCode(unitsPerBlock) * 31
        + Double.hashCode(originX) * 17
        + Double.hashCode(originY) * 7
        + Double.hashCode(originZ);
  }

  @Override
  public String toString() {
    return "CoordinateTransform["
        + unitsPerBlock
        + " units/block, origin "
        + originX
        + ", "
        + originY
        + ", "
        + originZ
        + "]";
  }
}

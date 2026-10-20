package dev.bluevista.craftq3.mc.fabric.bridge;

/**
 * A bridged player's Quake body in Minecraft blocks, relative to its feet: the qagame's {@code
 * r.mins}/{@code r.maxs} (standing or crouched, alive or dead) and its view height. While set on an
 * entity ({@link BridgeAvatar}), Minecraft's bounding box, width, height and eye height queries
 * report this body, so mob melee reach, projectiles, explosions and line of sight all see the Quake
 * hull.
 *
 * @param minX box minimum along Minecraft X relative to the feet position
 * @param eyeHeight eye height above the feet
 */
public record AvatarShape(
    double minX, double minZ, double maxX, double maxZ, double height, double eyeHeight) {
  public AvatarShape {
    if (!(maxX > minX && maxZ > minZ && height > 0)
        || Math.abs(minX) > 16
        || Math.abs(maxX) > 16
        || Math.abs(minZ) > 16
        || Math.abs(maxZ) > 16
        || height > 16
        || !Double.isFinite(eyeHeight)
        || Math.abs(eyeHeight) > 16) throw new IllegalArgumentException("Invalid avatar shape");
  }

  public float width() {
    return (float) Math.max(maxX - minX, maxZ - minZ);
  }

  /**
   * The shape of a Quake body {@code mins..maxs} around {@code origin} (Quake units, Z up), with
   * the given view height, at {@code unitsPerBlock}.
   */
  public static AvatarShape ofQuake(
      float[] mins, float[] maxs, float viewHeight, double unitsPerBlock) {
    double s = 1 / unitsPerBlock;
    // Minecraft X = Quake X, Minecraft Z = -Quake Y, feet at Quake mins.z.
    return new AvatarShape(
        mins[0] * s,
        -maxs[1] * s,
        maxs[0] * s,
        -mins[1] * s,
        (maxs[2] - mins[2]) * s,
        (viewHeight - mins[2]) * s);
  }
}

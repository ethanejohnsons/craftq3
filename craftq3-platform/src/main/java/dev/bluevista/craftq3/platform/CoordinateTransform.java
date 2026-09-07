package dev.bluevista.craftq3.platform;

import dev.bluevista.craftq3.core.math.Vec3;

/** Q3/canonical is right handed, Z up. Minecraft mapping is (x,z,-y), plus host origin. */
public record CoordinateTransform(double quakeUnitsPerBlock, Vec3 minecraftOrigin) {
  public CoordinateTransform {
    if (!Double.isFinite(quakeUnitsPerBlock)
        || quakeUnitsPerBlock <= 0
        || minecraftOrigin == null) {
      throw new IllegalArgumentException("Positive finite scale and origin required");
    }
  }

  public Vec3 toMinecraft(Vec3 q3) {
    return directionToMinecraft(q3).add(minecraftOrigin);
  }

  public Vec3 directionToMinecraft(Vec3 q3) {
    return new Vec3(q3.x(), q3.z(), -q3.y()).scale(1 / quakeUnitsPerBlock);
  }

  public Vec3 toQuake(Vec3 minecraft) {
    Vec3 v = minecraft.add(minecraftOrigin.scale(-1)).scale(quakeUnitsPerBlock);
    return new Vec3(v.x(), -v.z(), v.y());
  }
}

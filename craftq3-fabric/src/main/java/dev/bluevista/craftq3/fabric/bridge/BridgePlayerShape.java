package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;

/** Immutable original-QVM hull in Minecraft axes, relative to the native avatar's feet. */
public record BridgePlayerShape(BspMap.Bounds bounds, double eyeHeight) {
  public BridgePlayerShape {
    if (bounds == null || !Double.isFinite(eyeHeight) || Math.abs(eyeHeight) > 64)
      throw new IllegalArgumentException("Invalid bridge player shape");
    var min = bounds.min();
    var max = bounds.max();
    if (max.x() <= min.x()
        || max.y() <= min.y()
        || max.z() <= min.z()
        || max.x() - min.x() > 64
        || max.y() - min.y() > 64
        || max.z() - min.z() > 64
        || Math.abs(min.x()) > 64
        || Math.abs(max.x()) > 64
        || Math.abs(min.z()) > 64
        || Math.abs(max.z()) > 64
        || min.y() != 0) throw new IllegalArgumentException("Invalid bridge player hull");
  }

  public static BridgePlayerShape from(
      BspMap.Bounds hull, Vec3 origin, int viewHeight, double scale) {
    if (!Double.isFinite(scale) || scale <= 0)
      throw new IllegalArgumentException("Invalid bridge scale");
    var min = hull.min();
    var max = hull.max();
    return new BridgePlayerShape(
        new BspMap.Bounds(
            new Vec3((min.x() - origin.x()) / scale, 0, (origin.y() - max.y()) / scale),
            new Vec3(
                (max.x() - origin.x()) / scale,
                (max.z() - min.z()) / scale,
                (origin.y() - min.y()) / scale)),
        (origin.z() + viewHeight - min.z()) / scale);
  }

  public float width() {
    return (float)
        Math.max(bounds.max().x() - bounds.min().x(), bounds.max().z() - bounds.min().z());
  }

  public float height() {
    return (float) (bounds.max().y() - bounds.min().y());
  }
}

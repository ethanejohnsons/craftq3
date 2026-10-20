package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * A yaw/pitch camera in Q3's Z-up convention: positive pitch looks down, the field of view is
 * horizontal in degrees. Rolled or mirrored views use {@link ViewBasis} instead.
 */
public record Camera(Vec3f origin, float yaw, float pitch, float horizontalFov) {
  public Camera {
    if (origin == null
        || !origin.isFinite()
        || !Float.isFinite(yaw)
        || !Float.isFinite(pitch)
        || !Float.isFinite(horizontalFov)
        || horizontalFov <= 1
        || horizontalFov >= 179) throw new IllegalArgumentException("Invalid camera");
  }

  public ViewBasis basis() {
    return ViewBasis.of(this);
  }
}

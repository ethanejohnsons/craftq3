package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/** Direction-to-angle helpers shared by the travel executors ({@code Vector2Angles}). */
final class ViewAngles {
  private ViewAngles() {}

  /** Pitch (negated, as Quake III stores it), yaw and zero roll that look along {@code dir}. */
  static Vec3f of(Vec3f direction) {
    float x = direction.x(), y = direction.y(), z = direction.z();
    float yaw, pitch;
    if (x == 0 && y == 0) {
      yaw = 0;
      pitch = z > 0 ? 90 : 270;
    } else {
      yaw = (float) (Math.atan2(y, x) * 180 / Math.PI);
      if (yaw < 0) yaw += 360;
      float horizontal = (float) Math.sqrt(x * x + y * y);
      pitch = (float) (Math.atan2(z, horizontal) * 180 / Math.PI);
      if (pitch < 0) pitch += 360;
    }
    return new Vec3f(-pitch, yaw, 0);
  }

  /** Signed difference of two angles in degrees, wrapped into (-180, 180] ({@code AngleDiff}). */
  static float difference(float first, float second) {
    float difference = first - second;
    if (first > second) {
      if (difference > 180) difference -= 360;
    } else if (difference < -180) difference += 360;
    return difference;
  }
}

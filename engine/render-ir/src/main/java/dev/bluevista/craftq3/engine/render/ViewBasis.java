package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * View axes with {@code right} pointing to the screen's right (Q3 refdefs carry a left axis).
 * Reflected bases keep their handedness, so a mirror view has {@code (right x forward) . up < 0}.
 */
public record ViewBasis(Vec3f forward, Vec3f right, Vec3f up) {
  public ViewBasis {
    Objects.requireNonNull(forward, "forward");
    Objects.requireNonNull(right, "right");
    Objects.requireNonNull(up, "up");
  }

  /** The axes of a yaw/pitch camera. */
  public static ViewBasis of(Camera camera) {
    double yaw = Math.toRadians(camera.yaw());
    double pitch = Math.toRadians(camera.pitch());
    Vec3f forward =
        new Vec3f(
            (float) (Math.cos(yaw) * Math.cos(pitch)),
            (float) (Math.sin(yaw) * Math.cos(pitch)),
            (float) -Math.sin(pitch));
    Vec3f right = new Vec3f((float) Math.sin(yaw), (float) -Math.cos(yaw), 0);
    return new ViewBasis(forward, right, right.cross(forward));
  }

  /** From Q3's forward, left and up axes. */
  public static ViewBasis fromQuake(Vec3f forward, Vec3f left, Vec3f up) {
    return new ViewBasis(forward, left.negate(), up);
  }

  /** Q3's left axis. */
  public Vec3f left() {
    return right.negate();
  }

  /** True when the basis is reflected (left-handed in screen terms). */
  public boolean mirrored() {
    return right.cross(forward).dot(up) < 0;
  }
}

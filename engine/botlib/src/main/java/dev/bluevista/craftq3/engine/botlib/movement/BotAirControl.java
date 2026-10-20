package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * Native-observed airborne steering toward a target ({@code BotAirControl}), without collision or
 * state mutation. The fall is projected in 0.1-second steps for at most five seconds; a target the
 * bot cannot reach in that time is a failure, which callers answer by heading straight for it.
 */
public final class BotAirControl {
  /** The original projects fifty 0.1-second steps. */
  public static final int MAX_STEPS = 50;

  /** The {@code sv_gravity} default. */
  public static final float DEFAULT_GRAVITY = 800;

  /** Raw speed can reach 416; the elementary-action consumer owns its 400-unit clamp. */
  public record Result(Vec3f direction, float speed, boolean success) {
    public static final Result FAILED = new Result(Vec3f.ZERO, 400, false);

    public Result {
      Objects.requireNonNull(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 416)
        throw new IllegalArgumentException("Invalid air control speed");
    }
  }

  private BotAirControl() {}

  public static Result control(Vec3f origin, Vec3f velocity, Vec3f target) {
    return control(origin, velocity, target, DEFAULT_GRAVITY);
  }

  public static Result control(Vec3f origin, Vec3f velocity, Vec3f target, float gravity) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(velocity);
    Objects.requireNonNull(target);
    float x = origin.x(), y = origin.y(), z = origin.z();
    // The observed scale rounds after multiplication by the double decimal constant.
    float dx = (float) (velocity.x() * .1);
    float dy = (float) (velocity.y() * .1);
    float dz = (float) (velocity.z() * .1);
    for (int step = 0; step < MAX_STEPS; step++) {
      dz = (float) (dz - gravity * .01);
      float nx = x + dx, ny = y + dy, nz = z + dz;
      if (!Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz)) return Result.FAILED;
      if (dz < 0 && nz <= target.z()) {
        float fraction = (target.z() - z) / dz;
        float rx = target.x() - (x + dx * fraction);
        float ry = target.y() - (y + dy * fraction);
        float rz = target.z() - (z + dz * fraction);
        // Preserve the small vertical remainder introduced by final-step interpolation.
        float squared = rx * rx + ry * ry + rz * rz;
        float root = (float) Math.sqrt(squared);
        if (!Float.isFinite(root)) return Result.FAILED;
        float inverse = root == 0 ? 0 : 1 / root;
        float distance = squared * inverse;
        float speed = 400 - (400 - Math.min(distance, 32) * 13);
        return new Result(new Vec3f(rx * inverse, ry * inverse, rz * inverse), speed, true);
      }
      x = nx;
      y = ny;
      z = nz;
    }
    return Result.FAILED;
  }

  /**
   * The callers' fallback when steering fails: straight at {@code target} horizontally, normalized,
   * at full speed.
   */
  static Result straight(Vec3f origin, Vec3f target) {
    Vec3f delta = target.subtract(origin);
    return new Result(new Vec3f(delta.x(), delta.y(), 0).normalize(), 400, false);
  }
}

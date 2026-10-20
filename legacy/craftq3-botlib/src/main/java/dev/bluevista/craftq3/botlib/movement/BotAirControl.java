package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;

/** Native-observed airborne steering toward a target, without collision or state mutation. */
public final class BotAirControl {
  private static final int MAX_STEPS = 4096;

  /** Raw speed can reach 416; the elementary-action consumer owns its 400-unit clamp. */
  public record Result(Vec3 direction, float speed, boolean success) {
    public Result {
      direction = MovementAbi.vector(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 416)
        throw new IllegalArgumentException("Invalid air control speed");
    }
  }

  private BotAirControl() {}

  public static Result control(Vec3 origin, Vec3 velocity, Vec3 target) {
    origin = MovementAbi.vector(origin);
    velocity = MovementAbi.vector(velocity);
    target = MovementAbi.vector(target);
    float x = (float) origin.x(), y = (float) origin.y(), z = (float) origin.z();
    // The observed scale rounds after multiplication by the double decimal constant.
    float dx = (float) ((float) velocity.x() * .1);
    float dy = (float) ((float) velocity.y() * .1);
    float dz = (float) ((float) velocity.z() * .1);
    for (int step = 0; step < MAX_STEPS; step++) {
      dz -= 8;
      float nx = x + dx, ny = y + dy, nz = z + dz;
      if (!Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz))
        throw new IllegalArgumentException("Air control arithmetic exceeds float range");
      if (dz < 0 && nz <= (float) target.z()) {
        float fraction = ((float) target.z() - z) / dz;
        float rx = (float) target.x() - (x + dx * fraction);
        float ry = (float) target.y() - (y + dy * fraction);
        float rz = (float) target.z() - (z + dz * fraction);
        // Preserve the small vertical remainder introduced by final-step interpolation.
        float squared = rx * rx + ry * ry + rz * rz;
        float root = (float) Math.sqrt(squared);
        if (!Float.isFinite(root))
          throw new IllegalArgumentException("Air control direction exceeds float range");
        float inverse = root == 0 ? 0 : 1 / root;
        float distance = squared * inverse;
        float speed = 400 - (400 - Math.min(distance, 32) * 13);
        return new Result(new Vec3(rx * inverse, ry * inverse, rz * inverse), speed, true);
      }
      x = nx;
      y = ny;
      z = nz;
    }
    throw new IllegalStateException("Air control work budget exhausted");
  }
}

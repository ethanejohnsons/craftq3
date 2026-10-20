package dev.bluevista.craftq3.engine.audio;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * Where the sounds are heard from: the entity the view belongs to, its origin and its axis ({@code
 * forward}, {@code left}, {@code up}, as the cgame's view axis), and whether the view is under
 * water. Sounds started on {@link #entity()} are played unattenuated.
 */
public record Listener(
    int entity, Vec3f origin, Vec3f forward, Vec3f left, Vec3f up, boolean inWater) {
  /** Entity 0 at the world origin looking down +x, as before the first respatialisation. */
  public static final Listener DEFAULT =
      new Listener(
          0, Vec3f.ZERO, new Vec3f(1, 0, 0), new Vec3f(0, 1, 0), new Vec3f(0, 0, 1), false);

  public Listener {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(forward, "forward");
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(up, "up");
  }
}

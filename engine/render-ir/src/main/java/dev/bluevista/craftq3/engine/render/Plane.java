package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/** A plane {@code normal . p = distance}; points with nonnegative distance are in front. */
public record Plane(Vec3f normal, float distance) {
  public Plane {
    Objects.requireNonNull(normal, "normal");
  }

  public float signedDistance(Vec3f point) {
    return normal.dot(point) - distance;
  }

  public float signedDistance(float x, float y, float z) {
    return normal.x() * x + normal.y() * y + normal.z() * z - distance;
  }
}

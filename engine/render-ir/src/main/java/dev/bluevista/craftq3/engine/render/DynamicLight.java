package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/** A cgame dynamic light ({@code trap_R_AddLightToScene}): colour 0..1, radius in units. */
public record DynamicLight(Vec3f origin, float radius, Vec3f color) {
  public DynamicLight {
    Objects.requireNonNull(origin, "origin");
    Objects.requireNonNull(color, "color");
    if (!origin.isFinite()
        || !Float.isFinite(radius)
        || radius <= 0
        || !(color.x() >= 0 && color.x() <= 1)
        || !(color.y() >= 0 && color.y() <= 1)
        || !(color.z() >= 0 && color.z() <= 1))
      throw new IllegalArgumentException("Invalid dynamic light");
  }
}

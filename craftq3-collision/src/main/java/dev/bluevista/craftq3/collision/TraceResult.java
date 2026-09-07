package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;

/** Fraction is measured along the requested origin segment, with a small contact separation. */
public record TraceResult(
    double fraction, Vec3 endPosition, boolean startSolid, boolean allSolid, Optional<Hit> hit) {
  public TraceResult {
    Objects.requireNonNull(endPosition, "endPosition");
    Objects.requireNonNull(hit, "hit");
    if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1 || (allSolid && !startSolid))
      throw new IllegalArgumentException("Invalid collision result");
  }

  public record Plane(Vec3 normal, double distance) {
    public static final Plane NONE = new Plane(CollisionMath.ZERO, 0);

    public Plane {
      Objects.requireNonNull(normal, "normal");
      if (!Double.isFinite(distance))
        throw new IllegalArgumentException("Non-finite plane distance");
    }

    public double signedDistance(Vec3 point) {
      return CollisionMath.dot(normal, point) - distance;
    }
  }

  /**
   * Negative brush/side values identify synthetic boxes or patch triangles; face identifies
   * patches.
   */
  public record Hit(
      Plane plane,
      int contents,
      int surfaceFlags,
      int entity,
      int model,
      int brush,
      int side,
      int face,
      String shaderName) {
    public Hit {
      Objects.requireNonNull(plane, "plane");
      Objects.requireNonNull(shaderName, "shaderName");
    }
  }

  public static TraceResult clear(TraceRequest request) {
    return new TraceResult(1, request.end(), false, false, Optional.empty());
  }

  public boolean blocked() {
    return fraction < 1 || allSolid;
  }
}

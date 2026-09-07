package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;

/** A linearly swept, world-axis-aligned box. Mins/maxs are offsets from the moving origin. */
public record TraceRequest(
    Vec3 start, Vec3 end, Vec3 mins, Vec3 maxs, int contentsMask, int ignoreEntity) {
  public TraceRequest {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    Objects.requireNonNull(mins, "mins");
    Objects.requireNonNull(maxs, "maxs");
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException("Trace box mins exceed maxs");
    // Fixed coordinate limits keep all dot products, sweeps and interpolation finite.
    if (CollisionMath.maxAbs(start) > 1e12
        || CollisionMath.maxAbs(end) > 1e12
        || CollisionMath.maxAbs(mins) > 1e9
        || CollisionMath.maxAbs(maxs) > 1e9)
      throw new IllegalArgumentException("Trace coordinates exceed supported numeric range");
  }

  public static TraceRequest ray(Vec3 start, Vec3 end, int mask) {
    return new TraceRequest(start, end, CollisionMath.ZERO, CollisionMath.ZERO, mask, -1);
  }

  public static TraceRequest box(Vec3 start, Vec3 end, Vec3 mins, Vec3 maxs, int mask) {
    return new TraceRequest(start, end, mins, maxs, mask, -1);
  }

  public boolean isPoint() {
    return mins.equals(maxs);
  }

  public TraceRequest ignoring(int entity) {
    return new TraceRequest(start, end, mins, maxs, contentsMask, entity);
  }
}

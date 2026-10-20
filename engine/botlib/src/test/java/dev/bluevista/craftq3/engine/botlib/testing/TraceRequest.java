package dev.bluevista.craftq3.engine.botlib.testing;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * Test support: one recorded {@code BotCollision.trace} call. Fakes record these so oracle tests
 * can assert the exact hull, mask and ignored entity botlib asked for.
 */
public record TraceRequest(
    Vec3f start, Vec3f end, Vec3f mins, Vec3f maxs, int contentsMask, int ignoreEntity) {
  public TraceRequest {
    Objects.requireNonNull(start);
    Objects.requireNonNull(end);
    Objects.requireNonNull(mins);
    Objects.requireNonNull(maxs);
  }

  public static TraceRequest ray(Vec3f start, Vec3f end, int mask) {
    return new TraceRequest(start, end, Vec3f.ZERO, Vec3f.ZERO, mask, -1);
  }

  public static TraceRequest box(Vec3f start, Vec3f end, Vec3f mins, Vec3f maxs, int mask) {
    return new TraceRequest(start, end, mins, maxs, mask, -1);
  }

  public boolean isPoint() {
    return mins.equals(maxs);
  }

  public TraceRequest ignoring(int entity) {
    return new TraceRequest(start, end, mins, maxs, contentsMask, entity);
  }
}

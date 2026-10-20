package dev.bluevista.craftq3.engine.botlib.testing;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Optional;

/**
 * Test support: one axis-aligned solid box with contents, swept against with a slab test. Enough
 * for mask and hit/miss assertions; it does not model Quake III's contact epsilon.
 */
public final class BoxTraceWorld implements TraceWorld {
  private final Vec3f mins, maxs;
  private final int contents, surfaceFlags, entity;

  public BoxTraceWorld(Vec3f mins, Vec3f maxs, int contents, int surfaceFlags, int entity) {
    this.mins = mins;
    this.maxs = maxs;
    this.contents = contents;
    this.surfaceFlags = surfaceFlags;
    this.entity = entity;
  }

  @Override
  public TraceResult trace(TraceRequest request) {
    if ((request.contentsMask() & contents) == 0) return TraceResult.clear(request);
    float[] low = {
      mins.x() - request.maxs().x(), mins.y() - request.maxs().y(), mins.z() - request.maxs().z()
    };
    float[] high = {
      maxs.x() - request.mins().x(), maxs.y() - request.mins().y(), maxs.z() - request.mins().z()
    };
    float[] from = request.start().toArray(), to = request.end().toArray();
    double enter = 0, leave = 1;
    int axisHit = -1;
    boolean inside = true;
    for (int axis = 0; axis < 3; axis++) {
      double d = to[axis] - from[axis];
      if (from[axis] < low[axis] || from[axis] > high[axis]) inside = false;
      if (d == 0) {
        if (from[axis] < low[axis] || from[axis] > high[axis]) return TraceResult.clear(request);
        continue;
      }
      double t0 = (low[axis] - from[axis]) / d, t1 = (high[axis] - from[axis]) / d;
      if (t0 > t1) {
        double swap = t0;
        t0 = t1;
        t1 = swap;
      }
      if (t0 > enter) {
        enter = t0;
        axisHit = axis;
      }
      leave = Math.min(leave, t1);
      if (enter > leave) return TraceResult.clear(request);
    }
    var hit =
        Optional.of(
            new TraceResult.Hit(
                TraceResult.Plane.NONE, contents, surfaceFlags, entity, -1, -1, -1, -1, ""));
    if (inside) return new TraceResult(0, request.start(), true, false, hit);
    if (axisHit < 0) return TraceResult.clear(request);
    var end =
        new Vec3f(
            (float) (from[0] + (to[0] - from[0]) * enter),
            (float) (from[1] + (to[1] - from[1]) * enter),
            (float) (from[2] + (to[2] - from[2]) * enter));
    return new TraceResult(enter, end, false, false, hit);
  }

  @Override
  public int pointContents(Vec3f point, int contentsMask, int ignoreEntity) {
    boolean in =
        point.x() >= mins.x()
            && point.x() <= maxs.x()
            && point.y() >= mins.y()
            && point.y() <= maxs.y()
            && point.z() >= mins.z()
            && point.z() <= maxs.z();
    return in ? contents & contentsMask : 0;
  }
}

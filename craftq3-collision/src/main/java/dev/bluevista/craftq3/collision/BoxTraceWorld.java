package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;

/** An axis-aligned linked entity or host obstacle; bounds are absolute Q3 world coordinates. */
public final class BoxTraceWorld implements TraceWorld {
  private final ConvexTrace.Shape shape;

  public BoxTraceWorld(Vec3 mins, Vec3 maxs, int contents, int surfaceFlags, int entity) {
    new TraceRequest(mins, maxs, CollisionMath.ZERO, CollisionMath.ZERO, contents, -1);
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException("Invalid collision box bounds");
    var sides = new ArrayList<ConvexTrace.Side>();
    double[] lo = {mins.x(), mins.y(), mins.z()}, hi = {maxs.x(), maxs.y(), maxs.z()};
    for (int axis = 0; axis < 3; axis++) {
      sides.add(
          new ConvexTrace.Side(
              new TraceResult.Plane(CollisionMath.AXES[axis].scale(-1), -lo[axis]),
              surfaceFlags,
              axis * 2,
              ""));
      sides.add(
          new ConvexTrace.Side(
              new TraceResult.Plane(CollisionMath.AXES[axis], hi[axis]),
              surfaceFlags,
              axis * 2 + 1,
              ""));
    }
    shape = new ConvexTrace.Shape(sides, new ConvexTrace.Metadata(contents, entity, -1, -1, -1));
  }

  public static BoxTraceWorld at(
      Vec3 origin, Vec3 mins, Vec3 maxs, int contents, int surfaceFlags, int entity) {
    return new BoxTraceWorld(origin.add(mins), origin.add(maxs), contents, surfaceFlags, entity);
  }

  @Override
  public TraceResult trace(TraceRequest request) {
    return ConvexTrace.trace(shape, request);
  }

  @Override
  public int pointContents(Vec3 point, int mask, int ignoreEntity) {
    return ConvexTrace.contents(shape, point, mask, ignoreEntity);
  }
}

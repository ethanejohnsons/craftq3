package dev.bluevista.craftq3.engine.collision;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * A trace described as a value, for callers off the hot path (tools, tests, the hybrid layer). Hot
 * paths call {@link CollisionModel#boxTrace} with float arrays and a reused {@link Trace}.
 *
 * @param start where the box's origin starts
 * @param end where it would end
 * @param mins box minimum relative to the origin; equal to {@code maxs} for a point
 * @param maxs box maximum relative to the origin
 * @param model a model handle: {@link CollisionModel#WORLD_MODEL}, an inline model or a temporary
 *     box
 * @param contentMask contents that block the trace
 * @param capsule sweep the capsule fitted to the box instead of the box
 * @param origin model origin for a transformed trace, or null
 * @param angles model pitch, yaw and roll in degrees for a transformed trace, or null
 */
public record TraceRequest(
    Vec3f start,
    Vec3f end,
    Vec3f mins,
    Vec3f maxs,
    int model,
    int contentMask,
    boolean capsule,
    Vec3f origin,
    Vec3f angles) {
  public TraceRequest {
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    Objects.requireNonNull(mins, "mins");
    Objects.requireNonNull(maxs, "maxs");
    if (!start.isFinite() || !end.isFinite() || !mins.isFinite() || !maxs.isFinite())
      throw new IllegalArgumentException("Trace coordinates must be finite");
    if ((origin == null) != (angles == null))
      throw new IllegalArgumentException("A transformed trace needs both origin and angles");
  }

  public static TraceRequest point(Vec3f start, Vec3f end, int contentMask) {
    return box(start, end, Vec3f.ZERO, Vec3f.ZERO, contentMask);
  }

  public static TraceRequest box(Vec3f start, Vec3f end, Vec3f mins, Vec3f maxs, int contentMask) {
    return new TraceRequest(
        start, end, mins, maxs, CollisionModel.WORLD_MODEL, contentMask, false, null, null);
  }

  public TraceRequest withModel(int handle) {
    return new TraceRequest(start, end, mins, maxs, handle, contentMask, capsule, origin, angles);
  }

  public TraceRequest asCapsule() {
    return new TraceRequest(start, end, mins, maxs, model, contentMask, true, origin, angles);
  }

  public TraceRequest transformed(Vec3f modelOrigin, Vec3f modelAngles) {
    return new TraceRequest(
        start, end, mins, maxs, model, contentMask, capsule, modelOrigin, modelAngles);
  }

  public boolean isTransformed() {
    return origin != null;
  }
}

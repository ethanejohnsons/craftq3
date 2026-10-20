package dev.bluevista.craftq3.engine.botlib.testing;

import dev.bluevista.craftq3.engine.botlib.BspTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.Optional;

/**
 * Test support: the shape of a scripted trace answer, converted to botlib's {@link BspTrace}. A
 * result without a hit reports {@link BspTrace#ENTITY_NONE}.
 */
public record TraceResult(
    double fraction, Vec3f endPosition, boolean startSolid, boolean allSolid, Optional<Hit> hit) {
  public TraceResult {
    Objects.requireNonNull(endPosition);
    Objects.requireNonNull(hit);
  }

  public record Plane(Vec3f normal, double distance) {
    public static final Plane NONE = new Plane(Vec3f.ZERO, 0);
  }

  public record Hit(
      Plane plane,
      int contents,
      int surfaceFlags,
      int entity,
      int model,
      int brush,
      int side,
      int face,
      String shaderName) {}

  public static TraceResult clear(TraceRequest request) {
    return new TraceResult(1, request.end(), false, false, Optional.empty());
  }

  public BspTrace toBsp() {
    var plane = hit.map(Hit::plane).orElse(Plane.NONE);
    return new BspTrace(
        allSolid,
        startSolid,
        (float) fraction,
        endPosition,
        plane.normal(),
        (float) plane.distance(),
        0,
        hit.map(Hit::side).orElse(0),
        hit.map(Hit::shaderName).orElse(""),
        hit.map(Hit::surfaceFlags).orElse(0),
        0,
        hit.map(Hit::contents).orElse(0),
        hit.map(Hit::entity).orElse(BspTrace.ENTITY_NONE));
  }
}

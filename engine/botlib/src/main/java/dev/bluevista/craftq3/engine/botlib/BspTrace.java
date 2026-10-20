package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * The result of a world trace as botlib sees it, the original {@code bsp_trace_t}: solid flags,
 * fraction, end position, the hit plane, the expansion distance and brush side, the hit surface and
 * its contents, and the hit entity. {@link #entity} is {@link #ENTITY_NONE} when nothing was hit
 * and {@link #ENTITY_WORLD} for world geometry.
 */
public record BspTrace(
    boolean allSolid,
    boolean startSolid,
    float fraction,
    Vec3f endPosition,
    Vec3f planeNormal,
    float planeDistance,
    float expansionDistance,
    int sideNumber,
    String surfaceName,
    int surfaceFlags,
    int surfaceValue,
    int contents,
    int entity) {
  public static final int ENTITY_WORLD = 1022;
  public static final int ENTITY_NONE = 1023;

  public BspTrace {
    Objects.requireNonNull(endPosition);
    Objects.requireNonNull(planeNormal);
    Objects.requireNonNull(surfaceName);
  }

  /** An unobstructed trace that reached {@code end}. */
  public static BspTrace clear(Vec3f end) {
    return new BspTrace(false, false, 1, end, Vec3f.ZERO, 0, 0, 0, "", 0, 0, 0, ENTITY_NONE);
  }

  /** A trace stopped at {@code fraction} by {@code entity} on a plane with {@code normal}. */
  public static BspTrace hit(float fraction, Vec3f end, Vec3f normal, int contents, int entity) {
    return new BspTrace(false, false, fraction, end, normal, 0, 0, 0, "", 0, 0, contents, entity);
  }

  /** True when the trace hit something other than the world or nothing, like a mover or player. */
  public boolean hitEntity() {
    return entity != ENTITY_NONE && entity != ENTITY_WORLD;
  }
}

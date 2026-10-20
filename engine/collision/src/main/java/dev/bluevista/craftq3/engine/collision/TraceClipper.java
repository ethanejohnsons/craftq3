package dev.bluevista.craftq3.engine.collision;

/**
 * The view of a running world trace that a {@link CollisionSource} clips its solids into. Clipping
 * merges exactly as the model's own brushes do, so foreign solids and map brushes compete for the
 * nearest hit on equal terms. Valid only during {@link CollisionSource#clip(TraceClipper)}.
 */
public interface TraceClipper {
  /** Lower corner of the swept volume on {@code axis}, for broad-phase culling. */
  float sweptMin(int axis);

  /** Upper corner of the swept volume on {@code axis}. */
  float sweptMax(int axis);

  int contentMask();

  /** The nearest hit so far; solids farther along cannot change the result. */
  float fraction();

  /** True for a stationary query (start equals end), which tests overlap instead of sweeping. */
  boolean positionTest();

  /** True when the swept shape is a point. */
  boolean isPoint();

  /**
   * Clips against an axis-aligned solid box in world coordinates. Boxes whose contents miss the
   * trace's mask are ignored.
   */
  void clipBox(
      float minX,
      float minY,
      float minZ,
      float maxX,
      float maxY,
      float maxZ,
      int contents,
      int surfaceFlags);
}

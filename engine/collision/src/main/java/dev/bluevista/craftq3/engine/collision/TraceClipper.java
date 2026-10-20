package dev.bluevista.craftq3.engine.collision;

/**
 * The view of a running world trace that a {@link CollisionSource} clips its solids into. Clipping
 * merges exactly as the model's own brushes do, so foreign solids and map brushes compete for the
 * nearest hit on equal terms. Valid only during {@link CollisionSource#clip(TraceClipper)}.
 *
 * <p>The trace is described as the engine runs it ({@code CM_Trace}): a box centred on the moving
 * point, so {@link #mins} is {@code -maxs} on every axis. The box swept from {@link #start} to
 * {@link #end} covers exactly the volume the caller asked for ({@code start + mins .. start + maxs}
 * at the start, the same offsets at the end), so a source can visit only what the swept box touches
 * instead of everything inside {@linkplain #sweptMin the swept bounds}. For a capsule trace the box
 * is the capsule's bounding box. All values are world coordinates.
 */
public interface TraceClipper {
  /** Lower corner of the swept volume on {@code axis}, for broad-phase culling. */
  float sweptMin(int axis);

  /** Upper corner of the swept volume on {@code axis}. */
  float sweptMax(int axis);

  /** Where the centre of the swept box starts on {@code axis}. */
  float start(int axis);

  /**
   * Where the centre of the swept box would end; equal to the start for a {@link #positionTest}.
   */
  float end(int axis);

  /** The swept box's lower corner relative to its centre ({@code -maxs(axis)}; 0 for a point). */
  float mins(int axis);

  /** The swept box's upper corner relative to its centre (at least 0). */
  float maxs(int axis);

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

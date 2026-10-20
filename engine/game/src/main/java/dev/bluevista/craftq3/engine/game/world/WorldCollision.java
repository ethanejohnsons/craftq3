package dev.bluevista.craftq3.engine.game.world;

/**
 * The clip models the server traces against, as the original collision model ({@code CM_*}) offers
 * them: the world and its inline models, and the temporary box or capsule models built for entity
 * bounds. Vectors are {@code float[3]}; implementations write into the caller's {@link GameTrace}
 * and must not retain any argument. Owned by the simulation thread.
 *
 * <p>The default implementation adapts {@code engine:collision}; a map source may compose extra
 * world geometry into it (see {@link CollisionLayers}).
 */
public interface WorldCollision {
  /** Inline models including the world (model 0). */
  int modelCount();

  /** {@code CM_ModelBounds}. */
  void modelBounds(int model, float[] mins, float[] maxs);

  /**
   * {@code CM_TransformedBoxTrace} against inline model {@code model} (0 is the world, traced
   * untransformed as {@code CM_BoxTrace}). Fills every field of {@code out} except {@code
   * entityNum}.
   */
  void traceModel(
      GameTrace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int model,
      int contentMask,
      float[] origin,
      float[] angles,
      boolean capsule);

  /**
   * {@code CM_TransformedBoxTrace} against {@code CM_TempBoxModel(boxMins, boxMaxs, boxCapsule)}
   * placed at {@code origin}. The temporary model's brush has {@code CONTENTS_BODY}, so a mask
   * without that bit never hits it, and a hit reports {@code CONTENTS_BODY}.
   */
  void traceTempBox(
      GameTrace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      float[] boxMins,
      float[] boxMaxs,
      boolean boxCapsule,
      int contentMask,
      float[] origin,
      boolean capsule);

  /** {@code CM_TransformedPointContents} for inline model {@code model} (0: the world). */
  int pointContents(float[] point, int model, float[] origin, float[] angles);

  /** {@code CM_TransformedPointContents} for a temporary box or capsule model. */
  int tempBoxPointContents(
      float[] point, float[] boxMins, float[] boxMaxs, boolean capsule, float[] origin);
}

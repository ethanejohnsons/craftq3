package dev.bluevista.craftq3.engine.game.world;

import java.util.List;
import java.util.Objects;

/**
 * Composes extra world geometry into a base {@link WorldCollision}: every {@link Layer} takes part
 * in world (model 0) traces and point contents, as if its brushes belonged to the map. This is the
 * hook a map source uses to add geometry the BSP does not contain (for example the Minecraft
 * bridge's terrain); inline models and temporary boxes are untouched.
 *
 * <p>Results merge as the original merges brush traces within one model: solid flags accumulate and
 * the shortest fraction wins.
 */
public final class CollisionLayers implements WorldCollision {
  /** Extra world geometry. Implementations follow the {@link WorldCollision} conventions. */
  public interface Layer {
    /** Traces the layer, filling every field of {@code out} except {@code entityNum}. */
    void trace(
        GameTrace out,
        float[] start,
        float[] end,
        float[] mins,
        float[] maxs,
        int contentMask,
        boolean capsule);

    int pointContents(float[] point);
  }

  private final WorldCollision base;
  private final Layer[] layers;
  private final GameTrace scratch = new GameTrace();

  public CollisionLayers(WorldCollision base, List<Layer> layers) {
    this.base = Objects.requireNonNull(base);
    this.layers = layers.toArray(Layer[]::new);
  }

  /** {@code base} itself when there are no layers. */
  public static WorldCollision compose(WorldCollision base, List<Layer> layers) {
    return layers.isEmpty() ? base : new CollisionLayers(base, layers);
  }

  @Override
  public int modelCount() {
    return base.modelCount();
  }

  @Override
  public void modelBounds(int model, float[] mins, float[] maxs) {
    base.modelBounds(model, mins, maxs);
  }

  @Override
  public void traceModel(
      GameTrace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int model,
      int contentMask,
      float[] origin,
      float[] angles,
      boolean capsule) {
    base.traceModel(out, start, end, mins, maxs, model, contentMask, origin, angles, capsule);
    if (model != 0) return;
    for (Layer layer : layers) {
      layer.trace(scratch, start, end, mins, maxs, contentMask, capsule);
      merge(out, scratch);
    }
  }

  /** Folds {@code other} into {@code into} by the brush-merge rule. */
  static void merge(GameTrace into, GameTrace other) {
    boolean allSolid = into.allSolid | other.allSolid;
    boolean startSolid = into.startSolid | other.startSolid;
    if (other.fraction < into.fraction) into.copyFrom(other);
    into.allSolid = allSolid;
    into.startSolid = startSolid;
  }

  @Override
  public void traceTempBox(
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
      boolean capsule) {
    base.traceTempBox(
        out, start, end, mins, maxs, boxMins, boxMaxs, boxCapsule, contentMask, origin, capsule);
  }

  @Override
  public int pointContents(float[] point, int model, float[] origin, float[] angles) {
    int contents = base.pointContents(point, model, origin, angles);
    if (model == 0) for (Layer layer : layers) contents |= layer.pointContents(point);
    return contents;
  }

  @Override
  public int tempBoxPointContents(
      float[] point, float[] boxMins, float[] boxMaxs, boolean capsule, float[] origin) {
    return base.tempBoxPointContents(point, boxMins, boxMaxs, capsule, origin);
  }
}

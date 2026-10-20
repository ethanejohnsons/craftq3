package dev.bluevista.craftq3.engine.game.world;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceClipper;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import java.util.List;
import java.util.Objects;

/**
 * {@link WorldCollision} over an {@code engine:collision} {@link CollisionModel}, with optional
 * foreign {@link CollisionSource}s composed into every world (model 0) trace and point query: the
 * hook a map source uses for geometry the BSP does not contain (the Minecraft bridge's terrain).
 * One {@link TraceWork} is owned by the simulation thread.
 */
public final class ModelCollision implements WorldCollision {
  private static final float[] ZERO = new float[3];

  private final CollisionModel model;
  private final CollisionSource source;
  private final TraceWork work;
  private final Trace trace = new Trace();

  public ModelCollision(CollisionModel model, List<CollisionSource> sources) {
    this.model = Objects.requireNonNull(model);
    this.source = compose(sources);
    this.work = model.work();
  }

  public CollisionModel model() {
    return model;
  }

  /** One source clipping every given source in order, or null when there are none. */
  public static CollisionSource compose(List<CollisionSource> sources) {
    if (sources.isEmpty()) return null;
    if (sources.size() == 1) return sources.getFirst();
    CollisionSource[] all = sources.toArray(CollisionSource[]::new);
    return new CollisionSource() {
      @Override
      public void clip(TraceClipper clipper) {
        for (CollisionSource s : all) s.clip(clipper);
      }

      @Override
      public int pointContents(float x, float y, float z) {
        int contents = 0;
        for (CollisionSource s : all) contents |= s.pointContents(x, y, z);
        return contents;
      }
    };
  }

  @Override
  public int modelCount() {
    return model.inlineModelCount();
  }

  @Override
  public void modelBounds(int index, float[] mins, float[] maxs) {
    model.modelBounds(work, model.inlineModel(index), mins, maxs);
  }

  @Override
  public void traceModel(
      GameTrace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int index,
      int contentMask,
      float[] origin,
      float[] angles,
      boolean capsule) {
    int handle = model.inlineModel(index);
    if (handle == CollisionModel.WORLD_MODEL && isZero(origin) && isZero(angles))
      model.boxTrace(work, trace, start, end, mins, maxs, handle, contentMask, capsule, source);
    else
      model.transformedBoxTrace(
          work, trace, start, end, mins, maxs, handle, contentMask, origin, angles, capsule);
    copy(trace, out);
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
    int handle = model.tempBoxModel(work, boxMins, boxMaxs, boxCapsule);
    model.transformedBoxTrace(
        work, trace, start, end, mins, maxs, handle, contentMask, origin, ZERO, capsule);
    copy(trace, out);
  }

  @Override
  public int pointContents(float[] point, int index, float[] origin, float[] angles) {
    int handle = model.inlineModel(index);
    if (handle == CollisionModel.WORLD_MODEL && isZero(origin) && isZero(angles))
      return model.pointContents(work, point, handle, source);
    return model.transformedPointContents(work, point, handle, origin, angles);
  }

  @Override
  public int tempBoxPointContents(
      float[] point, float[] boxMins, float[] boxMaxs, boolean capsule, float[] origin) {
    int handle = model.tempBoxModel(work, boxMins, boxMaxs, capsule);
    return model.transformedPointContents(work, point, handle, origin, ZERO);
  }

  private static boolean isZero(float[] v) {
    return v == null || (v[0] == 0 && v[1] == 0 && v[2] == 0);
  }

  private static void copy(Trace from, GameTrace to) {
    to.allSolid = from.allSolid();
    to.startSolid = from.startSolid();
    to.fraction = from.fraction();
    from.endPos(to.endPos);
    for (int i = 0; i < 3; i++) to.planeNormal[i] = from.planeNormal(i);
    to.planeDist = from.planeDist();
    to.surfaceFlags = from.surfaceFlags();
    to.contents = from.contents();
    to.entityNum = from.entityNum();
  }
}

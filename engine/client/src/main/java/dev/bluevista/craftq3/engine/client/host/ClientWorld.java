package dev.bluevista.craftq3.engine.client.host;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceClipper;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.render.MarkFragments;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.layout.MarkFragmentLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.TraceLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;
import java.util.List;
import java.util.Objects;

/**
 * The cgame's view of the loaded map ({@code trap_CM_*}): the collision model for prediction traces
 * and point contents, temporary box and capsule models, mark fragments projected onto the world
 * surfaces, and the entity string tokens. The collision model and the mark-fragment surfaces are
 * built on first use. Foreign {@link CollisionSource}s (the host's terrain) are joined into world
 * (model 0) traces and point contents, as the server joins them.
 */
public final class ClientWorld {
  private final String name;
  private final BspMap map;
  private static final float[] ZERO = new float[3];

  private final ShaderLibrary shaders;
  private final CollisionSource source;
  private CollisionModel collision;
  private TraceWork work;
  private final Trace trace = new Trace();
  private MarkFragments marks;
  private final MarkFragments.Result markResult = new MarkFragments.Result();
  private final float[] start = new float[3], end = new float[3];
  private final float[] mins = new float[3], maxs = new float[3];
  private final float[] origin = new float[3], angles = new float[3];
  private final float[] point = new float[3];
  private int entityCursor;

  public ClientWorld(String name, BspMap map, ShaderLibrary shaders) {
    this(name, map, shaders, List.of());
  }

  /**
   * @param sources foreign geometry joined into world traces and point contents, in order
   */
  public ClientWorld(
      String name, BspMap map, ShaderLibrary shaders, List<CollisionSource> sources) {
    this.name = Objects.requireNonNull(name);
    this.map = Objects.requireNonNull(map);
    this.shaders = Objects.requireNonNull(shaders);
    this.source = compose(List.copyOf(sources));
  }

  private static CollisionSource compose(List<CollisionSource> sources) {
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

  public String name() {
    return name;
  }

  public BspMap map() {
    return map;
  }

  public CollisionModel collision() {
    if (collision == null) {
      collision = CollisionModel.build(map);
      work = collision.work();
    }
    return collision;
  }

  // ---------------------------------------------------------------- models

  public int inlineModelCount() {
    return collision().inlineModelCount();
  }

  public int inlineModel(int index) {
    return collision().inlineModel(index);
  }

  /** {@code CM_TempBoxModel(mins, maxs, capsule)}. */
  public int tempModel(QvmMemory memory, int minsAddress, int maxsAddress, boolean capsule) {
    VmStructs.readVec3(memory, minsAddress, mins);
    VmStructs.readVec3(memory, maxsAddress, maxs);
    CollisionModel model = collision();
    return model.tempBoxModel(work, mins, maxs, capsule);
  }

  // ---------------------------------------------------------------- contents and traces

  /** {@code CM_PointContents(p, model)} and its transformed variant. */
  public int pointContents(
      QvmMemory memory, int pointAddress, int model, int originAddress, int anglesAddress) {
    VmStructs.readVec3(memory, pointAddress, point);
    if (originAddress == 0 && anglesAddress == 0) return pointContents(point, model, null, null);
    return pointContents(
        point, model, vector(memory, originAddress, origin), vector(memory, anglesAddress, angles));
  }

  /**
   * Point contents of {@code model}; {@code origin}/{@code angles} null (or zero) for an
   * untransformed query. World queries also OR the foreign sources' contents.
   */
  public int pointContents(float[] p, int model, float[] at, float[] rotation) {
    CollisionModel cm = collision();
    if (at == null && rotation == null)
      return cm.pointContents(work, p, model, joins(model) ? source : null);
    float[] o = at == null ? ZERO : at, a = rotation == null ? ZERO : rotation;
    if (joins(model) && isZero(o) && isZero(a)) return cm.pointContents(work, p, model, source);
    return cm.transformedPointContents(work, p, model, o, a);
  }

  /**
   * {@code CM_BoxTrace} / {@code CM_TransformedBoxTrace} (and the capsule forms) into the guest's
   * {@code trace_t}. A transformed trace has non-zero origin and angle pointers.
   */
  public void trace(
      QvmMemory memory,
      int resultAddress,
      int startAddress,
      int endAddress,
      int minsAddress,
      int maxsAddress,
      int model,
      int mask,
      int originAddress,
      int anglesAddress,
      boolean transformed,
      boolean capsule) {
    VmStructs.readVec3(memory, startAddress, start);
    VmStructs.readVec3(memory, endAddress, end);
    float[] boxMins = minsAddress == 0 ? null : vector(memory, minsAddress, mins);
    float[] boxMaxs = maxsAddress == 0 ? null : vector(memory, maxsAddress, maxs);
    Trace result =
        transformed
            ? trace(
                start,
                end,
                boxMins,
                boxMaxs,
                model,
                mask,
                vector(memory, originAddress, origin),
                vector(memory, anglesAddress, angles),
                capsule)
            : trace(start, end, boxMins, boxMaxs, model, mask, null, null, capsule);
    TraceLayout.write(
        memory,
        resultAddress,
        new TraceLayout.VmTrace(
            result.allSolid(),
            result.startSolid(),
            result.fraction(),
            result.endPos(),
            result.planeNormal(),
            result.planeDist(),
            result.surfaceFlags(),
            result.contents(),
            result.entityNum()));
  }

  /**
   * The trace with host arrays ({@code origin}/{@code angles} null for {@code CM_BoxTrace}). A
   * world trace at the origin without rotation also clips the foreign sources, as the server's
   * world traces do. The result is reused by the next trace.
   */
  public Trace trace(
      float[] from,
      float[] to,
      float[] boxMins,
      float[] boxMaxs,
      int model,
      int mask,
      float[] at,
      float[] rotation,
      boolean capsule) {
    CollisionModel cm = collision();
    boolean transformed = at != null || rotation != null;
    float[] o = at == null ? ZERO : at, a = rotation == null ? ZERO : rotation;
    if (transformed && !(joins(model) && isZero(o) && isZero(a)))
      cm.transformedBoxTrace(work, trace, from, to, boxMins, boxMaxs, model, mask, o, a, capsule);
    else
      cm.boxTrace(
          work,
          trace,
          from,
          to,
          boxMins,
          boxMaxs,
          model,
          mask,
          capsule,
          joins(model) ? source : null);
    return trace;
  }

  /** True when traces through {@code model} join the foreign sources: the world, with sources. */
  private boolean joins(int model) {
    return source != null && model == CollisionModel.WORLD_MODEL;
  }

  private static boolean isZero(float[] v) {
    return v[0] == 0 && v[1] == 0 && v[2] == 0;
  }

  private static float[] vector(QvmMemory memory, int address, float[] out) {
    if (address == 0) {
      out[0] = out[1] = out[2] = 0;
      return out;
    }
    VmStructs.readVec3(memory, address, out);
    return out;
  }

  // ---------------------------------------------------------------- marks

  /**
   * {@code trap_CM_MarkFragments(numPoints, points, projection, maxPoints, pointBuffer,
   * maxFragments, fragmentBuffer)}: the number of fragments written.
   */
  public int markFragments(QvmMemory memory, int[] a) {
    int count = a[0];
    if (count < 3 || count > MarkFragments.MAX_POLYGON_POINTS) return 0;
    int maxPoints = Math.clamp(a[3], 0, MarkFragments.MAX_POINTS);
    int maxFragments = Math.clamp(a[5], 0, MarkFragments.MAX_FRAGMENTS);
    memory.resolve(a[1], count * VmStructs.VEC3_BYTES);
    float[] polygon = new float[count * 3];
    float[] corner = new float[3];
    for (int i = 0; i < count; i++) {
      VmStructs.readVec3(memory, a[1] + i * VmStructs.VEC3_BYTES, corner);
      System.arraycopy(corner, 0, polygon, i * 3, 3);
    }
    Vec3f projection = VmStructs.readVec3(memory, a[2]);
    if (projection.lengthSquared() == 0 || !projection.isFinite()) return 0;
    if (marks == null) {
      RenderScene scene = RenderScene.build(name, map, shaders::resolve);
      marks = new MarkFragments(scene.world());
    }
    marks.project(polygon, count, projection, maxPoints, maxFragments, markResult);
    for (int p = 0; p < markResult.pointCount(); p++)
      VmStructs.writeVec3(memory, a[4] + p * VmStructs.VEC3_BYTES, markResult.point(p));
    for (int f = 0; f < markResult.fragmentCount(); f++)
      MarkFragmentLayout.write(
          memory,
          a[6] + f * MarkFragmentLayout.BYTES,
          markResult.fragmentFirst(f),
          markResult.fragmentPoints(f));
    return markResult.fragmentCount();
  }

  // ---------------------------------------------------------------- entity string

  /** Restarts {@link #nextEntityToken()} at the beginning of the entity string. */
  public void resetEntityTokens() {
    entityCursor = 0;
  }

  /**
   * {@code trap_GetEntityToken}: the next {@code COM_Parse} token of the entity string, or null at
   * its end.
   */
  public String nextEntityToken() {
    String text = map.entityText();
    int i = entityCursor;
    while (true) {
      while (i < text.length() && text.charAt(i) <= ' ') i++;
      if (i + 1 < text.length() && text.charAt(i) == '/' && text.charAt(i + 1) == '/') {
        while (i < text.length() && text.charAt(i) != '\n') i++;
        continue;
      }
      if (i + 1 < text.length() && text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
        int close = text.indexOf("*/", i + 2);
        i = close < 0 ? text.length() : close + 2;
        continue;
      }
      break;
    }
    if (i >= text.length() || text.charAt(i) == 0) {
      entityCursor = text.length();
      return null;
    }
    StringBuilder token = new StringBuilder();
    if (text.charAt(i) == '"') {
      i++;
      while (i < text.length() && text.charAt(i) != '"' && text.charAt(i) != 0)
        token.append(text.charAt(i++));
      if (i < text.length() && text.charAt(i) == '"') i++;
    } else {
      while (i < text.length() && text.charAt(i) > ' ') token.append(text.charAt(i++));
    }
    entityCursor = i;
    return token.toString();
  }
}

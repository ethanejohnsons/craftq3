package dev.bluevista.craftq3.engine.client.host;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Trace;
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
import java.util.Objects;

/**
 * The cgame's view of the loaded map ({@code trap_CM_*}): the collision model for prediction traces
 * and point contents, temporary box and capsule models, mark fragments projected onto the world
 * surfaces, and the entity string tokens. The collision model and the mark-fragment surfaces are
 * built on first use.
 */
public final class ClientWorld {
  private final String name;
  private final BspMap map;
  private final ShaderLibrary shaders;
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
    this.name = Objects.requireNonNull(name);
    this.map = Objects.requireNonNull(map);
    this.shaders = Objects.requireNonNull(shaders);
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
    CollisionModel cm = collision();
    if (originAddress == 0 && anglesAddress == 0) return cm.pointContents(work, point, model);
    vector(memory, originAddress, origin);
    vector(memory, anglesAddress, angles);
    return cm.transformedPointContents(work, point, model, origin, angles);
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
    CollisionModel cm = collision();
    if (transformed) {
      vector(memory, originAddress, origin);
      vector(memory, anglesAddress, angles);
      cm.transformedBoxTrace(
          work, trace, start, end, boxMins, boxMaxs, model, mask, origin, angles, capsule);
    } else cm.boxTrace(work, trace, start, end, boxMins, boxMaxs, model, mask, capsule);
    TraceLayout.write(
        memory,
        resultAddress,
        new TraceLayout.VmTrace(
            trace.allSolid(),
            trace.startSolid(),
            trace.fraction(),
            trace.endPos(),
            trace.planeNormal(),
            trace.planeDist(),
            trace.surfaceFlags(),
            trace.contents(),
            trace.entityNum()));
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

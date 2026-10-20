package dev.bluevista.craftq3.engine.collision;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.common.math.VecMath;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import java.util.List;
import java.util.Objects;

/**
 * The collision model of one map (the Quake III "CM" layer): brushes and patch facets in the BSP
 * tree, inline models, temporary entity boxes and capsules, point contents, leaf, cluster and area
 * queries, and PVS tests.
 *
 * <p>Built once per map and immutable afterwards, so one instance serves the game simulation, the
 * client's prediction and bots on any threads. Queries keep their scratch state in a {@link
 * TraceWork}: pass one per thread on hot paths, or use the overloads without one, which use a
 * per-thread work object of this model. Area portal state, which changes as doors move, lives in a
 * separate {@link AreaPortalState}.
 *
 * <p>Model handles are ints as in the original {@code clipHandle_t}: {@link #WORLD_MODEL}, inline
 * models {@code 1..inlineModelCount()-1}, and {@link #BOX_MODEL_HANDLE} / {@link
 * #CAPSULE_MODEL_HANDLE} from {@link #tempBoxModel}.
 */
public final class CollisionModel {
  public static final int WORLD_MODEL = 0;

  /** The handle of the most recent temporary capsule of a {@link TraceWork}. */
  public static final int CAPSULE_MODEL_HANDLE = 254;

  /** The handle of the most recent temporary box of a {@link TraceWork}. */
  public static final int BOX_MODEL_HANDLE = 255;

  /** At most this many leaves are tested for a stationary (position) query. */
  public static final int MAX_POSITION_LEAFS = 1024;

  /**
   * Box sweeps descend into both sides of a non-axial node plane unless they stay this far beyond
   * it (the original's deliberately generous value: an axial brush behind a slanted plane can
   * otherwise poke through once expanded).
   */
  static final float NON_AXIAL_BOX_OFFSET = 2048;

  /** Node culling keeps a sweep on one side only when it is this much beyond its extents. */
  static final float CULL_MARGIN = 1;

  /** Stationary queries gather the leaves of the box grown by this much. */
  static final float POSITION_LEAF_MARGIN = 1;

  private static final Log LOG = Log.get(CollisionModel.class);

  /** Collision statistics of a built model. */
  public record Statistics(
      int brushes,
      int patches,
      int patchFacets,
      int patchPlanes,
      int skippedPatches,
      int truncatedPatches,
      int nodes,
      int leaves,
      int inlineModels,
      int clusters,
      int areas) {}

  final BrushTable brushes;
  final PatchTable patches;
  private final int[] nodePlane;
  private final int[] nodeChildren;
  private final int leafCount;
  private final int[] leafCluster;
  private final int[] leafArea;
  // Leaves 0..leafCount-1 are the BSP's; leaf leafCount + m holds inline model m's solids.
  private final int[] leafFirstBrush;
  private final int[] leafBrushCount;
  private final int[] leafBrushes;
  private final int[] leafFirstPatch;
  private final int[] leafPatchCount;
  private final int[] leafPatches;
  private final int modelCount;
  private final float[] modelBounds;
  private final int clusterCount;
  private final int areaCount;
  private final BspMap.Visibility visibility;
  private final Statistics statistics;
  private final ThreadLocal<TraceWork> works = ThreadLocal.withInitial(TraceWork::new);

  private CollisionModel(BspMap map, boolean curves) {
    List<BspMap.Texture> textures = map.textures();
    BspMap.Planes planes = map.planes();
    BspMap.BrushSides sides = map.brushSides();
    BspMap.Brushes bspBrushes = map.brushes();
    brushes = new BrushTable(planes.count(), sides.count(), bspBrushes.count());
    for (int p = 0; p < planes.count(); p++)
      brushes.setPlane(
          p, planes.normal(p, 0), planes.normal(p, 1), planes.normal(p, 2), planes.distance(p));
    for (int s = 0; s < sides.count(); s++) {
      brushes.sidePlanes[s] = sides.plane(s);
      int texture = sides.texture(s);
      brushes.sideFlags[s] =
          texture >= 0 && texture < textures.size() ? textures.get(texture).surfaceFlags() : 0;
    }
    for (int b = 0; b < bspBrushes.count(); b++) {
      brushes.firstSide[b] = bspBrushes.firstSide(b);
      brushes.sideCount[b] = bspBrushes.sideCount(b);
      int texture = bspBrushes.texture(b);
      brushes.contents[b] =
          texture >= 0 && texture < textures.size() ? textures.get(texture).contents() : 0;
      brushes.bound(b);
    }

    BspMap.Nodes nodes = map.nodes();
    nodePlane = new int[nodes.count()];
    nodeChildren = new int[nodes.count() * 2];
    for (int n = 0; n < nodes.count(); n++) {
      nodePlane[n] = nodes.plane(n);
      nodeChildren[n * 2] = nodes.front(n);
      nodeChildren[n * 2 + 1] = nodes.back(n);
    }

    // Patches, indexed by face.
    BspMap.Faces faces = map.faces();
    int[] facePatch = new int[faces.count()];
    PatchTable.Builder patchBuilder = new PatchTable.Builder();
    PatchBuilder builder = new PatchBuilder();
    BspMap.Vertices vertices = map.vertices();
    int skipped = 0;
    int truncated = 0;
    for (int f = 0; f < faces.count(); f++) {
      facePatch[f] = -1;
      if (!curves || faces.surfaceType(f) != SurfaceType.PATCH) continue;
      int texture = faces.texture(f);
      if (texture < 0 || texture >= textures.size()) continue;
      int contents = textures.get(texture).contents();
      // A patch without contents can never block anything.
      if (contents == 0) continue;
      int width = faces.patchWidth(f);
      int height = faces.patchHeight(f);
      int first = faces.firstVertex(f);
      if ((long) width * height > faces.vertexCount(f) || width < 0 || height < 0) {
        skipped++;
        continue;
      }
      float[] points = new float[width * height * 3];
      for (int v = 0; v < width * height; v++)
        for (int axis = 0; axis < 3; axis++)
          points[v * 3 + axis] = vertices.position(first + v, axis);
      int index = patchBuilder.count;
      try {
        if (builder.build(
                width,
                height,
                points,
                f,
                contents,
                textures.get(texture).surfaceFlags(),
                patchBuilder)
            > 0) facePatch[f] = index;
        if (builder.truncated()) truncated++;
      } catch (IllegalArgumentException | PatchBuilder.BudgetException e) {
        skipped++;
        LOG.warnOnce("patch:" + e.getMessage(), "Patch face " + f + " skipped: " + e.getMessage());
      }
    }
    patches = patchBuilder.build();

    BspMap.Leaves leaves = map.leaves();
    leafCount = leaves.count();
    List<BspMap.Model> models = map.models();
    modelCount = Math.max(1, models.size());
    int totalLeaves = leafCount + modelCount;
    leafCluster = new int[leafCount];
    leafArea = new int[leafCount];
    leafFirstBrush = new int[totalLeaves];
    leafBrushCount = new int[totalLeaves];
    leafFirstPatch = new int[totalLeaves];
    leafPatchCount = new int[totalLeaves];
    IntList brushList = new IntList();
    IntList patchList = new IntList();
    int maxCluster = -1;
    int maxArea = -1;
    BspMap.IndexList bspLeafBrushes = map.leafBrushes();
    BspMap.IndexList bspLeafFaces = map.leafFaces();
    for (int l = 0; l < leafCount; l++) {
      leafCluster[l] = leaves.cluster(l);
      leafArea[l] = leaves.area(l);
      maxCluster = Math.max(maxCluster, leafCluster[l]);
      maxArea = Math.max(maxArea, leafArea[l]);
      leafFirstBrush[l] = brushList.size;
      for (int k = 0; k < leaves.leafBrushCount(l); k++) {
        int brush = bspLeafBrushes.get(leaves.firstLeafBrush(l) + k);
        if (brush >= 0 && brush < brushes.count()) brushList.add(brush);
      }
      leafBrushCount[l] = brushList.size - leafFirstBrush[l];
      leafFirstPatch[l] = patchList.size;
      for (int k = 0; k < leaves.leafFaceCount(l); k++) {
        int face = bspLeafFaces.get(leaves.firstLeafFace(l) + k);
        if (face >= 0 && face < facePatch.length && facePatch[face] >= 0)
          patchList.add(facePatch[face]);
      }
      leafPatchCount[l] = patchList.size - leafFirstPatch[l];
    }
    modelBounds = new float[modelCount * 6];
    for (int m = 0; m < models.size(); m++) {
      BspMap.Model model = models.get(m);
      // Spread by a unit, as the original does for its cheap rejections.
      for (int axis = 0; axis < 3; axis++) {
        modelBounds[m * 6 + axis] = model.mins().get(axis) - 1;
        modelBounds[m * 6 + 3 + axis] = model.maxs().get(axis) + 1;
      }
      int leaf = leafCount + m;
      leafFirstBrush[leaf] = brushList.size;
      leafFirstPatch[leaf] = patchList.size;
      if (m == 0) continue; // The world is traced through the tree.
      for (int b = model.firstBrush(); b < model.firstBrush() + model.brushCount(); b++)
        if (b >= 0 && b < brushes.count()) brushList.add(b);
      for (int f = model.firstFace(); f < model.firstFace() + model.faceCount(); f++)
        if (f >= 0 && f < facePatch.length && facePatch[f] >= 0) patchList.add(facePatch[f]);
      leafBrushCount[leaf] = brushList.size - leafFirstBrush[leaf];
      leafPatchCount[leaf] = patchList.size - leafFirstPatch[leaf];
    }
    leafBrushes = brushList.toArray();
    leafPatches = patchList.toArray();

    visibility = map.visibility();
    clusterCount = visibility.absent() ? maxCluster + 1 : visibility.clusters();
    areaCount = maxArea + 1;
    if (skipped > 0) LOG.warn("Skipped " + skipped + " invalid patch surfaces");
    statistics =
        new Statistics(
            brushes.count(),
            patches.count,
            patches.totalFacets(),
            patches.totalPlanes(),
            skipped,
            truncated,
            nodePlane.length,
            leafCount,
            modelCount,
            clusterCount,
            areaCount);
  }

  /** Builds the collision model of {@code map}, generating every patch's facets. */
  public static CollisionModel build(BspMap map) {
    return build(map, true);
  }

  /**
   * Builds the collision model of {@code map}; without {@code curves} patches do not collide at
   * all, like the original's {@code cm_noCurves} cheat.
   */
  public static CollisionModel build(BspMap map, boolean curves) {
    return new CollisionModel(Objects.requireNonNull(map, "map"), curves);
  }

  public Statistics statistics() {
    return statistics;
  }

  /** This thread's work object for this model, as used by the overloads without one. */
  public TraceWork work() {
    return works.get();
  }

  // ---- models ---------------------------------------------------------------------------------

  /** The number of models including the world (model 0). */
  public int inlineModelCount() {
    return modelCount;
  }

  /** The handle of inline model {@code index} ({@code CM_InlineModel}). */
  public int inlineModel(int index) {
    if (index < 0 || index >= modelCount)
      throw new IllegalArgumentException("Inline model " + index + " out of range");
    return index;
  }

  /**
   * Sets {@code work}'s temporary box ({@link #BOX_MODEL_HANDLE}, contents {@link Contents#BODY})
   * or capsule ({@link #CAPSULE_MODEL_HANDLE}) to the given bounds and returns its handle.
   */
  public int tempBoxModel(TraceWork work, float[] mins, float[] maxs, boolean capsule) {
    return tempBoxModel(work, mins, maxs, capsule, Contents.BODY);
  }

  /** As {@link #tempBoxModel(TraceWork, float[], float[], boolean)} with box contents. */
  public int tempBoxModel(
      TraceWork work, float[] mins, float[] maxs, boolean capsule, int boxContents) {
    VecMath.copy(mins, work.tempMins);
    VecMath.copy(maxs, work.tempMaxs);
    work.tempCapsule = capsule;
    if (capsule) return CAPSULE_MODEL_HANDLE;
    work.tempBox.setBox(mins, maxs, boxContents, 0);
    return BOX_MODEL_HANDLE;
  }

  /** The temporary box or capsule of this thread's {@link #work()}. */
  public int tempBoxModel(float[] mins, float[] maxs, boolean capsule) {
    return tempBoxModel(work(), mins, maxs, capsule);
  }

  /** A model's bounds; inline models are spread by one unit, as the original does. */
  public void modelBounds(TraceWork work, int handle, float[] mins, float[] maxs) {
    if (isTemp(handle)) {
      VecMath.copy(work.tempMins, mins);
      VecMath.copy(work.tempMaxs, maxs);
      return;
    }
    checkHandle(handle);
    System.arraycopy(modelBounds, handle * 6, mins, 0, 3);
    System.arraycopy(modelBounds, handle * 6 + 3, maxs, 0, 3);
  }

  public void modelBounds(int handle, float[] mins, float[] maxs) {
    modelBounds(work(), handle, mins, maxs);
  }

  private boolean isTemp(int handle) {
    return handle >= modelCount && (handle == BOX_MODEL_HANDLE || handle == CAPSULE_MODEL_HANDLE);
  }

  private void checkHandle(int handle) {
    if (handle < 0 || (handle >= modelCount && !isTemp(handle)))
      throw new IllegalArgumentException("Bad clip model handle " + handle);
  }

  // ---- traces ---------------------------------------------------------------------------------

  /** {@link #boxTrace(TraceWork, Trace, float[], float[], float[], float[], int, int, boolean)}. */
  public void boxTrace(
      TraceWork work,
      Trace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int model,
      int contentMask,
      boolean capsule) {
    boxTrace(work, out, start, end, mins, maxs, model, contentMask, capsule, null);
  }

  /**
   * Sweeps the box {@code mins..maxs} (or the capsule fitted to it) from {@code start} to {@code
   * end} through {@code model} ({@code CM_BoxTrace}). A null box is a point. When {@code start}
   * equals {@code end} the query is a position test: it reports all-solid if the box overlaps a
   * solid. World traces ({@link #WORLD_MODEL}) also clip against {@code source} when it is not
   * null.
   */
  public void boxTrace(
      TraceWork work,
      Trace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int model,
      int contentMask,
      boolean capsule,
      CollisionSource source) {
    checkHandle(model);
    work.sphereOffset[0] = 0;
    work.sphereOffset[1] = 0;
    work.sphereOffset[2] = 0;
    trace(work, start, end, mins, maxs, model, contentMask, capsule, false, source);
    copyResult(work, out);
    if (work.fraction == 1) VecMath.copy(end, out.endPos);
    else lerp(start, end, work.fraction, out.endPos);
  }

  /**
   * Sweeps against a model placed at {@code origin} and rotated by {@code angles} (pitch, yaw, roll
   * in degrees) ({@code CM_TransformedBoxTrace}). The box is made symmetric about its centre and
   * the segment is moved into the model's frame; with non-zero angles the segment (and a capsule's
   * axis) is rotated there, while a box keeps its axes, an approximation the original accepts. A
   * hit plane's normal is rotated back to world space and its distance is given in world space; the
   * end position is recomputed from the world segment.
   */
  public void transformedBoxTrace(
      TraceWork work,
      Trace out,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int model,
      int contentMask,
      float[] origin,
      float[] angles,
      boolean capsule) {
    checkHandle(model);
    float[] ls = work.localStart;
    float[] le = work.localEnd;
    float[] smin = work.symmetricMins;
    float[] smax = work.symmetricMaxs;
    for (int axis = 0; axis < 3; axis++) {
      float lo = mins == null ? 0 : mins[axis];
      float hi = maxs == null ? 0 : maxs[axis];
      float offset = (lo + hi) * 0.5f;
      smin[axis] = lo - offset;
      smax[axis] = hi - offset;
      ls[axis] = start[axis] + offset - origin[axis];
      le[axis] = end[axis] + offset - origin[axis];
    }
    boolean rotated = rotates(model, angles);
    float radius = Math.min(smax[0], smax[2]);
    float axisHalf = smax[2] - radius;
    float[] r = work.rotation;
    if (rotated) {
      rotationMatrix(work, angles);
      rotate(r, ls);
      rotate(r, le);
      // The capsule's vertical axis seen from the model's frame.
      work.sphereOffset[0] = r[2] * axisHalf;
      work.sphereOffset[1] = r[5] * axisHalf;
      work.sphereOffset[2] = r[8] * axisHalf;
    } else {
      work.sphereOffset[0] = 0;
      work.sphereOffset[1] = 0;
      work.sphereOffset[2] = axisHalf;
    }
    trace(work, ls, le, smin, smax, model, contentMask, capsule, true, null);
    copyResult(work, out);
    if (rotated && work.fraction != 1) {
      float nx = out.normal[0];
      float ny = out.normal[1];
      float nz = out.normal[2];
      // The transpose takes model-frame vectors back to world space.
      out.normal[0] = r[0] * nx + r[3] * ny + r[6] * nz;
      out.normal[1] = r[1] * nx + r[4] * ny + r[7] * nz;
      out.normal[2] = r[2] * nx + r[5] * ny + r[8] * nz;
    }
    out.dist += VecMath.dot(out.normal, origin);
    lerp(start, end, work.fraction, out.endPos);
  }

  /**
   * Whether the box {@code mins..maxs} (absolute coordinates) overlaps the placed model, the test
   * behind {@code trap_EntityContact} and {@code trap_EntityContactCapsule}.
   */
  public boolean entityContact(
      TraceWork work,
      float[] mins,
      float[] maxs,
      int model,
      float[] origin,
      float[] angles,
      boolean capsule) {
    Trace trace = work.contactTrace;
    float[] at = work.contactPoint;
    at[0] = 0;
    at[1] = 0;
    at[2] = 0;
    transformedBoxTrace(work, trace, at, at, mins, maxs, model, -1, origin, angles, capsule);
    return trace.startSolid;
  }

  /** A trace described by a request, using this thread's work object. */
  public Trace trace(TraceRequest request) {
    TraceWork work = work();
    Trace out = new Trace();
    float[] start = request.start().toArray();
    float[] end = request.end().toArray();
    float[] mins = request.mins().toArray();
    float[] maxs = request.maxs().toArray();
    if (request.isTransformed())
      transformedBoxTrace(
          work,
          out,
          start,
          end,
          mins,
          maxs,
          request.model(),
          request.contentMask(),
          request.origin().toArray(),
          request.angles().toArray(),
          request.capsule());
    else
      boxTrace(
          work,
          out,
          start,
          end,
          mins,
          maxs,
          request.model(),
          request.contentMask(),
          request.capsule());
    return out;
  }

  private static boolean rotates(int model, float[] angles) {
    return model != BOX_MODEL_HANDLE && (angles[0] != 0 || angles[1] != 0 || angles[2] != 0);
  }

  /** Rows: forward, left (the negated right vector) and up of the angles. */
  private static void rotationMatrix(TraceWork work, float[] angles) {
    VecMath.angleVectors(angles, work.forward, work.right, work.up);
    float[] r = work.rotation;
    r[0] = work.forward[0];
    r[1] = work.forward[1];
    r[2] = work.forward[2];
    r[3] = -work.right[0];
    r[4] = -work.right[1];
    r[5] = -work.right[2];
    r[6] = work.up[0];
    r[7] = work.up[1];
    r[8] = work.up[2];
  }

  private static void rotate(float[] r, float[] p) {
    float x = p[0];
    float y = p[1];
    float z = p[2];
    p[0] = r[0] * x + r[1] * y + r[2] * z;
    p[1] = r[3] * x + r[4] * y + r[5] * z;
    p[2] = r[6] * x + r[7] * y + r[8] * z;
  }

  private static void lerp(float[] start, float[] end, float fraction, float[] out) {
    for (int axis = 0; axis < 3; axis++)
      out[axis] = start[axis] + fraction * (end[axis] - start[axis]);
  }

  private static void copyResult(TraceWork w, Trace out) {
    out.allSolid = w.allSolid;
    out.startSolid = w.startSolid;
    out.fraction = w.fraction;
    VecMath.copy(w.normal, out.normal);
    out.dist = w.dist;
    out.planeType = w.planeType;
    out.planeSignbits = w.planeSigns;
    out.surfaceFlags = w.surfaceFlags;
    out.contents = w.contents;
    out.entityNum = 0;
  }

  /** The shared core: positions in model space, the sphere offset preset when requested. */
  private void trace(
      TraceWork w,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      int model,
      int contentMask,
      boolean capsule,
      boolean presetSphereOffset,
      CollisionSource source) {
    w.begin(this);
    w.resetResult();
    w.mask = contentMask;
    boolean zeroSize = true;
    for (int axis = 0; axis < 3; axis++) {
      float lo = mins == null ? 0 : mins[axis];
      float hi = maxs == null ? 0 : maxs[axis];
      float offset = (lo + hi) * 0.5f;
      w.size0[axis] = lo - offset;
      w.size1[axis] = hi - offset;
      w.start[axis] = start[axis] + offset;
      w.end[axis] = end[axis] + offset;
      if (w.size0[axis] != 0) zeroSize = false;
    }
    w.sphere = capsule;
    if (capsule) {
      w.radius = Math.min(w.size1[0], w.size1[2]);
      w.halfHeight = w.size1[2];
      if (!presetSphereOffset) w.sphereOffset[2] = w.halfHeight - w.radius;
    }
    for (int i = 0; i < 8; i++) {
      w.offsets[i * 3] = (i & 1) != 0 ? w.size1[0] : w.size0[0];
      w.offsets[i * 3 + 1] = (i & 2) != 0 ? w.size1[1] : w.size0[1];
      w.offsets[i * 3 + 2] = (i & 4) != 0 ? w.size1[2] : w.size0[2];
    }
    if (capsule) Clip.sphereBounds(w);
    else
      for (int axis = 0; axis < 3; axis++) {
        w.bounds0[axis] = Math.min(w.start[axis], w.end[axis]) + w.size0[axis];
        w.bounds1[axis] = Math.max(w.start[axis], w.end[axis]) + w.size1[axis];
      }
    w.positionTest = start[0] == end[0] && start[1] == end[1] && start[2] == end[2];
    if (w.positionTest) {
      // The original never marks a stationary query as a point.
      w.isPoint = false;
      for (int axis = 0; axis < 3; axis++) w.extents[axis] = 0;
    } else {
      w.isPoint = zeroSize;
      for (int axis = 0; axis < 3; axis++) w.extents[axis] = zeroSize ? 0 : w.size1[axis];
    }

    if (isTemp(model)) {
      if (model == CAPSULE_MODEL_HANDLE) {
        if (!capsule) Clip.boxAgainstCapsule(w, w.tempMins, w.tempMaxs);
        else if (w.positionTest) Clip.testCapsuleInCapsule(w, w.tempMins, w.tempMaxs);
        else Clip.traceCapsuleThroughCapsule(w, w.tempMins, w.tempMaxs);
      } else if ((w.tempBox.contents[0] & contentMask) != 0) Clip.brush(w, w.tempBox, 0);
    } else if (model != WORLD_MODEL) {
      if (w.positionTest) testLeaf(w, leafCount + model);
      else traceLeaf(w, leafCount + model);
    } else if (w.positionTest) positionTestWorld(w);
    else if (nodePlane.length > 0) walkTree(w);
    else if (leafCount == 1) traceLeaf(w, 0);

    if (source != null && model == WORLD_MODEL && !w.allSolid) source.clip(w.clipper);
  }

  private void walkTree(TraceWork w) {
    float[] planes = brushes.planes;
    byte[] types = brushes.planeTypes;
    w.ensureStack(1);
    w.nodeStack[0] = 0;
    float[] fs = w.frameStack;
    fs[0] = 0;
    fs[1] = 1;
    System.arraycopy(w.start, 0, fs, 2, 3);
    System.arraycopy(w.end, 0, fs, 5, 3);
    int top = 1;
    while (top > 0) {
      top--;
      int node = w.nodeStack[top];
      fs = w.frameStack;
      int f = top * 8;
      float p1f = fs[f];
      if (w.fraction <= p1f) continue; // Already hit something nearer.
      if (node < 0) {
        traceLeaf(w, ~node);
        if (w.fraction == 0) return;
        continue;
      }
      float p2f = fs[f + 1];
      float p1x = fs[f + 2];
      float p1y = fs[f + 3];
      float p1z = fs[f + 4];
      float p2x = fs[f + 5];
      float p2y = fs[f + 6];
      float p2z = fs[f + 7];
      int plane = nodePlane[node];
      int base = plane * 4;
      int type = types[plane];
      float t1;
      float t2;
      float offset;
      if (type < 3) {
        float d = planes[base + 3];
        t1 = (type == 0 ? p1x : type == 1 ? p1y : p1z) - d;
        t2 = (type == 0 ? p2x : type == 1 ? p2y : p2z) - d;
        offset = w.extents[type];
      } else {
        float nx = planes[base];
        float ny = planes[base + 1];
        float nz = planes[base + 2];
        float d = planes[base + 3];
        t1 = nx * p1x + ny * p1y + nz * p1z - d;
        t2 = nx * p2x + ny * p2y + nz * p2z - d;
        offset = w.isPoint ? 0 : NON_AXIAL_BOX_OFFSET;
      }
      int front = nodeChildren[node * 2];
      int back = nodeChildren[node * 2 + 1];
      if (t1 >= offset + CULL_MARGIN && t2 >= offset + CULL_MARGIN) {
        top = push(w, top, front, p1f, p2f, p1x, p1y, p1z, p2x, p2y, p2z);
        continue;
      }
      if (t1 < -offset - CULL_MARGIN && t2 < -offset - CULL_MARGIN) {
        top = push(w, top, back, p1f, p2f, p1x, p1y, p1z, p2x, p2y, p2z);
        continue;
      }
      // Put the crossing point SURFACE_CLIP_EPSILON on the near side.
      int side;
      float frac;
      float frac2;
      if (t1 < t2) {
        float inverse = 1.0f / (t1 - t2);
        side = 1;
        frac2 = (t1 + offset + Clip.SURFACE_CLIP_EPSILON) * inverse;
        frac = (t1 - offset + Clip.SURFACE_CLIP_EPSILON) * inverse;
      } else if (t1 > t2) {
        float inverse = 1.0f / (t1 - t2);
        side = 0;
        frac2 = (t1 - offset - Clip.SURFACE_CLIP_EPSILON) * inverse;
        frac = (t1 + offset + Clip.SURFACE_CLIP_EPSILON) * inverse;
      } else {
        side = 0;
        frac = 1;
        frac2 = 0;
      }
      if (frac < 0) frac = 0;
      if (frac > 1) frac = 1;
      if (frac2 < 0) frac2 = 0;
      if (frac2 > 1) frac2 = 1;
      int near = side == 0 ? front : back;
      int far = side == 0 ? back : front;
      // Far side first on the stack so the near side is walked (completely) first.
      float midf2 = p1f + (p2f - p1f) * frac2;
      top =
          push(
              w,
              top,
              far,
              midf2,
              p2f,
              p1x + frac2 * (p2x - p1x),
              p1y + frac2 * (p2y - p1y),
              p1z + frac2 * (p2z - p1z),
              p2x,
              p2y,
              p2z);
      float midf = p1f + (p2f - p1f) * frac;
      top =
          push(
              w,
              top,
              near,
              p1f,
              midf,
              p1x,
              p1y,
              p1z,
              p1x + frac * (p2x - p1x),
              p1y + frac * (p2y - p1y),
              p1z + frac * (p2z - p1z));
    }
  }

  private static int push(
      TraceWork w,
      int top,
      int node,
      float p1f,
      float p2f,
      float p1x,
      float p1y,
      float p1z,
      float p2x,
      float p2y,
      float p2z) {
    w.ensureStack(top + 1);
    w.nodeStack[top] = node;
    float[] fs = w.frameStack;
    int f = top * 8;
    fs[f] = p1f;
    fs[f + 1] = p2f;
    fs[f + 2] = p1x;
    fs[f + 3] = p1y;
    fs[f + 4] = p1z;
    fs[f + 5] = p2x;
    fs[f + 6] = p2y;
    fs[f + 7] = p2z;
    return top + 1;
  }

  private void traceLeaf(TraceWork w, int leaf) {
    int[] checks = w.brushChecks;
    int check = w.checkCount;
    int first = leafFirstBrush[leaf];
    int end = first + leafBrushCount[leaf];
    for (int k = first; k < end; k++) {
      int b = leafBrushes[k];
      if (checks[b] == check) continue; // Already clipped in another leaf.
      checks[b] = check;
      if ((brushes.contents[b] & w.mask) == 0) continue;
      if (!Clip.boundsIntersect(w, brushes.bounds, b * 6)) continue;
      Clip.traceBrush(w, brushes, b);
      if (w.fraction == 0) return;
    }
    int[] patchChecks = w.patchChecks;
    first = leafFirstPatch[leaf];
    end = first + leafPatchCount[leaf];
    for (int k = first; k < end; k++) {
      int p = leafPatches[k];
      if (patchChecks[p] == check) continue;
      patchChecks[p] = check;
      if ((patches.contents[p] & w.mask) == 0) continue;
      Clip.tracePatch(w, patches, p);
      if (w.fraction == 0) return;
    }
  }

  private void testLeaf(TraceWork w, int leaf) {
    int[] checks = w.brushChecks;
    int check = w.checkCount;
    int first = leafFirstBrush[leaf];
    int end = first + leafBrushCount[leaf];
    for (int k = first; k < end; k++) {
      int b = leafBrushes[k];
      if (checks[b] == check) continue;
      checks[b] = check;
      if ((brushes.contents[b] & w.mask) == 0) continue;
      Clip.testBrush(w, brushes, b);
      if (w.allSolid) return;
    }
    int[] patchChecks = w.patchChecks;
    first = leafFirstPatch[leaf];
    end = first + leafPatchCount[leaf];
    for (int k = first; k < end; k++) {
      int p = leafPatches[k];
      if (patchChecks[p] == check) continue;
      patchChecks[p] = check;
      if ((patches.contents[p] & w.mask) == 0) continue;
      if (Clip.testPatch(w, patches, p)) {
        w.startSolid = true;
        w.allSolid = true;
        w.fraction = 0;
        w.contents = patches.contents[p];
        return;
      }
    }
  }

  private void positionTestWorld(TraceWork w) {
    float[] lo = w.leafMins;
    float[] hi = w.leafMaxs;
    for (int axis = 0; axis < 3; axis++) {
      lo[axis] = w.start[axis] + w.size0[axis] - POSITION_LEAF_MARGIN;
      hi[axis] = w.start[axis] + w.size1[axis] + POSITION_LEAF_MARGIN;
    }
    int count = collectLeaves(w, lo, hi, w.leafList, MAX_POSITION_LEAFS);
    for (int i = 0; i < count; i++) {
      testLeaf(w, w.leafList[i]);
      if (w.allSolid) return;
    }
  }

  /**
   * Front-first leaves under the box, as {@code CM_BoxLeafnums}; stores at most {@code capacity}
   * and records the last leaf with a cluster in {@link TraceWork#lastLeaf}.
   */
  private int collectLeaves(TraceWork w, float[] mins, float[] maxs, int[] list, int capacity) {
    w.lastLeaf = 0;
    int count = 0;
    if (nodePlane.length == 0) {
      if (leafCount == 1) {
        if (capacity > 0) list[count++] = 0;
        if (leafCluster[0] != -1) w.lastLeaf = 0;
      }
      return count;
    }
    float[] planes = brushes.planes;
    byte[] types = brushes.planeTypes;
    byte[] signs = brushes.planeSigns;
    int[] stack = w.nodeStack;
    int top = 0;
    stack[top++] = 0;
    while (top > 0) {
      int node = stack[--top];
      if (node < 0) {
        int leaf = ~node;
        if (leafCluster[leaf] != -1) w.lastLeaf = leaf;
        if (count < capacity) list[count++] = leaf;
        continue;
      }
      int plane = nodePlane[node];
      int base = plane * 4;
      int type = types[plane];
      float d = planes[base + 3];
      int sides;
      if (type < 3) {
        if (d <= mins[type]) sides = 1;
        else if (d >= maxs[type]) sides = 2;
        else sides = 3;
      } else {
        int s = signs[plane];
        float nx = planes[base];
        float ny = planes[base + 1];
        float nz = planes[base + 2];
        float high =
            nx * ((s & 1) != 0 ? mins[0] : maxs[0])
                + ny * ((s & 2) != 0 ? mins[1] : maxs[1])
                + nz * ((s & 4) != 0 ? mins[2] : maxs[2]);
        float low =
            nx * ((s & 1) != 0 ? maxs[0] : mins[0])
                + ny * ((s & 2) != 0 ? maxs[1] : mins[1])
                + nz * ((s & 4) != 0 ? maxs[2] : mins[2]);
        sides = (high >= d ? 1 : 0) | (low < d ? 2 : 0);
      }
      if (top + 2 > stack.length) {
        w.ensureStack(top + 2);
        stack = w.nodeStack;
      }
      if ((sides & 2) != 0) stack[top++] = nodeChildren[node * 2 + 1];
      if ((sides & 1) != 0) stack[top++] = nodeChildren[node * 2];
    }
    return count;
  }

  // ---- point queries --------------------------------------------------------------------------

  /**
   * The OR of the contents of every brush of {@code model} containing {@code p} ({@code
   * CM_PointContents}). Points on a brush face count as inside; patches have no volume.
   */
  public int pointContents(TraceWork work, float[] p, int model) {
    checkHandle(model);
    if (isTemp(model)) {
      if (model == CAPSULE_MODEL_HANDLE)
        return Clip.pointInCapsule(work.tempMins, work.tempMaxs, p, work.scratch)
            ? Contents.BODY
            : 0;
      return brushContains(work.tempBox, 0, p) ? work.tempBox.contents[0] : 0;
    }
    int leaf;
    if (model == WORLD_MODEL) {
      if (nodePlane.length == 0 && leafCount != 1) return 0;
      leaf = pointLeafnum(p);
    } else leaf = leafCount + model;
    int contents = 0;
    int first = leafFirstBrush[leaf];
    int end = first + leafBrushCount[leaf];
    for (int k = first; k < end; k++) {
      int b = leafBrushes[k];
      if (brushContains(brushes, b, p)) contents |= brushes.contents[b];
    }
    return contents;
  }

  /** As {@link #pointContents(TraceWork, float[], int)}, also ORing a foreign source's contents. */
  public int pointContents(TraceWork work, float[] p, int model, CollisionSource source) {
    int contents = pointContents(work, p, model);
    return source == null ? contents : contents | source.pointContents(p[0], p[1], p[2]);
  }

  public int pointContents(float[] p, int model) {
    return pointContents(work(), p, model);
  }

  public int pointContents(Vec3f p, int model) {
    return pointContents(work(), p.toArray(), model);
  }

  /** Point contents of a model placed at {@code origin} with {@code angles}. */
  public int transformedPointContents(
      TraceWork work, float[] p, int model, float[] origin, float[] angles) {
    float[] local = work.contactPoint;
    VecMath.subtract(p, origin, local);
    if (rotates(model, angles)) {
      rotationMatrix(work, angles);
      rotate(work.rotation, local);
    }
    return pointContents(work, local, model);
  }

  private static boolean brushContains(BrushTable t, int b, float[] p) {
    int base6 = b * 6;
    float[] bb = t.bounds;
    float e = Clip.SURFACE_CLIP_EPSILON;
    if (bb[base6 + 3] < p[0] - e
        || bb[base6 + 4] < p[1] - e
        || bb[base6 + 5] < p[2] - e
        || bb[base6] > p[0] + e
        || bb[base6 + 1] > p[1] + e
        || bb[base6 + 2] > p[2] + e) return false;
    int first = t.firstSide[b];
    int count = t.sideCount[b];
    float[] planes = t.planes;
    for (int s = first; s < first + count; s++) {
      int base = t.sidePlanes[s] * 4;
      float d = p[0] * planes[base] + p[1] * planes[base + 1] + p[2] * planes[base + 2];
      if (d > planes[base + 3]) return false;
    }
    return count > 0;
  }

  // ---- leaves, clusters, areas, PVS ----------------------------------------------------------

  public int leafCount() {
    return leafCount;
  }

  /** The leaf containing {@code p}; points on a node plane go to its front. */
  public int pointLeafnum(float[] p) {
    if (nodePlane.length == 0) return 0;
    float[] planes = brushes.planes;
    byte[] types = brushes.planeTypes;
    int node = 0;
    while (node >= 0) {
      int plane = nodePlane[node];
      int base = plane * 4;
      int type = types[plane];
      float d =
          type < 3
              ? p[type] - planes[base + 3]
              : p[0] * planes[base]
                  + p[1] * planes[base + 1]
                  + p[2] * planes[base + 2]
                  - planes[base + 3];
      node = nodeChildren[node * 2 + (d < 0 ? 1 : 0)];
    }
    return ~node;
  }

  public int leafCluster(int leaf) {
    if (leaf < 0 || leaf >= leafCount)
      throw new IllegalArgumentException("Leaf " + leaf + " out of range");
    return leafCluster[leaf];
  }

  public int leafArea(int leaf) {
    if (leaf < 0 || leaf >= leafCount)
      throw new IllegalArgumentException("Leaf " + leaf + " out of range");
    return leafArea[leaf];
  }

  /**
   * Writes up to {@code capacity} leaves touched by the box into {@code list}, front sides first
   * ({@code CM_BoxLeafnums}). Returns the number written. When {@code lastLeaf} is not null its
   * first element receives the last leaf found with a cluster, even past the capacity.
   */
  public int boxLeafnums(
      TraceWork work, float[] mins, float[] maxs, int[] list, int capacity, int[] lastLeaf) {
    if (capacity > list.length) throw new IllegalArgumentException("Capacity exceeds the list");
    int count = collectLeaves(work, mins, maxs, list, capacity);
    if (lastLeaf != null) lastLeaf[0] = work.lastLeaf;
    return count;
  }

  public int boxLeafnums(float[] mins, float[] maxs, int[] list, int capacity, int[] lastLeaf) {
    return boxLeafnums(work(), mins, maxs, list, capacity, lastLeaf);
  }

  /** Visibility clusters: from the visibility lump, or the leaves when the map has none. */
  public int clusterCount() {
    return clusterCount;
  }

  public int areaCount() {
    return areaCount;
  }

  /**
   * Whether cluster {@code to} is in the potentially visible set of cluster {@code from}. A
   * negative source cluster, or a map without visibility data, sees everything.
   */
  public boolean clusterVisible(int from, int to) {
    return visibility.visible(from, to);
  }

  /** Closed area portals for a new game on this map. */
  public AreaPortalState newAreaPortalState() {
    return new AreaPortalState(areaCount);
  }

  /**
   * Whether {@code p2} is in the PVS of {@code p1} and their areas are connected through open
   * portals ({@code trap_InPVS}).
   */
  public boolean inPVS(float[] p1, float[] p2, AreaPortalState portals) {
    int leaf1 = pointLeafnum(p1);
    int leaf2 = pointLeafnum(p2);
    if (!clusterVisible(leafCluster[leaf1], leafCluster[leaf2])) return false;
    return portals.connected(leafArea[leaf1], leafArea[leaf2]);
  }

  /**
   * Whether {@code p2} is in the PVS of {@code p1}, ignoring doors ({@code
   * trap_InPVSIgnorePortals}).
   */
  public boolean inPVSIgnorePortals(float[] p1, float[] p2) {
    return clusterVisible(leafCluster[pointLeafnum(p1)], leafCluster[pointLeafnum(p2)]);
  }

  /** A growable int list for building. */
  private static final class IntList {
    int[] values = new int[256];
    int size;

    void add(int value) {
      if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2);
      values[size++] = value;
    }

    int[] toArray() {
      return java.util.Arrays.copyOf(values, size);
    }
  }
}

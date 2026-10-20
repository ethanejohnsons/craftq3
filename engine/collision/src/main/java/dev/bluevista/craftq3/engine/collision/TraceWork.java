package dev.bluevista.craftq3.engine.collision;

import java.util.Arrays;

/**
 * Reusable scratch state for traces and point queries, the counterpart of the original {@code
 * traceWork_t} plus the per-brush and per-patch "already checked" counters. A work object belongs
 * to one thread at a time; every query against any {@link CollisionModel} may reuse it, and after
 * warm-up queries allocate nothing.
 *
 * <p>A work object also holds the temporary box or capsule made by {@link
 * CollisionModel#tempBoxModel(TraceWork, float[], float[], boolean)}, as the original engine holds
 * one global box model: the handle refers to the most recent temporary box of this work object.
 */
public final class TraceWork {
  // Trace configuration (in model space, positions are box centres).
  final float[] start = new float[3];
  final float[] end = new float[3];
  final float[] size0 = new float[3];
  final float[] size1 = new float[3];
  final float[] extents = new float[3];
  final float[] bounds0 = new float[3];
  final float[] bounds1 = new float[3];

  /** Box corners indexed by plane signbits: the corner that sits deepest behind such a plane. */
  final float[] offsets = new float[24];

  boolean isPoint;
  boolean positionTest;
  int mask;
  boolean sphere;
  float radius;
  float halfHeight;
  final float[] sphereOffset = new float[3];

  // The running result, merged in place as the original does.
  float fraction;
  boolean startSolid;
  boolean allSolid;
  final float[] normal = new float[3];
  float dist;
  int planeType;
  int planeSigns;
  int surfaceFlags;
  int contents;

  // "Already checked during this query" counters.
  Object boundTo;
  int[] brushChecks = new int[0];
  int[] patchChecks = new int[0];
  int checkCount;

  // Iterative tree walk: node, p1f, p2f and both segment end points per frame.
  int[] nodeStack = new int[64];
  float[] frameStack = new float[64 * 8];
  final int[] leafList = new int[CollisionModel.MAX_POSITION_LEAFS];
  final float[] leafMins = new float[3];
  final float[] leafMaxs = new float[3];
  int leafCount;
  int lastLeaf;

  // Point traces through patches: per-plane side and crossing time.
  boolean[] frontFacing = new boolean[0];
  float[] crossing = new float[0];

  // Temporary box models.
  final BrushTable tempBox = BrushTable.box();
  final float[] tempMins = new float[3];
  final float[] tempMaxs = new float[3];
  boolean tempCapsule;
  final BrushTable swapBox = BrushTable.box();
  final BrushTable foreignBox = BrushTable.box();

  // Transformed traces.
  final float[] rotation = new float[9];
  final float[] localStart = new float[3];
  final float[] localEnd = new float[3];
  final float[] symmetricMins = new float[3];
  final float[] symmetricMaxs = new float[3];
  final float[] scratch = new float[12];
  final float[] forward = new float[3];
  final float[] right = new float[3];
  final float[] up = new float[3];
  final Trace contactTrace = new Trace();
  final float[] contactPoint = new float[3];

  final Clipper clipper = new Clipper();

  /** Prepares the dedup counters for a query against {@code model}. */
  void begin(CollisionModel model) {
    if (boundTo != model) {
      boundTo = model;
      if (brushChecks.length < model.brushes.count()) brushChecks = new int[model.brushes.count()];
      else Arrays.fill(brushChecks, 0);
      if (patchChecks.length < model.patches.count) patchChecks = new int[model.patches.count];
      else Arrays.fill(patchChecks, 0);
      int planes = model.patches.maxPlanesPerPatch;
      if (frontFacing.length < planes) {
        frontFacing = new boolean[planes];
        crossing = new float[planes];
      }
      checkCount = 0;
    }
    nextCheck();
  }

  /** Starts a new "already checked" generation, as the original increments its check count. */
  void nextCheck() {
    if (++checkCount == Integer.MAX_VALUE) {
      Arrays.fill(brushChecks, 0);
      Arrays.fill(patchChecks, 0);
      checkCount = 1;
    }
  }

  void resetResult() {
    fraction = 1;
    startSolid = false;
    allSolid = false;
    normal[0] = 0;
    normal[1] = 0;
    normal[2] = 0;
    dist = 0;
    planeType = 0;
    planeSigns = 0;
    surfaceFlags = 0;
    contents = 0;
  }

  void ensureStack(int depth) {
    if (depth < nodeStack.length) return;
    nodeStack = Arrays.copyOf(nodeStack, nodeStack.length * 2);
    frameStack = Arrays.copyOf(frameStack, frameStack.length * 2);
  }

  /** The {@link TraceClipper} handed to {@link CollisionSource}s during a world trace. */
  final class Clipper implements TraceClipper {
    @Override
    public float sweptMin(int axis) {
      return bounds0[axis];
    }

    @Override
    public float sweptMax(int axis) {
      return bounds1[axis];
    }

    @Override
    public int contentMask() {
      return mask;
    }

    @Override
    public float fraction() {
      return fraction;
    }

    @Override
    public boolean positionTest() {
      return positionTest;
    }

    @Override
    public boolean isPoint() {
      return isPoint;
    }

    @Override
    public void clipBox(
        float minX,
        float minY,
        float minZ,
        float maxX,
        float maxY,
        float maxZ,
        int boxContents,
        int boxSurfaceFlags) {
      if ((boxContents & mask) == 0 || allSolid) return;
      foreignBox.setBox(minX, minY, minZ, maxX, maxY, maxZ, boxContents, boxSurfaceFlags);
      Clip.brush(TraceWork.this, foreignBox, 0);
    }
  }
}

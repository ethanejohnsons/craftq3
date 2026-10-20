package dev.bluevista.craftq3.engine.render;

import java.util.Arrays;

/**
 * The per-view output of {@link Visibility#select}: the world batches to draw, each as index runs
 * into {@link WorldGeometry#indices()} (adjacent visible surfaces merged), the visible surfaces
 * whose material needs CPU deformation (drawn from per-frame buffers instead), and statistics.
 * Reused across frames; selection allocates only when a frame needs more room than any before.
 */
public final class VisibleSet {
  private long[] surfaceBits = new long[0];
  private long[] candidateBits = new long[0];
  private int[] batches = new int[16];
  private int[] batchFirstRun = new int[16];
  private int[] batchRunCount = new int[16];
  private int batchCount;
  private int[] runFirstIndex = new int[64];
  private int[] runIndexCount = new int[64];
  private int runCount;
  private int[] cpuSurfaces = new int[16];
  private int cpuSurfaceCount;
  private int cameraLeaf = -1;
  private int cameraCluster = -1;
  private boolean pvsApplied;
  private int visibleLeaves;
  private int pvsSurfaceCount;
  private int visibleSurfaceCount;
  private int visibleIndexCount;

  void reset(int surfaceCount) {
    int words = (surfaceCount + 63) >>> 6;
    if (surfaceBits.length < words) {
      surfaceBits = new long[words];
      candidateBits = new long[words];
    } else {
      Arrays.fill(surfaceBits, 0, words, 0);
      Arrays.fill(candidateBits, 0, words, 0);
    }
    batchCount = runCount = cpuSurfaceCount = 0;
    visibleLeaves = pvsSurfaceCount = visibleSurfaceCount = visibleIndexCount = 0;
    cameraLeaf = cameraCluster = -1;
    pvsApplied = false;
  }

  void location(int leaf, int cluster, boolean pvs, int leaves, int pvsSurfaces) {
    cameraLeaf = leaf;
    cameraCluster = cluster;
    pvsApplied = pvs;
    visibleLeaves = leaves;
    pvsSurfaceCount = pvsSurfaces;
  }

  void beginBatch(int batch) {
    if (batchCount == batches.length) {
      batches = Arrays.copyOf(batches, batchCount * 2);
      batchFirstRun = Arrays.copyOf(batchFirstRun, batchCount * 2);
      batchRunCount = Arrays.copyOf(batchRunCount, batchCount * 2);
    }
    batches[batchCount] = batch;
    batchFirstRun[batchCount] = runCount;
    batchRunCount[batchCount] = 0;
    batchCount++;
  }

  /** Adds a surface's index range to the current batch, merging it with the previous run. */
  void addRange(int firstIndex, int indexCount) {
    int b = batchCount - 1;
    if (batchRunCount[b] > 0) {
      int last = runCount - 1;
      if (runFirstIndex[last] + runIndexCount[last] == firstIndex) {
        runIndexCount[last] += indexCount;
        visibleIndexCount += indexCount;
        return;
      }
    }
    if (runCount == runFirstIndex.length) {
      runFirstIndex = Arrays.copyOf(runFirstIndex, runCount * 2);
      runIndexCount = Arrays.copyOf(runIndexCount, runCount * 2);
    }
    runFirstIndex[runCount] = firstIndex;
    runIndexCount[runCount] = indexCount;
    runCount++;
    batchRunCount[b]++;
    visibleIndexCount += indexCount;
  }

  /** Drops the current batch when no run was added to it. */
  void endBatch() {
    if (batchCount > 0 && batchRunCount[batchCount - 1] == 0) batchCount--;
  }

  void markCandidate(int surface) {
    candidateBits[surface >>> 6] |= 1L << (surface & 63);
  }

  boolean candidate(int surface) {
    return (candidateBits[surface >>> 6] & (1L << (surface & 63))) != 0;
  }

  void markSurface(int surface) {
    surfaceBits[surface >>> 6] |= 1L << (surface & 63);
    visibleSurfaceCount++;
  }

  void addCpuSurface(int surface) {
    if (cpuSurfaceCount == cpuSurfaces.length)
      cpuSurfaces = Arrays.copyOf(cpuSurfaces, cpuSurfaceCount * 2);
    cpuSurfaces[cpuSurfaceCount++] = surface;
  }

  /** Visible world batches drawn from the static buffers. */
  public int batchCount() {
    return batchCount;
  }

  /** The {@code i}-th visible batch's index into {@link WorldGeometry#batches()}. */
  public int batch(int i) {
    return batches[i];
  }

  public int batchFirstRun(int i) {
    return batchFirstRun[i];
  }

  public int batchRunCount(int i) {
    return batchRunCount[i];
  }

  public int runCount() {
    return runCount;
  }

  public int runFirstIndex(int run) {
    return runFirstIndex[run];
  }

  public int runIndexCount(int run) {
    return runIndexCount[run];
  }

  /** Visible surfaces whose material deforms on the CPU; not part of any run. */
  public int cpuSurfaceCount() {
    return cpuSurfaceCount;
  }

  public int cpuSurface(int i) {
    return cpuSurfaces[i];
  }

  public boolean surfaceVisible(int surface) {
    int word = surface >>> 6;
    return surface >= 0
        && word < surfaceBits.length
        && (surfaceBits[word] & (1L << (surface & 63))) != 0;
  }

  public int visibleSurfaceCount() {
    return visibleSurfaceCount;
  }

  /** Indices in all runs (three per triangle). */
  public int visibleIndexCount() {
    return visibleIndexCount;
  }

  /** The leaf containing the visibility origin, or -1 outside the BSP. */
  public int cameraLeaf() {
    return cameraLeaf;
  }

  public int cameraCluster() {
    return cameraCluster;
  }

  /** False when PVS was disabled, absent or unusable from the origin (everything is candidate). */
  public boolean pvsApplied() {
    return pvsApplied;
  }

  public int visibleLeaves() {
    return visibleLeaves;
  }

  /** Surfaces that passed PVS and area tests, before frustum culling. */
  public int pvsSurfaceCount() {
    return pvsSurfaceCount;
  }
}

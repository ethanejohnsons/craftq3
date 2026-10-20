package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import java.util.List;

/**
 * World-surface selection for one view: BSP leaf lookup, cluster PVS, refdef area mask and
 * conservative box-frustum culling, producing {@link VisibleSet} runs over the static batches.
 * Immutable per map; every per-view value lives in the {@link VisibleSet}.
 *
 * <p>Faces no leaf references are always candidates. When PVS is disabled, missing, or the origin
 * is in solid space or outside the world, every leaf is a candidate. Leaves whose area the mask
 * hides are skipped. Surfaces whose material deforms vertices skip the frustum test, because their
 * static bounds do not contain the deformed geometry.
 */
public final class Visibility {
  private final BspMap map;
  private final WorldGeometry world;
  private final List<MaterialProgram> materials;
  private final long[] unreferenced;
  private final int[] surfaceClusters;

  public Visibility(BspMap map, WorldGeometry world, List<MaterialProgram> materials) {
    this.map = map;
    this.world = world;
    this.materials = List.copyOf(materials);
    int faces = world.surfaceCount();
    unreferenced = new long[(faces + 63) >>> 6];
    surfaceClusters = new int[faces];
    java.util.Arrays.fill(surfaceClusters, -1);
    for (int face = 0; face < faces; face++) unreferenced[face >>> 6] |= 1L << (face & 63);
    BspMap.IndexList leafFaces = map.leafFaces();
    BspMap.Leaves leaves = map.leaves();
    for (int leaf = 0; leaf < leaves.count(); leaf++)
      for (int j = 0; j < leaves.leafFaceCount(leaf); j++) {
        int face = leafFaces.get(leaves.firstLeafFace(leaf) + j);
        unreferenced[face >>> 6] &= ~(1L << (face & 63));
        if (surfaceClusters[face] < 0) surfaceClusters[face] = leaves.cluster(leaf);
      }
  }

  /** The cluster of the first leaf listing the surface, or -1. */
  public int clusterForSurface(int surface) {
    return surface < 0 || surface >= surfaceClusters.length ? -1 : surfaceClusters[surface];
  }

  /** Selects the world model's visible surfaces for {@code view}. */
  public void select(
      ViewDefinition view, boolean pvsEnabled, boolean frustumEnabled, VisibleSet out) {
    select(
        view.pvsOrigin(), view.areaMask(), frustumEnabled ? view.frustum() : null, pvsEnabled, out);
  }

  /**
   * Selects from {@code origin}; {@code frustum} may be null to keep every candidate. Model 0
   * batches only: inline models are drawn through their entities.
   */
  public void select(
      Vec3f origin, AreaMask areaMask, Frustum frustum, boolean pvsEnabled, VisibleSet out) {
    int surfaceCount = world.surfaceCount();
    out.reset(surfaceCount);
    int leaf = findLeaf(origin);
    BspMap.Leaves leaves = map.leaves();
    int cluster = leaf < 0 ? -1 : leaves.cluster(leaf);
    boolean usePvs =
        pvsEnabled
            && cluster >= 0
            && cluster < map.visibility().clusters()
            && map.leafFaces().count() > 0
            && insideWorld(origin);
    boolean everything = !usePvs && areaMask.size() == 0;
    int visibleLeaves = 0;
    if (everything) {
      visibleLeaves = leaves.count();
    } else {
      for (int i = 0; i < unreferenced.length; i++)
        for (long bits = unreferenced[i]; bits != 0; bits &= bits - 1)
          out.markCandidate(i * 64 + Long.numberOfTrailingZeros(bits));
      BspMap.IndexList leafFaces = map.leafFaces();
      for (int l = 0; l < leaves.count(); l++) {
        if (usePvs && !map.visibility().visible(cluster, leaves.cluster(l))) continue;
        if (areaMask.hidden(leaves.area(l))) continue;
        visibleLeaves++;
        for (int j = 0; j < leaves.leafFaceCount(l); j++)
          out.markCandidate(leafFaces.get(leaves.firstLeafFace(l) + j));
      }
    }
    int pvsSurfaces = 0;
    int first = world.modelCount() == 0 ? 0 : world.modelFirstBatch(0);
    int count = world.modelCount() == 0 ? 0 : world.modelBatchCount(0);
    List<DrawBatch> batches = world.batches();
    float[] bounds = world.surfaceBoundsArray();
    for (int b = first; b < first + count; b++) {
      DrawBatch batch = batches.get(b);
      MaterialProgram material = materials.get(batch.material());
      boolean cull = frustum != null && !material.deformsGeometry();
      boolean cpu = material.needsCpuDeform();
      if (!cpu) out.beginBatch(b);
      for (int k = batch.firstSurface(); k < batch.firstSurface() + batch.surfaceCount(); k++) {
        int surface = world.batchSurface(k);
        if (!everything && !out.candidate(surface)) continue;
        pvsSurfaces++;
        int at = surface * 6;
        if (cull
            && !frustum.intersects(
                bounds[at],
                bounds[at + 1],
                bounds[at + 2],
                bounds[at + 3],
                bounds[at + 4],
                bounds[at + 5])) continue;
        out.markSurface(surface);
        if (cpu) out.addCpuSurface(surface);
        else out.addRange(world.surfaceFirstIndex(surface), world.surfaceIndexCount(surface));
      }
      if (!cpu) out.endBatch();
    }
    out.location(leaf, cluster, usePvs, visibleLeaves, pvsSurfaces);
  }

  /** The leaf containing {@code point}, or -1 outside the tree. */
  public int findLeaf(Vec3f point) {
    BspMap.Leaves leaves = map.leaves();
    BspMap.Nodes nodes = map.nodes();
    BspMap.Planes planes = map.planes();
    if (leaves.count() == 0) return -1;
    if (nodes.count() == 0) return leaves.count() == 1 ? 0 : -1;
    int index = 0;
    for (int visited = 0; index >= 0 && visited <= nodes.count(); visited++) {
      if (index >= nodes.count()) return -1;
      int plane = nodes.plane(index);
      double distance =
          (double) point.x() * planes.normal(plane, 0)
              + (double) point.y() * planes.normal(plane, 1)
              + (double) point.z() * planes.normal(plane, 2)
              - planes.distance(plane);
      index = distance >= 0 ? nodes.front(index) : nodes.back(index);
    }
    if (index >= 0) return -1;
    long decoded = -(long) index - 1;
    return decoded < leaves.count() ? (int) decoded : -1;
  }

  private boolean insideWorld(Vec3f p) {
    if (!map.models().isEmpty()) {
      BspMap.Model world = map.models().getFirst();
      return p.x() >= world.mins().x()
          && p.y() >= world.mins().y()
          && p.z() >= world.mins().z()
          && p.x() <= world.maxs().x()
          && p.y() <= world.maxs().y()
          && p.z() <= world.maxs().z();
    }
    BspMap.Nodes nodes = map.nodes();
    if (nodes.count() == 0) return true;
    for (int axis = 0; axis < 3; axis++) {
      float value = p.get(axis);
      if (value < nodes.mins(0, axis) || value > nodes.maxs(0, axis)) return false;
    }
    return true;
  }
}

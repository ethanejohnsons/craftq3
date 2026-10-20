package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.util.List;
import java.util.Objects;

/**
 * The static geometry of every BSP model, built once per map as packed primitive buffers for a
 * single GPU upload. Vertex colours have the map's overbright headroom restored ({@link
 * #MAP_OVERBRIGHT_BITS}). Indices are 32-bit, grouped by {@link DrawBatch}; within a batch the
 * surfaces follow ascending BSP face order. Triangles wind clockwise when seen from the side their
 * vertex normals face (the Q3 front side).
 *
 * <p>Surfaces are indexed by BSP face number. Faces that are not drawn (nodraw, flares, bad faces)
 * have no batch; nodraw faces still keep their triangles, after the last batch, for decal clipping
 * and diagnostics.
 *
 * <p>The arrays returned by {@link #vertexArrays()} and {@link #indices()} are shared, not copied,
 * so per-frame consumers can read them without allocation. Callers must not modify them.
 */
public final class WorldGeometry {
  /** BSP vertex colours and lightmaps store light shifted right by this many bits. */
  public static final int MAP_OVERBRIGHT_BITS = 2;

  /** Texture surface flags (from the BSP texture lump) consulted by the renderer. */
  public static final int SURF_SKY = 0x4;

  public static final int SURF_NOIMPACT = 0x10;
  public static final int SURF_NOMARKS = 0x20;
  public static final int SURF_NODRAW = 0x80;
  public static final int SURF_NODLIGHT = 0x20000;

  static final int MODEL = 0, TYPE = 1, MATERIAL = 2, LIGHTMAP = 3, FOG = 4, BATCH = 5;
  static final int FIRST_VERTEX = 6, VERTEX_COUNT = 7, FIRST_INDEX = 8, INDEX_COUNT = 9;
  static final int FLAGS = 10;
  static final int SURFACE_INTS = 11;

  private final VertexArrays vertices;
  private final int vertexCount;
  private final int[] indices;
  private final int[] surfaces;
  private final float[] surfaceBounds;
  private final List<DrawBatch> batches;
  private final int[] batchSurfaces;
  private final int[] modelFirstBatch;
  private final int[] modelBatchCount;
  private final int unbatchedFirstIndex;

  WorldGeometry(
      VertexArrays vertices,
      int vertexCount,
      int[] indices,
      int[] surfaces,
      float[] surfaceBounds,
      List<DrawBatch> batches,
      int[] batchSurfaces,
      int[] modelFirstBatch,
      int[] modelBatchCount,
      int unbatchedFirstIndex) {
    this.vertices = Objects.requireNonNull(vertices);
    this.vertexCount = vertexCount;
    this.indices = indices;
    this.surfaces = surfaces;
    this.surfaceBounds = surfaceBounds;
    this.batches = List.copyOf(batches);
    this.batchSurfaces = batchSurfaces;
    this.modelFirstBatch = modelFirstBatch;
    this.modelBatchCount = modelBatchCount;
    this.unbatchedFirstIndex = unbatchedFirstIndex;
  }

  public int vertexCount() {
    return vertexCount;
  }

  public int indexCount() {
    return indices.length;
  }

  public int triangleCount() {
    return indices.length / 3;
  }

  /** Positions, normals, base and lightmap coordinates and RGBA colours; shared storage. */
  public VertexArrays vertexArrays() {
    return vertices;
  }

  /** Every index, batch by batch, then the unbatched nodraw triangles; shared storage. */
  public int[] indices() {
    return indices;
  }

  /** Where the indices of surfaces without a batch begin. */
  public int unbatchedFirstIndex() {
    return unbatchedFirstIndex;
  }

  public List<DrawBatch> batches() {
    return batches;
  }

  /** Surface ids of every batch, concatenated in batch order (see {@link DrawBatch}). */
  public int batchSurface(int position) {
    return batchSurfaces[position];
  }

  public int batchSurfaceCount() {
    return batchSurfaces.length;
  }

  public int modelCount() {
    return modelFirstBatch.length;
  }

  /** The batches of BSP model {@code model} are {@code batches()[first, first + count)}. */
  public int modelFirstBatch(int model) {
    return modelFirstBatch[model];
  }

  public int modelBatchCount(int model) {
    return modelBatchCount[model];
  }

  /** Number of surfaces, equal to the BSP face count. */
  public int surfaceCount() {
    return surfaces.length / SURFACE_INTS;
  }

  /** The BSP model owning the face, or -1 when no model lists it. */
  public int surfaceModel(int surface) {
    return surfaces[surface * SURFACE_INTS + MODEL];
  }

  public SurfaceType surfaceType(int surface) {
    return SurfaceType.of(surfaces[surface * SURFACE_INTS + TYPE]);
  }

  /** Index into {@link RenderScene#materials()}. */
  public int surfaceMaterial(int surface) {
    return surfaces[surface * SURFACE_INTS + MATERIAL];
  }

  public int surfaceLightmap(int surface) {
    return surfaces[surface * SURFACE_INTS + LIGHTMAP];
  }

  public int surfaceFog(int surface) {
    return surfaces[surface * SURFACE_INTS + FOG];
  }

  /** The surface's batch index, or -1 when it is not drawn. */
  public int surfaceBatch(int surface) {
    return surfaces[surface * SURFACE_INTS + BATCH];
  }

  public int surfaceFirstVertex(int surface) {
    return surfaces[surface * SURFACE_INTS + FIRST_VERTEX];
  }

  public int surfaceVertexCount(int surface) {
    return surfaces[surface * SURFACE_INTS + VERTEX_COUNT];
  }

  /** First index of the surface's triangles in {@link #indices()}. */
  public int surfaceFirstIndex(int surface) {
    return surfaces[surface * SURFACE_INTS + FIRST_INDEX];
  }

  public int surfaceIndexCount(int surface) {
    return surfaces[surface * SURFACE_INTS + INDEX_COUNT];
  }

  /** The BSP texture's surface flags ({@code SURF_*}). */
  public int surfaceFlags(int surface) {
    return surfaces[surface * SURFACE_INTS + FLAGS];
  }

  /** Bounds component {@code k}: 0..2 minimum xyz, 3..5 maximum xyz. */
  public float surfaceBound(int surface, int k) {
    return surfaceBounds[surface * 6 + k];
  }

  /** The surface bounds as six floats (min xyz, max xyz); shared storage, do not modify. */
  float[] surfaceBoundsArray() {
    return surfaceBounds;
  }
}

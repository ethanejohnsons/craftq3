package dev.bluevista.craftq3.engine.render;

/**
 * A contiguous index range of {@link WorldGeometry} sharing one material, lightmap page, fog volume
 * and BSP model, so a GPU backend draws it with one pipeline and texture binding. Its surfaces are
 * {@code world.batchSurfaces()[firstSurface, firstSurface + surfaceCount)} in index order.
 *
 * @param model the BSP model (0 is the world; others are inline models drawn by entities)
 * @param material index into {@link RenderScene#materials()}
 * @param lightmap lightmap page, -1 for none, or {@code BspMap.LIGHTMAP_BY_VERTEX}
 * @param fog fog volume index into {@code BspMap.effects()}, or -1
 * @param sky true for sky surfaces, which mark where the sky box shows rather than draw stages
 * @param sortRank {@code MaterialProgram.sortRank()} of the material
 */
public record DrawBatch(
    int index,
    int model,
    int material,
    int lightmap,
    int fog,
    int firstIndex,
    int indexCount,
    int firstSurface,
    int surfaceCount,
    boolean sky,
    int sortRank) {

  /**
   * A stable ordering key: sort rank, then material, lightmap and fog, so equal-state batches are
   * adjacent. Translucent ranks still need back-to-front ordering by the backend.
   */
  public long sortKey() {
    return (long) sortRank << 48
        | (long) (material & 0xffff) << 32
        | (long) ((lightmap + 4) & 0xffff) << 16
        | ((fog + 1) & 0xffff);
  }
}

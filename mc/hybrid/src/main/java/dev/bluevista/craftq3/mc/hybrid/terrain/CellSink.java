package dev.bluevista.craftq3.mc.hybrid.terrain;

/**
 * Receives one cell's description from {@link TerrainCells#describe}. Coordinates are block-local
 * Minecraft coordinates (+Y up). Calls may come in any order; a cell can report several solids, at
 * most one fluid, and properties that apply to all of its solids.
 */
public interface CellSink {
  /** A solid collision box ({@code CONTENTS_SOLID} to Quake). */
  void solid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

  /**
   * A fluid filling the cell from its floor to {@code height} blocks (Minecraft's {@code
   * FluidState.getHeight}, in {@code (0, 1]}).
   */
  void fluid(FluidKind kind, double height);

  /** The cell's solids are climbable: Quake movement treats them as ladder surfaces. */
  void climbable();

  /** Extra Quake surface flags ({@code SURF_*}, e.g. {@code SURF_SLICK} for ice) of its solids. */
  void surfaceFlags(int flags);

  /** A whole solid block, also used for unloaded cells. */
  default void full() {
    solid(0, 0, 0, 1, 1, 1);
  }
}

package dev.bluevista.craftq3.mc.fabric.bridge;

import dev.bluevista.craftq3.engine.collision.SurfaceFlags;
import dev.bluevista.craftq3.mc.hybrid.terrain.CellSink;
import dev.bluevista.craftq3.mc.hybrid.terrain.FluidKind;
import dev.bluevista.craftq3.mc.hybrid.terrain.TerrainCells;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * {@link TerrainCells} over a live Minecraft level, read on the level's own thread (the server
 * thread for qagame, the render thread for the client's cgame prediction).
 *
 * <ul>
 *   <li>Collision boxes are the block state's collision shape ({@code getCollisionShape} with the
 *       empty context, as for an entity standing nowhere special); full cubes report one box.
 *   <li>Water and lava fill the cell up to {@code FluidState.getHeight}.
 *   <li>Climbable blocks with a collision shape (ladders, scaffolding) are Quake ladders; ice is
 *       slick. Climbable blocks without one (vines) have nothing to climb in Quake movement.
 *   <li>Unloaded chunks are full blocks, so they act as walls; the cell layer below the level's
 *       bottom is a floor, so nobody falls forever.
 * </ul>
 */
public final class LevelTerrainCells implements TerrainCells {
  private final Level level;
  private final int minY;
  private final int maxY;
  private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

  /** Per-thread position and box forwarder, so describing a cell allocates nothing. */
  private static final class Scratch implements Shapes.DoubleLineConsumer {
    final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    CellSink sink;

    @Override
    public void consume(double x1, double y1, double z1, double x2, double y2, double z2) {
      sink.solid(x1, y1, z1, x2, y2, z2);
    }
  }

  public LevelTerrainCells(Level level) {
    this.level = Objects.requireNonNull(level, "level");
    this.minY = level.getMinY();
    this.maxY = level.getMaxY() + 1;
  }

  public Level level() {
    return level;
  }

  @Override
  public void describe(int x, int y, int z, CellSink sink) {
    if (y < minY) {
      if (y == minY - 1) sink.full();
      return;
    }
    if (y >= maxY) return;
    if (!level.hasChunk(x >> 4, z >> 4)) {
      sink.full();
      return;
    }
    Scratch s = scratch.get();
    BlockPos pos = s.pos.set(x, y, z);
    BlockState state = level.getBlockState(pos);
    if (state.isAir()) return;
    VoxelShape shape = state.getCollisionShape(level, pos);
    if (!shape.isEmpty()) {
      if (shape == Shapes.block()) sink.full();
      else {
        s.sink = sink;
        try {
          shape.forAllBoxes(s);
        } finally {
          s.sink = null;
        }
      }
      if (state.is(BlockTags.CLIMBABLE)) sink.climbable();
      if (state.is(BlockTags.ICE)) sink.surfaceFlags(SurfaceFlags.SLICK);
    }
    FluidState fluid = state.getFluidState();
    if (!fluid.isEmpty()) {
      FluidKind kind =
          fluid.is(FluidTags.LAVA)
              ? FluidKind.LAVA
              : fluid.is(FluidTags.WATER) ? FluidKind.WATER : null;
      if (kind != null) sink.fluid(kind, fluid.getHeight(level, pos));
    }
  }

  @Override
  public int minY() {
    return minY - 1;
  }

  @Override
  public int maxY() {
    return maxY;
  }
}

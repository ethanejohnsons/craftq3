package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;

/**
 * Live Minecraft collision shapes in Q3 units; callers serialize reads on the level's owner thread.
 */
public final class MinecraftTerrain implements TraceWorld {
  private final Level level;
  private final BlockPos anchor;
  private final CoordinateTransform transform;
  private final Map<GridTraceWorld.Cell, List<TraceWorld>> cache = new HashMap<>();
  private final GridTraceWorld grid;

  public MinecraftTerrain(Level level, BlockPos anchor, double unitsPerBlock) {
    this.level = Objects.requireNonNull(level);
    this.anchor = anchor.immutable();
    transform =
        new CoordinateTransform(
            unitsPerBlock, new Vec3(anchor.getX(), anchor.getY(), anchor.getZ()));
    grid = new GridTraceWorld(unitsPerBlock, this::shapes);
  }

  public CoordinateTransform transform() {
    return transform;
  }

  /** Discard cached shapes before each simulation/prediction frame so block edits take effect. */
  public void beginFrame() {
    cache.clear();
  }

  @Override
  public TraceResult trace(TraceRequest request) {
    return grid.trace(request);
  }

  @Override
  public int pointContents(Vec3 point, int mask, int ignore) {
    return grid.pointContents(point, mask, ignore);
  }

  private List<TraceWorld> shapes(GridTraceWorld.Cell cell) {
    return cache.computeIfAbsent(
        cell,
        key -> {
          if (cache.size() >= 262144)
            throw new IllegalStateException("Minecraft collision cache budget exceeded");
          BlockPos pos = anchor.offset(key.x(), key.z(), -key.y() - 1);
          var result = new ArrayList<TraceWorld>();
          // An unloaded chunk is a boundary, preventing movement/projectiles into unsimulated
          // terrain.
          if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            result.add(box(pos, 0, 0, 0, 1, 1, 1, 1));
            return List.copyOf(result);
          }
          var state = level.getBlockState(pos);
          for (var shape : state.getCollisionShape(level, pos).toAabbs())
            result.add(
                box(
                    pos,
                    shape.minX,
                    shape.minY,
                    shape.minZ,
                    shape.maxX,
                    shape.maxY,
                    shape.maxZ,
                    1));
          var fluid = state.getFluidState();
          int contents = fluid.is(FluidTags.LAVA) ? 8 : fluid.is(FluidTags.WATER) ? 32 : 0;
          if (contents != 0)
            result.add(box(pos, 0, 0, 0, 1, fluid.getHeight(level, pos), 1, contents));
          return List.copyOf(result);
        });
  }

  private TraceWorld box(
      BlockPos pos,
      double x0,
      double y0,
      double z0,
      double x1,
      double y1,
      double z1,
      int contents) {
    Vec3 a = transform.toQuake(new Vec3(pos.getX() + x0, pos.getY() + y0, pos.getZ() + z0));
    Vec3 b = transform.toQuake(new Vec3(pos.getX() + x1, pos.getY() + y1, pos.getZ() + z1));
    return new BoxTraceWorld(
        new Vec3(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
        new Vec3(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())),
        contents,
        0,
        1022);
  }
}

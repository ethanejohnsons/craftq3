package dev.bluevista.craftq3.mc.fabric.building;

import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import dev.bluevista.craftq3.mc.hybrid.building.BlockFace;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingQueries;
import dev.bluevista.craftq3.mc.hybrid.building.SupportShape;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The Minecraft side of the building rules: what the building mixins ask, answered from the level's
 * {@link LevelBuilding} (a field of the level, see {@link CraftQ3Level}). Every query is a no-op
 * outside an enabled region, so the mixins cost one field read elsewhere.
 */
public final class BuildingRules {
  private BuildingRules() {}

  /** The queries of the enabled region containing {@code x} in {@code level}, or null. */
  public static BuildingQueries at(Level level, double x) {
    return level == null ? null : CraftQ3Level.of(level).craftq3$building().at(x);
  }

  /** As {@link #at(Level, double)} for a block getter that is a level, else null. */
  public static BuildingQueries at(BlockGetter getter, double x) {
    return getter instanceof Level level ? at(level, x) : null;
  }

  // ---- movement -------------------------------------------------------------------------------

  /** {@code colliders} plus the BSP of the region around {@code region}, for one movement. */
  public static List<VoxelShape> withBsp(
      Entity entity, Level level, AABB region, List<VoxelShape> colliders) {
    if (entity == null || entity.noPhysics) return colliders;
    BuildingQueries queries = at(level, (region.minX + region.maxX) * 0.5);
    if (queries == null) return colliders;
    List<VoxelShape> out = new ArrayList<>(colliders.size() + 1);
    out.addAll(colliders);
    out.add(new BspMovementShape(queries, region, entity.maxUpStep()));
    return out;
  }

  /** True when {@code box} overlaps a BSP solid that blocks movement. */
  public static boolean blocksMovement(Level level, AABB box) {
    BuildingQueries queries = at(level, (box.minX + box.maxX) * 0.5);
    return queries != null
        && !queries.boxClear(
            box.minX,
            box.minY,
            box.minZ,
            box.maxX,
            box.maxY,
            box.maxZ,
            BuildingQueries.MOVEMENT_MASK);
  }

  /** True when {@code box} overlaps a BSP solid (blocks and fluids may not occupy it). */
  public static boolean blocksSolid(Level level, AABB box) {
    BuildingQueries queries = at(level, (box.minX + box.maxX) * 0.5);
    return queries != null
        && !queries.boxClear(
            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, BuildingQueries.SOLID_MASK);
  }

  /** True when BSP solid lies right under block {@code pos} (a falling block rests on it). */
  public static boolean restsOnBsp(Level level, BlockPos pos) {
    return blocksSolid(
        level,
        new AABB(
            pos.getX() + 0.25,
            pos.getY() - 0.05,
            pos.getZ() + 0.25,
            pos.getX() + 0.75,
            pos.getY() - 0.001,
            pos.getZ() + 0.75));
  }

  // ---- support --------------------------------------------------------------------------------

  /** True when BSP faces support {@code type} on {@code face} of block {@code pos}. */
  public static boolean supports(
      BlockGetter getter, BlockPos pos, Direction face, SupportType type) {
    BuildingQueries queries = at(getter, pos.getX() + 0.5);
    return queries != null
        && queries.supportsFace(
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            BlockFace.ofOrdinal(face.ordinal()),
            SupportShape.values()[type.ordinal()]);
  }

  // ---- occlusion ------------------------------------------------------------------------------

  /** True when BSP solid lies between the two points (sight, explosion exposure). */
  public static boolean occluded(Level level, Vec3 from, Vec3 to) {
    BuildingQueries queries = at(level, (from.x + to.x) * 0.5);
    return queries != null && queries.occludes(from.x, from.y, from.z, to.x, to.y, to.z);
  }

  /**
   * {@code original} (a block clip from {@code from} to {@code to}) or, when a BSP surface comes
   * first, a hit on that surface: its block position is the empty cell in front of the surface, so
   * projectiles stop there and a block placed against it fills that cell.
   */
  public static BlockHitResult clip(Level level, Vec3 from, Vec3 to, BlockHitResult original) {
    BuildingQueries queries = at(level, (from.x + to.x) * 0.5);
    if (queries == null) return original;
    double bsp = queries.rayFraction(from.x, from.y, from.z, to.x, to.y, to.z);
    if (bsp >= 1) return original;
    double length = from.distanceTo(to);
    if (original.getType() != HitResult.Type.MISS
        && length > 0
        && original.getLocation().distanceTo(from) / length <= bsp) return original;
    BuildingQueries.Placement placement =
        queries.pick(from.x, from.y, from.z, to.x, to.y, to.z, BuildingQueries.MOVEMENT_MASK);
    Vec3 hit = from.add(to.subtract(from).scale(bsp));
    if (placement == null) {
      Direction facing = Direction.getApproximateNearest(from.subtract(to));
      return new BlockHitResult(hit, facing, BlockPos.containing(hit), false);
    }
    Direction face = Direction.values()[placement.face().ordinal()];
    return new BlockHitResult(
        new Vec3(placement.x(), placement.y(), placement.z()),
        face,
        new BlockPos(placement.cellX(), placement.cellY(), placement.cellZ()),
        false);
  }

  // ---- buckets --------------------------------------------------------------------------------

  /**
   * A bucket's own ray ({@code Item.getPlayerPOVHitResult}, from {@code from} to {@code to}) with
   * BSP surfaces: {@code original} when a block or fluid comes first, otherwise a hit on the BSP
   * surface whose block position is the BSP side of the free cell in front of it (the cell must be
   * free of BSP solid), so {@code BucketItem} places into that cell ({@code pos.relative(face)})
   * and finds nothing to collect inside the BSP. A BSP surface with no free cell in front is a
   * miss.
   */
  public static BlockHitResult bucketClip(
      Level level, Vec3 from, Vec3 to, BlockHitResult original) {
    BuildingQueries queries = at(level, (from.x + to.x) * 0.5);
    if (queries == null) return original;
    double bsp = queries.rayFraction(from.x, from.y, from.z, to.x, to.y, to.z);
    if (bsp >= 1) return original;
    double length = from.distanceTo(to);
    if (original.getType() != HitResult.Type.MISS
        && length > 0
        && original.getLocation().distanceTo(from) / length <= bsp) return original;
    Vec3 hit = from.add(to.subtract(from).scale(bsp));
    BuildingQueries.Placement placement =
        queries.pick(from.x, from.y, from.z, to.x, to.y, to.z, BuildingQueries.SOLID_MASK);
    if (placement == null)
      return BlockHitResult.miss(
          hit, Direction.getApproximateNearest(from.subtract(to)), BlockPos.containing(hit));
    Direction face = Direction.values()[placement.face().ordinal()];
    BlockPos cell = new BlockPos(placement.cellX(), placement.cellY(), placement.cellZ());
    return new BlockHitResult(
        new Vec3(placement.x(), placement.y(), placement.z()),
        face,
        cell.relative(face.getOpposite()),
        false);
  }

  /** True when block cell {@code pos} overlaps BSP solid (no fluid or block may go there). */
  public static boolean cellInBsp(Level level, BlockPos pos) {
    BuildingQueries queries = at(level, pos.getX() + 0.5);
    return queries != null && !queries.cellClear(pos.getX(), pos.getY(), pos.getZ());
  }

  // ---- hanging entities -----------------------------------------------------------------------

  /**
   * The block a hanging item ({@code HangingEntityItem.useOn}) was used on: the crosshair's BSP
   * pick names the empty cell in front of the surface ({@link #clip}), so when that cell is empty
   * and a BSP face lies on {@code face} of the block behind it, the block behind is the one clicked
   * and the entity goes into the empty cell ({@code clicked.relative(face)}).
   */
  public static BlockPos hangingClicked(Level level, BlockPos clicked, Direction face) {
    BuildingQueries queries = at(level, clicked.getX() + 0.5);
    if (queries == null || !level.getBlockState(clicked).isAir()) return clicked;
    BlockPos behind = clicked.relative(face.getOpposite());
    return queries.touchesFace(
            behind.getX(), behind.getY(), behind.getZ(), BlockFace.ofOrdinal(face.ordinal()))
        ? behind
        : clicked;
  }

  // ---- navigation -----------------------------------------------------------------------------

  /** How far below a destination a BSP floor is looked for, in blocks. */
  private static final double DESTINATION_DROP = 64;

  /** How far above a destination inside BSP solid a free cell is looked for, in blocks. */
  private static final int DESTINATION_RISE = 8;

  /**
   * Ground navigation's destination ({@code GroundPathNavigation.findSurfacePosition}) with BSP
   * floors: Minecraft walks down an air column to the first block, which in a building region goes
   * straight through BSP floors (they are not blocks). The destination becomes the cell of the
   * highest BSP floor under {@code pos} (a floor off the block grid, say at y 63.25, gives cell 63)
   * unless a block surface ({@code surface}, Minecraft's answer) lies higher; a destination inside
   * BSP solid first moves up to the free cell above it.
   */
  public static BlockPos navigationSurface(Level level, BlockPos pos, BlockPos surface) {
    BuildingQueries queries = at(level, pos.getX() + 0.5);
    if (queries == null) return surface;
    double x = pos.getX() + 0.5, z = pos.getZ() + 0.5;
    int y = pos.getY();
    for (int rise = 0;
        rise < DESTINATION_RISE && !queries.cellClear(pos.getX(), y, pos.getZ());
        rise++) y++;
    if (!queries.cellClear(pos.getX(), y, pos.getZ())) return surface;
    double floor = queries.floorBelow(x, y + 1 - BuildingQueries.CELL_INSET, z, DESTINATION_DROP);
    if (Double.isNaN(floor)) return surface;
    // The cell whose path node stands on that floor (see floorIn).
    int floorCell = (int) Math.floor(floor + 0.5);
    return floorCell >= surface.getY() || surface.getY() > y
        ? new BlockPos(pos.getX(), floorCell, pos.getZ())
        : surface;
  }

  /**
   * The walkable BSP floor a mob in block cell {@code pos} stands on, as path nodes see it ({@code
   * BuildingWalkNodeMixin}): within half a block of the cell's bottom, on or off the block grid.
   * NaN when there is none.
   */
  public static double floorIn(Level level, BlockPos pos) {
    BuildingQueries queries = at(level, pos.getX() + 0.5);
    if (queries == null) return Double.NaN;
    return queries.floorBelow(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1.0);
  }

  // ---- fluids ---------------------------------------------------------------------------------

  /** True when fluid may flow from {@code source} into {@code target}. */
  public static boolean fluidCanFlow(BlockGetter getter, BlockPos source, BlockPos target) {
    BuildingQueries queries = at(getter, source.getX() + 0.5);
    return queries == null
        || queries.fluidCanFlow(
            source.getX(),
            source.getY(),
            source.getZ(),
            target.getX(),
            target.getY(),
            target.getZ());
  }
}

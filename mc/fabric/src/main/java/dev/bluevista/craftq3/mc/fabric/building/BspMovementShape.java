package dev.bluevista.craftq3.mc.fabric.building;

import dev.bluevista.craftq3.mc.hybrid.building.BuildingQueries;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The BSP as one more collider in an entity's movement resolution ({@code Entity.collide}): each
 * axis sweep is an exact Quake trace against the region's world model ({@link
 * BuildingQueries#clipAxis}), and its Y coordinates offer the BSP floor under the body as a step-up
 * candidate. It exists only for the duration of one movement and is never stored as level geometry.
 */
public final class BspMovementShape extends VoxelShape {
  private final BuildingQueries queries;
  private final AABB region;
  private final DoubleList heights;

  public BspMovementShape(BuildingQueries queries, AABB region, double maxStep) {
    this(queries, region, stepHeights(queries, region, maxStep));
  }

  private BspMovementShape(BuildingQueries queries, AABB region, DoubleList heights) {
    super(new BitSetDiscreteVoxelShape(1, heights.size() - 1, 1));
    this.queries = queries;
    this.region = region;
    this.heights = heights;
  }

  private static DoubleList stepHeights(BuildingQueries queries, AABB region, double maxStep) {
    DoubleArrayList values = new DoubleArrayList(4);
    values.add(region.minY);
    if (maxStep > 0) {
      double floor =
          queries.stepSurface(
              region.minX, region.minY, region.minZ, region.maxX, region.maxZ, maxStep);
      if (floor > region.minY && floor < region.maxY) values.add(floor);
    }
    values.add(region.maxY);
    values.sort(null);
    return values;
  }

  @Override
  public DoubleList getCoords(Direction.Axis axis) {
    return switch (axis) {
      case X -> DoubleList.of(region.minX, region.maxX);
      case Y -> heights;
      case Z -> DoubleList.of(region.minZ, region.maxZ);
    };
  }

  @Override
  public double collide(Direction.Axis axis, AABB body, double distance) {
    if (Math.abs(distance) < 1.0E-7) return 0;
    return queries.clipAxis(
        body.minX,
        body.minY,
        body.minZ,
        body.maxX,
        body.maxY,
        body.maxZ,
        axis.ordinal(),
        distance,
        BuildingQueries.MOVEMENT_MASK);
  }

  @Override
  public boolean isEmpty() {
    return false;
  }
}

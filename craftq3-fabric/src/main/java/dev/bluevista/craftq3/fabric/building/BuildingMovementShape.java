package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.core.math.Vec3;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import java.util.TreeSet;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Transient analytic adapter for Entity's movement resolver only. It contributes exact axis sweeps
 * and candidate support heights; it is never exposed as world voxel geometry.
 */
public final class BuildingMovementShape extends VoxelShape {
  private final BuildingGeometry geometry;
  private final AABB region;
  private final DoubleList heights;

  public BuildingMovementShape(BuildingGeometry geometry, AABB region, double maxStep) {
    this(geometry, region, stepHeights(geometry, region, maxStep));
  }

  private BuildingMovementShape(BuildingGeometry geometry, AABB region, DoubleList heights) {
    super(new BitSetDiscreteVoxelShape(1, heights.size() - 1, 1));
    this.geometry = geometry;
    this.region = region;
    this.heights = heights;
  }

  private static DoubleList stepHeights(BuildingGeometry geometry, AABB region, double maxStep) {
    var values = new TreeSet<Double>();
    values.add(region.minY);
    var support = geometry.stepSurface(box(region), maxStep);
    if (support.isPresent() && support.getAsDouble() > region.minY)
      values.add(support.getAsDouble());
    // Also try the native limit when a taller neighboring brush masks the downward probe.
    // Minecraft still checks headroom, horizontal improvement and grounded state.
    if (maxStep > 0) values.add(region.minY + maxStep);
    values.add(region.maxY);
    return new DoubleArrayList(values);
  }

  @Override
  public DoubleList getCoords(Axis axis) {
    return switch (axis) {
      case X -> DoubleList.of(region.minX, region.maxX);
      case Y -> heights;
      case Z -> DoubleList.of(region.minZ, region.maxZ);
    };
  }

  @Override
  public double collide(Axis axis, AABB body, double distance) {
    return geometry.axis(
        box(body),
        switch (axis) {
          case X -> new Vec3(distance, 0, 0);
          case Y -> new Vec3(0, distance, 0);
          case Z -> new Vec3(0, 0, distance);
        });
  }

  private static BuildingGeometry.Box box(AABB box) {
    return new BuildingGeometry.Box(
        new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ));
  }
}

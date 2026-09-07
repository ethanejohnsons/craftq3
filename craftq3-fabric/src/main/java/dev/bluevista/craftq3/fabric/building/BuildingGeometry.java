package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.util.Optional;

/** Exact BSP traces in Minecraft coordinates. No BSP-to-block conversion is involved. */
public final class BuildingGeometry {
  public record Box(Vec3 min, Vec3 max) {
    public Box moved(Vec3 delta) {
      return new Box(min.add(delta), max.add(delta));
    }
  }

  public record RayHit(Vec3 point, Vec3 normal) {}

  public record SurfaceHit(Vec3 point, Vec3 normal, Vec3 cell) {
    public Vec3 placementPoint() {
      return new Vec3(
          Math.clamp(point.x(), cell.x(), cell.x() + 1),
          Math.clamp(point.y(), cell.y(), cell.y() + 1),
          Math.clamp(point.z(), cell.z(), cell.z() + 1));
    }
  }

  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final TraceWorld bsp;
  private final CoordinateTransform transform;

  public BuildingGeometry(TraceWorld bsp, CoordinateTransform transform) {
    this.bsp = bsp;
    this.transform = transform;
  }

  public TraceResult trace(Box box, Vec3 delta) {
    return trace(box, delta, 0x10001);
  }

  private TraceResult trace(Box box, Vec3 delta, int contents) {
    var origin = transform.toQuake(box.min());
    var extent = box.max().add(box.min().scale(-1));
    double scale = transform.quakeUnitsPerBlock();
    var mins = new Vec3(0, -extent.z() * scale, 0);
    var maxs = new Vec3(extent.x() * scale, 0, extent.y() * scale);
    return bsp.trace(
        new TraceRequest(
            origin, transform.toQuake(box.min().add(delta)), mins, maxs, contents, -1));
  }

  /**
   * BSP-only geometry sweep. Native movement combines both layers through BuildingMovementShape.
   */
  public Vec3 clip(Box box, Vec3 movement) {
    double y = axis(box, new Vec3(0, movement.y(), 0));
    box = box.moved(new Vec3(0, y, 0));
    double x, z;
    if (Math.abs(movement.x()) < Math.abs(movement.z())) {
      z = axis(box, new Vec3(0, 0, movement.z()));
      box = box.moved(new Vec3(0, 0, z));
      x = axis(box, new Vec3(movement.x(), 0, 0));
    } else {
      x = axis(box, new Vec3(movement.x(), 0, 0));
      box = box.moved(new Vec3(x, 0, 0));
      z = axis(box, new Vec3(0, 0, movement.z()));
    }
    return new Vec3(x, y, z);
  }

  /** Clip a movement containing only one nonzero axis. */
  public double axis(Box box, Vec3 motion) {
    double length = motion.x() + motion.y() + motion.z();
    if (length == 0) return 0;
    var hit = trace(box, motion);
    return hit.startSolid() ? 0 : length * hit.fraction();
  }

  /** Highest BSP support under the swept footprint within Minecraft's step allowance. */
  public java.util.OptionalDouble stepSurface(Box region, double maxStep) {
    if (maxStep <= 0) return java.util.OptionalDouble.empty();
    double top = region.min().y() + maxStep;
    var footprint =
        new Box(
            new Vec3(region.min().x(), top, region.min().z()),
            new Vec3(region.max().x(), top, region.max().z()));
    var hit = trace(footprint, new Vec3(0, -maxStep, 0));
    if (hit.startSolid()
        || hit.fraction() >= 1
        || hit.hit().orElseThrow().plane().normal().z() < .5)
      return java.util.OptionalDouble.empty();
    return java.util.OptionalDouble.of(top - maxStep * hit.fraction());
  }

  /** Occupancy for embedded projectiles excludes player-only clipping volumes. */
  public boolean solidClear(Box box) {
    return !trace(box, ZERO, 1).startSolid();
  }

  /** Swept clearance for a path edge; clear endpoints alone do not prove a clear route. */
  public boolean pathClear(Box box, Vec3 delta) {
    var hit = trace(box, delta);
    return !hit.startSolid() && hit.fraction() == 1;
  }

  /** Ground under a native path node, whose Y is rounded from the standing height. */
  public java.util.OptionalDouble navigationFloor(double x, int y, double z) {
    var from = new Vec3(x, y + .499, z);
    var hit = trace(new Box(from, from), new Vec3(0, -1, 0));
    if (hit.startSolid()
        || hit.fraction() >= 1
        || hit.hit().orElseThrow().plane().normal().z() < .5)
      return java.util.OptionalDouble.empty();
    return java.util.OptionalDouble.of(from.y() - hit.fraction());
  }

  public boolean clear(Box box) {
    return !trace(box, ZERO).startSolid();
  }

  /** Blast visibility includes rays that start inside immutable solid geometry. */
  public boolean occludes(Vec3 start, Vec3 end) {
    var hit = bsp.trace(TraceRequest.ray(transform.toQuake(start), transform.toQuake(end), 1));
    return hit.startSolid() || hit.fraction() < 1;
  }

  /** Solid BSP ray independent of whether a Minecraft block can fit at the hit. */
  public Optional<RayHit> ray(Vec3 start, Vec3 end) {
    var hit = bsp.trace(TraceRequest.ray(transform.toQuake(start), transform.toQuake(end), 1));
    if (hit.fraction() >= 1 || hit.startSolid()) return Optional.empty();
    return Optional.of(
        new RayHit(
            transform.toMinecraft(hit.endPosition()),
            transform
                .directionToMinecraft(hit.hit().orElseThrow().plane().normal())
                .scale(transform.quakeUnitsPerBlock())));
  }

  /**
   * Select an empty grid cell entirely outside the solid face, including non-grid-aligned floors.
   */
  public Optional<SurfaceHit> pick(Vec3 eye, Vec3 end) {
    return pick(eye, end, 0x10001);
  }

  /** Fluid placement ignores player-only clipping volumes. */
  public Optional<SurfaceHit> pickSolid(Vec3 eye, Vec3 end) {
    return pick(eye, end, 1);
  }

  private Optional<SurfaceHit> pick(Vec3 eye, Vec3 end, int contents) {
    var hit = bsp.trace(TraceRequest.ray(transform.toQuake(eye), transform.toQuake(end), 1));
    if (hit.fraction() >= 1 || hit.startSolid()) return Optional.empty();
    var point = transform.toMinecraft(hit.endPosition());
    var n =
        transform
            .directionToMinecraft(hit.hit().orElseThrow().plane().normal())
            .scale(transform.quakeUnitsPerBlock());
    int axis =
        Math.abs(n.y()) >= Math.abs(n.x()) && Math.abs(n.y()) >= Math.abs(n.z())
            ? 1
            : Math.abs(n.x()) >= Math.abs(n.z()) ? 0 : 2;
    double[] cell = {Math.floor(point.x()), Math.floor(point.y()), Math.floor(point.z())};
    double[] pos = {point.x(), point.y(), point.z()}, normal = {n.x(), n.y(), n.z()};
    cell[axis] = normal[axis] > 0 ? Math.ceil(pos[axis] - 0.01) : Math.floor(pos[axis] + 0.01) - 1;
    // A sloped face can require moving further along its outward normal to fit a full block.
    for (int attempt = 0; attempt < 4; attempt++) {
      var lo = new Vec3(cell[0], cell[1], cell[2]);
      if (!trace(
              new Box(lo.add(new Vec3(.001, .001, .001)), lo.add(new Vec3(.999, .999, .999))),
              ZERO,
              contents)
          .startSolid()) return Optional.of(new SurfaceHit(point, n, lo));
      cell[axis] += normal[axis] > 0 ? 1 : -1;
    }
    return Optional.empty();
  }
}

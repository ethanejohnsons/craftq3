package dev.bluevista.craftq3.mc.hybrid.building;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.collision.TraceWork;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.Objects;

/**
 * The questions Minecraft's rules ask about an immutable BSP placed in a building region, in
 * Minecraft coordinates: whether a box is free of BSP solids (block placement, fluids, entity
 * bodies, path nodes), how far a box can move (entity collision), whether a segment is occluded
 * (sight, explosion exposure and propagation, fluid flow), where the floor is (navigation, step
 * height), which grid cell a ray picks, and whether a block face is supported ({@code
 * isFaceSturdy}).
 *
 * <p>Built once per map placement and immutable; every query is lock-free and, apart from {@link
 * #pick} and combined face support, allocation-free (per-thread scratch). Traces are exact Quake
 * III traces against the world model, curves included; inline models (doors, platforms) are not
 * part of the building world.
 */
public final class BuildingQueries {
  /** What entities (players, mobs, path nodes) collide with: solids and player clip. */
  public static final int MOVEMENT_MASK = Contents.SOLID | Contents.PLAYERCLIP;

  /** What blocks, fluids, projectiles, sight and explosions collide with: solids only. */
  public static final int SOLID_MASK = Contents.SOLID;

  /** {@code MIN_WALK_NORMAL}: steeper surfaces are not floors. */
  public static final float MIN_WALK_NORMAL = 0.7f;

  /** Block cells are tested shrunk by this much (blocks) so touching faces do not count. */
  public static final double CELL_INSET = 0.001;

  /** Statistics of a build. */
  public record Statistics(
      int alignedBrushFaces, int supportedBlockFaces, int fullySupportedFaces, long buildNanos) {}

  /**
   * A picked BSP surface and the empty grid cell outside it that a block placed there would fill.
   *
   * @param x the hit point
   * @param nx the surface's unit normal
   * @param cellX the outside cell
   * @param face the face of the cell's neighbour that was clicked: the cell is that neighbour moved
   *     along {@code face}
   */
  public record Placement(
      double x,
      double y,
      double z,
      double nx,
      double ny,
      double nz,
      int cellX,
      int cellY,
      int cellZ,
      BlockFace face) {}

  private final CollisionModel model;
  private final CoordinateTransform transform;
  private final FaceSupportIndex support;
  private final Statistics statistics;
  private final ThreadLocal<Scratch> scratch;

  private BuildingQueries(
      CollisionModel model, CoordinateTransform transform, FaceSupportIndex support, long nanos) {
    this.model = model;
    this.transform = transform;
    this.support = support;
    this.statistics =
        new Statistics(support.alignedFaces(), support.touchedFaces(), support.fullFaces(), nanos);
    this.scratch = ThreadLocal.withInitial(Scratch::new);
  }

  /** Builds the queries of {@code map} placed by {@code transform} (block-aligned origin). */
  public static BuildingQueries build(BspMap map, CoordinateTransform transform) {
    long start = System.nanoTime();
    return build(map, CollisionModel.build(map), transform, start);
  }

  /** Builds the queries over an existing collision model of {@code map}. */
  public static BuildingQueries build(
      BspMap map, CollisionModel model, CoordinateTransform transform) {
    return build(map, model, transform, System.nanoTime());
  }

  private static BuildingQueries build(
      BspMap map, CollisionModel model, CoordinateTransform transform, long start) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(transform, "transform");
    FaceSupportIndex support = FaceSupportIndex.build(map, transform);
    return new BuildingQueries(model, transform, support, System.nanoTime() - start);
  }

  public CoordinateTransform transform() {
    return transform;
  }

  public CollisionModel collision() {
    return model;
  }

  public Statistics statistics() {
    return statistics;
  }

  // ---- occupancy -----------------------------------------------------------------------------

  /** True when the box overlaps no BSP solid of {@code mask}. */
  public boolean boxClear(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int mask) {
    Scratch s = scratch.get();
    s.box(transform, minX, minY, minZ, maxX, maxY, maxZ);
    model.boxTrace(s.work(model), s.trace, s.start, s.start, s.mins, s.maxs, 0, mask, false);
    return !s.trace.startSolid();
  }

  /**
   * True when block cell {@code (x, y, z)} (shrunk by {@link #CELL_INSET}) is free of BSP solids: a
   * block or fluid may occupy it.
   */
  public boolean cellClear(int x, int y, int z) {
    return boxClear(
        x + CELL_INSET,
        y + CELL_INSET,
        z + CELL_INSET,
        x + 1 - CELL_INSET,
        y + 1 - CELL_INSET,
        z + 1 - CELL_INSET,
        SOLID_MASK);
  }

  /** The Quake contents at a Minecraft point. */
  public int pointContents(double x, double y, double z) {
    Scratch s = scratch.get();
    transform.toQuake(x, y, z, s.start);
    return model.pointContents(s.work(model), s.start, 0);
  }

  // ---- movement ------------------------------------------------------------------------------

  /**
   * The fraction of the displacement {@code (dx, dy, dz)} the box can sweep before touching a solid
   * of {@code mask}: 1 when clear, 0 when it starts inside one.
   */
  public double sweep(
      double minX,
      double minY,
      double minZ,
      double maxX,
      double maxY,
      double maxZ,
      double dx,
      double dy,
      double dz,
      int mask) {
    Scratch s = scratch.get();
    s.box(transform, minX, minY, minZ, maxX, maxY, maxZ);
    transform.toQuakeDirection(dx, dy, dz, s.end);
    for (int axis = 0; axis < 3; axis++) s.end[axis] += s.start[axis];
    model.boxTrace(s.work(model), s.trace, s.start, s.end, s.mins, s.maxs, 0, mask, false);
    if (s.trace.startSolid()) return 0;
    return s.trace.fraction();
  }

  /**
   * How far the box can move along Minecraft axis {@code axis} (0 X, 1 Y, 2 Z) toward {@code
   * delta}, for an entity collision pass that resolves one axis at a time. Contact stops a Quake
   * surface epsilon (1/256 block at 32 units per block) short, as Quake movement does.
   */
  public double clipAxis(
      double minX,
      double minY,
      double minZ,
      double maxX,
      double maxY,
      double maxZ,
      int axis,
      double delta,
      int mask) {
    if (delta == 0) return 0;
    double fraction =
        sweep(
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            axis == 0 ? delta : 0,
            axis == 1 ? delta : 0,
            axis == 2 ? delta : 0,
            mask);
    return delta * fraction;
  }

  /** True when the box can sweep the whole displacement: a path edge is passable. */
  public boolean pathClear(
      double minX,
      double minY,
      double minZ,
      double maxX,
      double maxY,
      double maxZ,
      double dx,
      double dy,
      double dz) {
    return sweep(minX, minY, minZ, maxX, maxY, maxZ, dx, dy, dz, MOVEMENT_MASK) == 1;
  }

  /**
   * The height of the highest walkable BSP surface under the box's footprint within {@code maxStep}
   * blocks below {@code minY + maxStep}, for step-up selection; NaN if none.
   */
  public double stepSurface(
      double minX, double minY, double minZ, double maxX, double maxZ, double maxStep) {
    if (!(maxStep > 0)) return Double.NaN;
    double top = minY + maxStep;
    Scratch s = scratch.get();
    s.box(transform, minX, top, minZ, maxX, top, maxZ);
    transform.toQuakeDirection(0, -maxStep, 0, s.end);
    for (int axis = 0; axis < 3; axis++) s.end[axis] += s.start[axis];
    model.boxTrace(s.work(model), s.trace, s.start, s.end, s.mins, s.maxs, 0, MOVEMENT_MASK, false);
    Trace t = s.trace;
    if (t.startSolid() || t.fraction() >= 1 || t.planeNormal(2) < MIN_WALK_NORMAL)
      return Double.NaN;
    return top - maxStep * t.fraction();
  }

  /**
   * The walkable floor under the point within {@code maxDrop} blocks (path nodes, destinations), or
   * NaN.
   */
  public double floorBelow(double x, double y, double z, double maxDrop) {
    Scratch s = scratch.get();
    transform.toQuake(x, y, z, s.start);
    transform.toQuake(x, y - maxDrop, z, s.end);
    model.boxTrace(s.work(model), s.trace, s.start, s.end, null, null, 0, MOVEMENT_MASK, false);
    Trace t = s.trace;
    if (t.startSolid() || t.fraction() >= 1 || t.planeNormal(2) < MIN_WALK_NORMAL)
      return Double.NaN;
    return transform.minecraftY(t.endPos(2));
  }

  // ---- occlusion -----------------------------------------------------------------------------

  /**
   * True when solid BSP lies on the segment (sight, explosion exposure and propagation). A segment
   * starting inside solid counts as occluded.
   */
  public boolean occludes(double x0, double y0, double z0, double x1, double y1, double z1) {
    Scratch s = scratch.get();
    transform.toQuake(x0, y0, z0, s.start);
    transform.toQuake(x1, y1, z1, s.end);
    model.boxTrace(s.work(model), s.trace, s.start, s.end, null, null, 0, SOLID_MASK, false);
    return s.trace.startSolid() || s.trace.fraction() < 1;
  }

  /**
   * True when fluid may flow from cell {@code from} into its neighbour {@code to}: the target is
   * free of BSP solids and no BSP barrier lies between the two cell centres.
   */
  public boolean fluidCanFlow(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
    return cellClear(toX, toY, toZ)
        && !occludes(fromX + 0.5, fromY + 0.5, fromZ + 0.5, toX + 0.5, toY + 0.5, toZ + 0.5);
  }

  /**
   * The distance along the segment (as a fraction) to the first solid BSP surface, 1 when clear, 0
   * when starting inside solid. Projectiles compare this with their block hit.
   */
  public double rayFraction(double x0, double y0, double z0, double x1, double y1, double z1) {
    Scratch s = scratch.get();
    transform.toQuake(x0, y0, z0, s.start);
    transform.toQuake(x1, y1, z1, s.end);
    model.boxTrace(s.work(model), s.trace, s.start, s.end, null, null, 0, SOLID_MASK, false);
    return s.trace.startSolid() ? 0 : s.trace.fraction();
  }

  // ---- picking -------------------------------------------------------------------------------

  /**
   * The BSP surface the segment hits and the free grid cell outside it (including above a floor
   * that is not on the grid), or null. {@code mask} is what the cell must be free of: {@link
   * #MOVEMENT_MASK} for blocks, {@link #SOLID_MASK} for fluids.
   */
  public Placement pick(
      double x0, double y0, double z0, double x1, double y1, double z1, int mask) {
    Scratch s = scratch.get();
    transform.toQuake(x0, y0, z0, s.start);
    transform.toQuake(x1, y1, z1, s.end);
    model.boxTrace(s.work(model), s.trace, s.start, s.end, null, null, 0, SOLID_MASK, false);
    Trace t = s.trace;
    if (t.startSolid() || t.fraction() >= 1) return null;
    double[] hit = new double[3];
    transform.toMinecraft(t.endPos(0), t.endPos(1), t.endPos(2), hit);
    double[] n = new double[3];
    CoordinateTransform.normalToMinecraft(t.planeNormal(0), t.planeNormal(1), t.planeNormal(2), n);
    int axis =
        Math.abs(n[1]) >= Math.abs(n[0]) && Math.abs(n[1]) >= Math.abs(n[2])
            ? 1
            : Math.abs(n[0]) >= Math.abs(n[2]) ? 0 : 2;
    int sign = n[axis] > 0 ? 1 : -1;
    int[] cell = {(int) Math.floor(hit[0]), (int) Math.floor(hit[1]), (int) Math.floor(hit[2])};
    cell[axis] =
        sign > 0 ? (int) Math.ceil(hit[axis] - 0.01) : (int) Math.floor(hit[axis] + 0.01) - 1;
    // A sloped surface can need a further cell along its normal before a whole block fits.
    for (int attempt = 0; attempt < 4; attempt++) {
      if (boxClear(
          cell[0] + CELL_INSET,
          cell[1] + CELL_INSET,
          cell[2] + CELL_INSET,
          cell[0] + 1 - CELL_INSET,
          cell[1] + 1 - CELL_INSET,
          cell[2] + 1 - CELL_INSET,
          mask))
        return new Placement(
            hit[0],
            hit[1],
            hit[2],
            n[0],
            n[1],
            n[2],
            cell[0],
            cell[1],
            cell[2],
            BlockFace.of(axis, sign));
      cell[axis] += sign;
    }
    return null;
  }

  // ---- support -------------------------------------------------------------------------------

  /** True when BSP faces alone support {@code shape} on {@code face} of block {@code (x, y, z)}. */
  public boolean supportsFace(int x, int y, int z, BlockFace face, SupportShape shape) {
    return (support.bits(x, y, z, face) & FaceSupportIndex.bit(shape)) != 0;
  }

  /** True when any BSP face lies on {@code face} of block {@code (x, y, z)}. */
  public boolean touchesFace(int x, int y, int z, BlockFace face) {
    return (support.bits(x, y, z, face) & FaceSupportIndex.TOUCHED_BIT) != 0;
  }

  /**
   * True when BSP faces together with the block's own face shape support {@code shape}: {@code
   * host[0 .. 4 * hostCount)} holds the block's face rectangles {@code u0, v0, u1, v1} in
   * block-local face coordinates (see {@link BlockFace}), e.g. a slab's face shape. Combining
   * allocates only when the BSP covers the face partly.
   */
  public boolean supportsFace(
      int x, int y, int z, BlockFace face, SupportShape shape, double[] host, int hostCount) {
    return support.supports(x, y, z, face, shape, host, hostCount);
  }

  FaceSupportIndex supportIndex() {
    return support;
  }

  /** Per-thread trace scratch. */
  private static final class Scratch {
    final Trace trace = new Trace();
    final float[] start = new float[3];
    final float[] end = new float[3];
    final float[] mins = new float[3];
    final float[] maxs = new float[3];
    TraceWork work;
    CollisionModel owner;

    TraceWork work(CollisionModel model) {
      if (owner != model) {
        owner = model;
        work = model.work();
      }
      return work;
    }

    /** Sets start to the box centre and mins/maxs to its half extents, in Quake units. */
    void box(
        CoordinateTransform t,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ) {
      t.boxToQuake(minX, minY, minZ, maxX, maxY, maxZ, mins, maxs);
      for (int axis = 0; axis < 3; axis++) {
        float centre = (mins[axis] + maxs[axis]) * 0.5f;
        float half = (maxs[axis] - mins[axis]) * 0.5f;
        start[axis] = centre;
        mins[axis] = -half;
        maxs[axis] = half;
      }
    }
  }
}

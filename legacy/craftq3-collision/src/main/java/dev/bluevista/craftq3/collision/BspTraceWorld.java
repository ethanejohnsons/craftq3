package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable Q3 collision geometry. Queries use private scratch state and are thread-safe. */
public final class BspTraceWorld implements TraceWorld {
  private static final int MAX_PLANES = 4_000_000;
  private static final int MAX_PATCH_FACETS = 200_000;
  private static final int MAX_CACHED_MODEL_PLANES = 1_000_000;
  private final BspMap map;
  private final int subdivisions;
  private final List<TraceResult.Plane> planes;
  private final ModelData world;
  private final BitSet unreferencedBrushes, unreferencedPatches;
  private final Map<ModelKey, TraceWorld> models = new LinkedHashMap<>();
  private final Map<GeometryKey, ModelData> modelGeometry = new LinkedHashMap<>();
  private int cachedModelPlanes;

  public BspTraceWorld(BspMap map) {
    this(map, 8);
  }

  public BspTraceWorld(BspMap map, int patchSubdivisions) {
    this.map = Objects.requireNonNull(map, "map");
    if (patchSubdivisions < 1 || patchSubdivisions > 16)
      throw new IllegalArgumentException("Patch subdivisions must be 1..16");
    subdivisions = patchSubdivisions;
    planes = map.planes().stream().map(BspTraceWorld::plane).toList();
    world = compile(0, Contents.WORLD_ENTITY, RigidTransform.IDENTITY, false);
    unreferencedBrushes = new BitSet(map.brushes().size());
    for (int brush : world.brushes().keySet()) unreferencedBrushes.set(brush);
    for (int brush : map.leafBrushes()) unreferencedBrushes.clear(brush);
    unreferencedPatches = new BitSet(map.faces().size());
    for (int face : world.patches().keySet()) unreferencedPatches.set(face);
    for (int face : map.leafFaces()) unreferencedPatches.clear(face);
  }

  public record Statistics(
      int brushes, int patchFaces, int patchFacets, int planes, int patchSubdivisions) {}

  public Statistics statistics() {
    return new Statistics(
        world.brushes().size(),
        world.patches().size(),
        world.facetCount(),
        world.planeCount(),
        subdivisions);
  }

  /** Returns an inline BSP model at the linked entity's Q3 origin and pitch/yaw/roll angles. */
  public synchronized TraceWorld model(int index, int entity, Vec3 origin, Vec3 angles) {
    if (index < 0 || index >= map.models().size())
      throw new IllegalArgumentException("Invalid inline BSP model index");
    var key = new ModelKey(index, entity, origin, angles);
    TraceWorld cached = models.get(key);
    if (cached != null) return cached;
    boolean rotated = angles.x() % 360 != 0 || angles.y() % 360 != 0 || angles.z() % 360 != 0;
    if (CollisionMath.maxAbs(origin) > 1e12)
      throw new IllegalArgumentException("Model origin exceeds numeric range");
    var geometryKey = new GeometryKey(index, entity, angles);
    ModelData data = modelGeometry.get(geometryKey);
    if (data == null) {
      data = compile(index, entity, new RigidTransform(CollisionMath.ZERO, angles), rotated);
      if (modelGeometry.size() >= 128
          || (long) cachedModelPlanes + data.planeCount() > MAX_CACHED_MODEL_PLANES) {
        modelGeometry.clear();
        models.clear();
        cachedModelPlanes = 0;
      }
      if (data.planeCount() <= MAX_CACHED_MODEL_PLANES) {
        modelGeometry.put(geometryKey, data);
        cachedModelPlanes += data.planeCount();
      }
    }
    TraceWorld result = new InlineWorld(data, origin);
    if (data.planeCount() <= MAX_CACHED_MODEL_PLANES) {
      if (models.size() >= 128) models.remove(models.keySet().iterator().next());
      models.put(key, result);
    }
    return result;
  }

  @Override
  public TraceResult trace(TraceRequest request) {
    if (request.ignoreEntity() == Contents.WORLD_ENTITY || request.contentsMask() == 0)
      return TraceResult.clear(request);
    Candidates candidates = candidates(request);
    return trace(world, request, candidates.brushes(), candidates.patches());
  }

  @Override
  public int pointContents(Vec3 point, int mask, int ignoreEntity) {
    if (ignoreEntity == Contents.WORLD_ENTITY || mask == 0) return 0;
    var candidates = candidates(TraceRequest.ray(point, point, mask));
    int result = 0;
    for (int brush = candidates.brushes().nextSetBit(0);
        brush >= 0;
        brush = candidates.brushes().nextSetBit(brush + 1)) {
      var shape = world.brushes().get(brush);
      if (shape != null) result |= ConvexTrace.contents(shape, point, mask, ignoreEntity);
    }
    // Patches have no enclosed volume and do not contribute to point contents.
    return result;
  }

  /**
   * Whether a planar cardinal rectangle is covered by coplanar outward solid-brush faces and
   * rectangular host supports. Curved patches and playerclip do not supply block support.
   */
  public boolean supportsFace(Vec3 min, Vec3 max, Vec3 normal, List<BspMap.Bounds> hostSupports) {
    var coverage = new FaceCoverage(min, max, normal);
    for (var box : hostSupports) {
      coverage.subtract(FaceCoverage.boxPlanes(box.min(), box.max()));
      if (coverage.covered()) return true;
    }
    double distance = CollisionMath.dot(normal, min);
    var padding = new Vec3(.00001, .00001, .00001);
    var query =
        TraceRequest.box(
            min.add(padding.scale(-1)),
            min.add(padding.scale(-1)),
            CollisionMath.ZERO,
            CollisionMath.subtract(max, min).add(padding.scale(2)),
            Contents.SOLID);
    var brushes = candidates(query).brushes();
    for (int index = brushes.nextSetBit(0); index >= 0; index = brushes.nextSetBit(index + 1)) {
      var brush = world.brushes().get(index);
      if (brush == null || (brush.metadata().contents() & Contents.SOLID) == 0) continue;
      var face =
          brush.sides().stream()
              .filter(
                  side ->
                      CollisionMath.length(CollisionMath.subtract(side.plane().normal(), normal))
                              < .000001
                          && Math.abs(side.plane().distance() - distance) < .00001)
              .findFirst();
      if (face.isEmpty()) continue;
      coverage.subtract(
          brush.sides().stream()
              .filter(side -> side != face.get())
              .map(ConvexTrace.Side::plane)
              .toList());
      if (coverage.covered()) return true;
    }
    return false;
  }

  private Candidates candidates(TraceRequest request) {
    if (map.nodes().isEmpty()) return new Candidates(world.brushIds(), world.patchIds());
    BitSet brushes = (BitSet) unreferencedBrushes.clone(),
        patches = (BitSet) unreferencedPatches.clone();
    var visited = new BitSet(map.nodes().size());
    var visitedLeaves = new BitSet(map.leaves().size());
    var pending = new ArrayDeque<Integer>();
    pending.push(0);
    while (!pending.isEmpty()) {
      int index = pending.pop();
      if (index < 0) {
        int leafIndex = -index - 1;
        if (visitedLeaves.get(leafIndex)) continue;
        visitedLeaves.set(leafIndex);
        var leaf = map.leaves().get(leafIndex);
        for (int i = 0; i < leaf.brushCount(); i++)
          brushes.set(map.leafBrushes().get(leaf.firstBrush() + i));
        for (int i = 0; i < leaf.faceCount(); i++) {
          int face = map.leafFaces().get(leaf.firstFace() + i);
          if (world.patches().containsKey(face)) patches.set(face);
        }
        continue;
      }
      if (visited.get(index)) continue;
      visited.set(index);
      var node = map.nodes().get(index);
      var plane = planes.get(node.plane());
      double a = plane.signedDistance(request.start()), b = plane.signedDistance(request.end());
      double min = CollisionMath.minSupport(plane.normal(), request.mins(), request.maxs());
      double max = CollisionMath.maxSupport(plane.normal(), request.mins(), request.maxs());
      if (Math.min(a, b) + min > CollisionMath.CONTACT_EPSILON) pending.push(node.front());
      else if (Math.max(a, b) + max < -CollisionMath.CONTACT_EPSILON) pending.push(node.back());
      else {
        pending.push(node.back());
        pending.push(node.front());
      }
    }
    return new Candidates(brushes, patches);
  }

  private ModelData compile(int model, int entity, RigidTransform transform, boolean rotated) {
    int firstBrush = 0,
        brushCount = map.brushes().size(),
        firstFace = 0,
        faceCount = map.faces().size();
    if (!map.models().isEmpty()) {
      var source = map.models().get(model);
      firstBrush = source.firstBrush();
      brushCount = source.brushCount();
      firstFace = source.firstFace();
      faceCount = source.faceCount();
    }
    var brushes = new LinkedHashMap<Integer, ConvexTrace.Shape>();
    var patches = new LinkedHashMap<Integer, List<PatchCollision.Facet>>();
    int planeCount = 0, facetCount = 0;
    for (int index = firstBrush; index < firstBrush + brushCount; index++) {
      var brush = map.brushes().get(index);
      if (brush.sideCount() == 0) continue;
      if ((long) planeCount + brush.sideCount() > MAX_PLANES)
        throw new IllegalArgumentException("Collision plane budget exceeded");
      var sides = new ArrayList<ConvexTrace.Side>();
      for (int i = 0; i < brush.sideCount(); i++) {
        var side = map.brushSides().get(brush.firstSide() + i);
        var texture = map.textures().get(side.texture());
        sides.add(
            new ConvexTrace.Side(planes.get(side.plane()), texture.flags(), i, texture.name()));
      }
      var metadata =
          new ConvexTrace.Metadata(
              map.textures().get(brush.texture()).contents(), entity, model, index, -1);
      var shape =
          GeometrySupport.transform(
              new ConvexTrace.Shape(sides, metadata), transform, rotated, entity, model);
      planeCount = Math.addExact(planeCount, shape.sides().size());
      if (planeCount > MAX_PLANES)
        throw new IllegalArgumentException("Collision plane budget exceeded");
      brushes.put(index, shape);
    }
    for (int index = firstFace; index < firstFace + faceCount; index++) {
      var face = map.faces().get(index);
      if (face.type() != 2) continue;
      var texture = map.textures().get(face.texture());
      if (texture.contents() == 0 || (texture.flags() & 0x4000) != 0) continue;
      long potential =
          (long) ((face.patchWidth() - 1) / 2)
              * ((face.patchHeight() - 1) / 2)
              * subdivisions
              * subdivisions
              * 2;
      if (potential + facetCount > MAX_PATCH_FACETS)
        throw new IllegalArgumentException("Collision patch facet budget exceeded");
      var facets = PatchCollision.build(map, index, subdivisions, entity, model, transform);
      facetCount += facets.size();
      for (var facet : facets) planeCount = Math.addExact(planeCount, facet.shape().sides().size());
      if (planeCount > MAX_PLANES)
        throw new IllegalArgumentException("Collision plane budget exceeded");
      if (!facets.isEmpty()) patches.put(index, facets);
    }
    return new ModelData(Map.copyOf(brushes), Map.copyOf(patches), facetCount, planeCount);
  }

  private static TraceResult trace(
      ModelData model, TraceRequest request, BitSet brushes, BitSet patches) {
    var accumulator = new Accumulator(request);
    for (int index = brushes.nextSetBit(0); index >= 0; index = brushes.nextSetBit(index + 1)) {
      var shape = model.brushes().get(index);
      if (shape != null) accumulator.add(ConvexTrace.trace(shape, request));
    }
    for (int index = patches.nextSetBit(0); index >= 0; index = patches.nextSetBit(index + 1)) {
      var facets = model.patches().get(index);
      if (facets != null) for (var facet : facets) accumulator.add(facet.trace(request));
    }
    return accumulator.result();
  }

  private static TraceResult.Plane plane(BspMap.Plane plane) {
    double length = CollisionMath.length(plane.normal());
    if (length < 1e-12)
      throw new IllegalArgumentException("Collision BSP contains a zero plane normal");
    return new TraceResult.Plane(plane.normal().scale(1 / length), plane.distance() / length);
  }

  private record ModelKey(int model, int entity, Vec3 origin, Vec3 angles) {}

  private record GeometryKey(int model, int entity, Vec3 angles) {}

  private record Candidates(BitSet brushes, BitSet patches) {}

  private record ModelData(
      Map<Integer, ConvexTrace.Shape> brushes,
      Map<Integer, List<PatchCollision.Facet>> patches,
      int facetCount,
      int planeCount) {
    BitSet brushIds() {
      var result = new BitSet();
      for (int id : brushes.keySet()) result.set(id);
      return result;
    }

    BitSet patchIds() {
      var result = new BitSet();
      for (int id : patches.keySet()) result.set(id);
      return result;
    }
  }

  private static final class Accumulator {
    private TraceResult closest;
    private boolean start, all;

    Accumulator(TraceRequest request) {
      closest = TraceResult.clear(request);
    }

    void add(TraceResult result) {
      start |= result.startSolid();
      all |= result.allSolid();
      if ((result.allSolid() && !closest.allSolid()) || result.fraction() < closest.fraction())
        closest = result;
    }

    TraceResult result() {
      return new TraceResult(closest.fraction(), closest.endPosition(), start, all, closest.hit());
    }
  }

  private static final class InlineWorld implements TraceWorld {
    private final ModelData model;
    private final Vec3 origin;
    private final BitSet brushes, patches;

    InlineWorld(ModelData model, Vec3 origin) {
      this.model = model;
      this.origin = origin;
      brushes = model.brushIds();
      patches = model.patchIds();
    }

    @Override
    public TraceResult trace(TraceRequest request) {
      var local =
          new TraceRequest(
              CollisionMath.subtract(request.start(), origin),
              CollisionMath.subtract(request.end(), origin),
              request.mins(),
              request.maxs(),
              request.contentsMask(),
              request.ignoreEntity());
      TraceResult result = BspTraceWorld.trace(model, local, brushes, patches);
      var hit =
          result
              .hit()
              .map(
                  value -> {
                    var plane = value.plane();
                    var translated =
                        new TraceResult.Plane(
                            plane.normal(),
                            plane.distance() + CollisionMath.dot(plane.normal(), origin));
                    return new TraceResult.Hit(
                        translated,
                        value.contents(),
                        value.surfaceFlags(),
                        value.entity(),
                        value.model(),
                        value.brush(),
                        value.side(),
                        value.face(),
                        value.shaderName());
                  });
      return new TraceResult(
          result.fraction(),
          CollisionMath.lerp(request.start(), request.end(), result.fraction()),
          result.startSolid(),
          result.allSolid(),
          hit);
    }

    @Override
    public int pointContents(Vec3 point, int mask, int ignore) {
      Vec3 local = CollisionMath.subtract(point, origin);
      int result = 0;
      for (var shape : model.brushes().values())
        result |= ConvexTrace.contents(shape, local, mask, ignore);
      return result;
    }
  }
}

package dev.bluevista.craftq3.assets.aas;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Immutable AAS navigation asset. Coordinates and travel metadata retain their original Q3 units.
 */
public record AasMap(
    int version,
    int bspChecksum,
    List<Lump> lumps,
    List<BoundingBox> boundingBoxes,
    List<Vec3> vertices,
    List<Plane> planes,
    List<Edge> edges,
    Indices edgeIndices,
    List<Face> faces,
    Indices faceIndices,
    List<Area> areas,
    List<AreaSettings> areaSettings,
    List<Reachability> reachabilities,
    List<Node> nodes,
    List<Portal> portals,
    Indices portalIndices,
    List<Cluster> clusters) {
  public AasMap {
    lumps = List.copyOf(lumps);
    boundingBoxes = List.copyOf(boundingBoxes);
    vertices = List.copyOf(vertices);
    planes = List.copyOf(planes);
    edges = List.copyOf(edges);
    faces = List.copyOf(faces);
    areas = List.copyOf(areas);
    areaSettings = List.copyOf(areaSettings);
    reachabilities = List.copyOf(reachabilities);
    nodes = List.copyOf(nodes);
    portals = List.copyOf(portals);
    clusters = List.copyOf(clusters);
  }

  public enum LumpKind {
    BOUNDING_BOXES(32, 64),
    VERTICES(12, 262144),
    PLANES(20, 131072),
    EDGES(8, 524288),
    EDGE_INDICES(4, 2097152),
    FACES(24, 262144),
    FACE_INDICES(4, 1048576),
    AREAS(48, 65536),
    AREA_SETTINGS(28, 65536),
    REACHABILITIES(44, 1048576),
    NODES(12, 262144),
    PORTALS(20, 65536),
    PORTAL_INDICES(4, 131072),
    CLUSTERS(16, 65536);
    private final int stride, maxElements;

    LumpKind(int stride, int maxElements) {
      this.stride = stride;
      this.maxElements = maxElements;
    }

    public int stride() {
      return stride;
    }

    public int maxElements() {
      return maxElements;
    }
  }

  public record Lump(LumpKind kind, int offset, int length, int count) {}

  public record BoundingBox(int presenceType, int flags, Vec3 min, Vec3 max) {}

  public record Plane(Vec3 normal, float distance, int type) {}

  public record Edge(int startVertex, int endVertex) {}

  public record Face(
      int plane, int flags, int edgeCount, int firstEdge, int frontArea, int backArea) {}

  public record Area(int number, int faceCount, int firstFace, Vec3 min, Vec3 max, Vec3 center) {}

  public record AreaSettings(
      int contents,
      int flags,
      int presenceType,
      int cluster,
      int clusterArea,
      int reachabilityCount,
      int firstReachability) {}

  /**
   * face/edge are raw fields: elevators, jump pads and bobbing movers repurpose them as travel
   * data.
   */
  public record Reachability(
      int area,
      int face,
      int edge,
      Vec3 start,
      Vec3 end,
      int travelType,
      int travelTime,
      int reserved) {
    public int baseTravelType() {
      return travelType & 0x00ffffff;
    }

    public int travelFlags() {
      return travelType & 0xff000000;
    }

    public Optional<TravelType> kind() {
      return TravelType.fromId(baseTravelType());
    }

    public boolean hasGeometryReferences() {
      int type = baseTravelType();
      return type >= 2 && type <= 17 && type != TravelType.ELEVATOR.id();
    }
  }

  public enum TravelType {
    INVALID(1),
    WALK(2),
    CROUCH(3),
    BARRIER_JUMP(4),
    JUMP(5),
    LADDER(6),
    WALK_OFF_LEDGE(7),
    SWIM(8),
    WATER_JUMP(9),
    TELEPORT(10),
    ELEVATOR(11),
    ROCKET_JUMP(12),
    BFG_JUMP(13),
    GRAPPLE_HOOK(14),
    DOUBLE_JUMP(15),
    RAMP_JUMP(16),
    STRAFE_JUMP(17),
    JUMP_PAD(18),
    FUNC_BOB(19);

    private final int id;

    TravelType(int id) {
      this.id = id;
    }

    public int id() {
      return id;
    }

    public static Optional<TravelType> fromId(int id) {
      return id >= 1 && id <= values().length ? Optional.of(values()[id - 1]) : Optional.empty();
    }
  }

  /** Positive children name nodes, negative children name areas, zero denotes solid space. */
  public record Node(int plane, int front, int back) {}

  public record Portal(
      int area, int frontCluster, int backCluster, int frontClusterArea, int backClusterArea) {}

  public record Cluster(
      int areaCount, int reachabilityAreaCount, int portalCount, int firstPortal) {}

  /** Compact immutable signed index array; accessors never expose mutable storage. */
  public static final class Indices {
    private final int[] values;

    public Indices(int[] values) {
      this.values = values.clone();
    }

    public int size() {
      return values.length;
    }

    public int get(int index) {
      return values[index];
    }

    public int[] toArray() {
      return values.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Indices indices && Arrays.equals(values, indices.values);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
      return "Indices[size=" + values.length + "]";
    }
  }
}

package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.AasMap;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Botlib's typed view of a loaded AAS file: one immutable record per area, face, reachability and
 * so on, built once from the format layer's {@link AasMap} by {@link #of}. Botlib services index
 * these lists by the original 1-based numbers (entry 0 is the file's dummy record).
 */
public record AasWorld(
    int version,
    int bspChecksum,
    List<AasMap.LumpInfo> lumps,
    List<AasMap.BoundingBox> boundingBoxes,
    List<Vec3f> vertices,
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
  public AasWorld {
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

  public record Plane(Vec3f normal, float distance, int type) {}

  public record Edge(int startVertex, int endVertex) {}

  public record Face(
      int plane, int flags, int edgeCount, int firstEdge, int frontArea, int backArea) {}

  public record Area(
      int number, int faceCount, int firstFace, Vec3f min, Vec3f max, Vec3f center) {}

  public record AreaSettings(
      int contents,
      int flags,
      int presenceType,
      int cluster,
      int clusterArea,
      int reachabilityCount,
      int firstReachability) {}

  /**
   * One reachability. {@code face} and {@code edge} are raw fields: elevators, jump pads and
   * bobbing platforms reuse them as travel data. {@code travelType} is the raw travel field, with
   * the base type in the low 24 bits and team flags above.
   */
  public record Reachability(
      int area,
      int face,
      int edge,
      Vec3f start,
      Vec3f end,
      int travelType,
      int travelTime,
      int reserved) {
    /** {@code TRAVELFLAG_NOTTEAM1}: team one may not use this reachability. */
    public static final int NOT_TEAM_1 = 0x0100_0000;

    /** {@code TRAVELFLAG_NOTTEAM2}: team two may not use this reachability. */
    public static final int NOT_TEAM_2 = 0x0200_0000;

    public int baseTravelType() {
      return travelType & TravelType.TYPE_MASK;
    }

    public int travelFlags() {
      return travelType & ~TravelType.TYPE_MASK;
    }

    public Optional<TravelType> kind() {
      return TravelType.fromId(baseTravelType());
    }

    /** The base travel type, or {@link TravelType#INVALID} for numbers outside the enum. */
    public TravelType travel() {
      return TravelType.fromId(baseTravelType()).orElse(TravelType.INVALID);
    }

    public boolean is(TravelType type) {
      return baseTravelType() == type.id();
    }

    public boolean hasGeometryReferences() {
      return TravelType.hasGeometryReferences(baseTravelType());
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

  /** Builds the typed view of a parsed file. */
  public static AasWorld of(AasMap map) {
    var vertices = new ArrayList<Vec3f>(map.vertices().count());
    for (int i = 0; i < map.vertices().count(); i++) vertices.add(map.vertices().get(i));
    var planes = new ArrayList<Plane>(map.planes().count());
    for (int i = 0; i < map.planes().count(); i++)
      planes.add(new Plane(map.planes().normal(i), map.planes().distance(i), map.planes().type(i)));
    var edgeRecords = map.edges();
    var edges = new ArrayList<Edge>(edgeRecords.count());
    for (int i = 0; i < edgeRecords.count(); i++)
      edges.add(
          new Edge(edgeRecords.get(i, AasMap.EDGE_START), edgeRecords.get(i, AasMap.EDGE_END)));
    var faceRecords = map.faces();
    var faces = new ArrayList<Face>(faceRecords.count());
    for (int i = 0; i < faceRecords.count(); i++)
      faces.add(
          new Face(
              faceRecords.get(i, AasMap.FACE_PLANE),
              faceRecords.get(i, AasMap.FACE_FLAGS),
              faceRecords.get(i, AasMap.FACE_EDGE_COUNT),
              faceRecords.get(i, AasMap.FACE_FIRST_EDGE),
              faceRecords.get(i, AasMap.FACE_FRONT_AREA),
              faceRecords.get(i, AasMap.FACE_BACK_AREA)));
    var areaRecords = map.areas();
    var areas = new ArrayList<Area>(areaRecords.count());
    for (int i = 0; i < areaRecords.count(); i++)
      areas.add(
          new Area(
              areaRecords.number(i),
              areaRecords.faceCount(i),
              areaRecords.firstFace(i),
              areaRecords.mins(i),
              areaRecords.maxs(i),
              areaRecords.center(i)));
    var settingRecords = map.areaSettings();
    var settings = new ArrayList<AreaSettings>(settingRecords.count());
    for (int i = 0; i < settingRecords.count(); i++)
      settings.add(
          new AreaSettings(
              settingRecords.get(i, AasMap.SETTINGS_CONTENTS),
              settingRecords.get(i, AasMap.SETTINGS_FLAGS),
              settingRecords.get(i, AasMap.SETTINGS_PRESENCE),
              settingRecords.get(i, AasMap.SETTINGS_CLUSTER),
              settingRecords.get(i, AasMap.SETTINGS_CLUSTER_AREA),
              settingRecords.get(i, AasMap.SETTINGS_REACH_COUNT),
              settingRecords.get(i, AasMap.SETTINGS_FIRST_REACH)));
    var reachRecords = map.reachabilities();
    var reaches = new ArrayList<Reachability>(reachRecords.count());
    for (int i = 0; i < reachRecords.count(); i++)
      reaches.add(
          new Reachability(
              reachRecords.area(i),
              reachRecords.face(i),
              reachRecords.edge(i),
              reachRecords.start(i),
              reachRecords.end(i),
              reachRecords.travelField(i),
              reachRecords.travelTime(i),
              reachRecords.reserved(i)));
    var nodeRecords = map.nodes();
    var nodes = new ArrayList<Node>(nodeRecords.count());
    for (int i = 0; i < nodeRecords.count(); i++)
      nodes.add(
          new Node(
              nodeRecords.get(i, AasMap.NODE_PLANE),
              nodeRecords.get(i, AasMap.NODE_FRONT),
              nodeRecords.get(i, AasMap.NODE_BACK)));
    var portalRecords = map.portals();
    var portals = new ArrayList<Portal>(portalRecords.count());
    for (int i = 0; i < portalRecords.count(); i++)
      portals.add(
          new Portal(
              portalRecords.get(i, AasMap.PORTAL_AREA),
              portalRecords.get(i, AasMap.PORTAL_FRONT_CLUSTER),
              portalRecords.get(i, AasMap.PORTAL_BACK_CLUSTER),
              portalRecords.get(i, AasMap.PORTAL_FRONT_CLUSTER_AREA),
              portalRecords.get(i, AasMap.PORTAL_BACK_CLUSTER_AREA)));
    var clusterRecords = map.clusters();
    var clusters = new ArrayList<Cluster>(clusterRecords.count());
    for (int i = 0; i < clusterRecords.count(); i++)
      clusters.add(
          new Cluster(
              clusterRecords.get(i, AasMap.CLUSTER_AREA_COUNT),
              clusterRecords.get(i, AasMap.CLUSTER_REACH_AREA_COUNT),
              clusterRecords.get(i, AasMap.CLUSTER_PORTAL_COUNT),
              clusterRecords.get(i, AasMap.CLUSTER_FIRST_PORTAL)));
    return new AasWorld(
        map.version(),
        map.bspChecksum(),
        map.lumps(),
        map.boundingBoxes(),
        vertices,
        planes,
        edges,
        new Indices(map.edgeIndices().toArray()),
        faces,
        new Indices(map.faceIndices().toArray()),
        areas,
        settings,
        reaches,
        nodes,
        portals,
        new Indices(map.portalIndices().toArray()),
        clusters);
  }
}

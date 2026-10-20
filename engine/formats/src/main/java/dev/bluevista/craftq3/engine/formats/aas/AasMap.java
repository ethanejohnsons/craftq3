package dev.bluevista.craftq3.engine.formats.aas;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable EAAS (version 4 or 5) navigation file in Quake III units. Lumps are held in
 * primitive arrays behind read-only views. Every index botlib follows was range-checked by {@link
 * AasReader}; consistency between redundant fields is checked only by {@link
 * AasValidator#strictIssues}.
 */
public final class AasMap {
  public static final int IDENT = 0x53414145; // "EAAS"
  public static final int VERSION_OLD = 4;
  public static final int VERSION = 5;

  /** {@code AREACONTENTS_CLUSTERPORTAL}: the area is a portal between clusters. */
  public static final int AREACONTENTS_CLUSTERPORTAL = 8;

  private final int version;
  private final int bspChecksum;
  private final List<LumpInfo> lumps;
  private final List<BoundingBox> boundingBoxes;
  private final Vectors vertices;
  private final Planes planes;
  private final Records edges;
  private final Records edgeIndices;
  private final Records faces;
  private final Records faceIndices;
  private final Areas areas;
  private final Records areaSettings;
  private final Reachabilities reachabilities;
  private final Records nodes;
  private final Records portals;
  private final Records portalIndices;
  private final Records clusters;
  private final List<String> diagnostics;

  AasMap(Parts p) {
    this.version = p.version;
    this.bspChecksum = p.bspChecksum;
    this.lumps = List.copyOf(p.lumps);
    this.boundingBoxes = List.copyOf(p.boundingBoxes);
    this.vertices = Objects.requireNonNull(p.vertices);
    this.planes = Objects.requireNonNull(p.planes);
    this.edges = Objects.requireNonNull(p.edges);
    this.edgeIndices = Objects.requireNonNull(p.edgeIndices);
    this.faces = Objects.requireNonNull(p.faces);
    this.faceIndices = Objects.requireNonNull(p.faceIndices);
    this.areas = Objects.requireNonNull(p.areas);
    this.areaSettings = Objects.requireNonNull(p.areaSettings);
    this.reachabilities = Objects.requireNonNull(p.reachabilities);
    this.nodes = Objects.requireNonNull(p.nodes);
    this.portals = Objects.requireNonNull(p.portals);
    this.portalIndices = Objects.requireNonNull(p.portalIndices);
    this.clusters = Objects.requireNonNull(p.clusters);
    this.diagnostics = List.copyOf(p.diagnostics);
  }

  /** Assembly area for the reader. */
  static final class Parts {
    int version;
    int bspChecksum;
    List<LumpInfo> lumps = List.of();
    List<BoundingBox> boundingBoxes = List.of();
    Vectors vertices;
    Planes planes;
    Records edges;
    Records edgeIndices;
    Records faces;
    Records faceIndices;
    Areas areas;
    Records areaSettings;
    Reachabilities reachabilities;
    Records nodes;
    Records portals;
    Records portalIndices;
    Records clusters;
    List<String> diagnostics = List.of();
  }

  public int version() {
    return version;
  }

  /** The {@code Com_BlockChecksum} of the BSP this file was compiled from. */
  public int bspChecksum() {
    return bspChecksum;
  }

  public List<LumpInfo> lumps() {
    return lumps;
  }

  public List<BoundingBox> boundingBoxes() {
    return boundingBoxes;
  }

  public Vectors vertices() {
    return vertices;
  }

  public Planes planes() {
    return planes;
  }

  /** Per edge: {@link #EDGE_START}, {@link #EDGE_END} vertex. */
  public Records edges() {
    return edges;
  }

  /** Signed edge references: the sign gives the direction of use. */
  public Records edgeIndices() {
    return edgeIndices;
  }

  /** Per face: plane, flags, edge count, first edge index, front area, back area. */
  public Records faces() {
    return faces;
  }

  /** Signed face references: positive when the area is on the face's front. */
  public Records faceIndices() {
    return faceIndices;
  }

  public Areas areas() {
    return areas;
  }

  /**
   * Per area: contents, flags, presence type, cluster (negative: portal number), cluster-area
   * number, reachability count, first reachability.
   */
  public Records areaSettings() {
    return areaSettings;
  }

  public Reachabilities reachabilities() {
    return reachabilities;
  }

  /** Per node: plane, front child, back child. Positive children are nodes, negative areas. */
  public Records nodes() {
    return nodes;
  }

  /** Per portal: area, front cluster, back cluster, front cluster-area, back cluster-area. */
  public Records portals() {
    return portals;
  }

  public Records portalIndices() {
    return portalIndices;
  }

  /** Per cluster: area count, reachability area count, portal count, first portal index. */
  public Records clusters() {
    return clusters;
  }

  /** Lenient-read notes, such as overlapping lumps. */
  public List<String> diagnostics() {
    return diagnostics;
  }

  // Field positions within the generic record views.
  public static final int EDGE_START = 0;
  public static final int EDGE_END = 1;
  public static final int FACE_PLANE = 0;
  public static final int FACE_FLAGS = 1;
  public static final int FACE_EDGE_COUNT = 2;
  public static final int FACE_FIRST_EDGE = 3;
  public static final int FACE_FRONT_AREA = 4;
  public static final int FACE_BACK_AREA = 5;
  public static final int SETTINGS_CONTENTS = 0;
  public static final int SETTINGS_FLAGS = 1;
  public static final int SETTINGS_PRESENCE = 2;
  public static final int SETTINGS_CLUSTER = 3;
  public static final int SETTINGS_CLUSTER_AREA = 4;
  public static final int SETTINGS_REACH_COUNT = 5;
  public static final int SETTINGS_FIRST_REACH = 6;
  public static final int NODE_PLANE = 0;
  public static final int NODE_FRONT = 1;
  public static final int NODE_BACK = 2;
  public static final int PORTAL_AREA = 0;
  public static final int PORTAL_FRONT_CLUSTER = 1;
  public static final int PORTAL_BACK_CLUSTER = 2;
  public static final int PORTAL_FRONT_CLUSTER_AREA = 3;
  public static final int PORTAL_BACK_CLUSTER_AREA = 4;
  public static final int CLUSTER_AREA_COUNT = 0;
  public static final int CLUSTER_REACH_AREA_COUNT = 1;
  public static final int CLUSTER_PORTAL_COUNT = 2;
  public static final int CLUSTER_FIRST_PORTAL = 3;

  public record LumpInfo(AasLump kind, int offset, int length, int count) {}

  public record BoundingBox(int presenceType, int flags, Vec3f mins, Vec3f maxs) {}

  /** Fixed-stride integer records. */
  public static final class Records {
    private final int[] data;
    private final int stride;

    Records(int[] data, int stride) {
      this.data = data;
      this.stride = stride;
    }

    public int count() {
      return data.length / stride;
    }

    public int stride() {
      return stride;
    }

    public int get(int record, int field) {
      Objects.checkIndex(field, stride);
      return data[record * stride + field];
    }

    /** For single-field records (index lumps). */
    public int get(int record) {
      return data[record * stride];
    }

    public int[] toArray() {
      return data.clone();
    }
  }

  /** Packed float 3-vectors. */
  public static final class Vectors {
    private final float[] data;

    Vectors(float[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / 3;
    }

    public float get(int vector, int axis) {
      Objects.checkIndex(axis, 3);
      return data[vector * 3 + axis];
    }

    public Vec3f get(int vector) {
      return new Vec3f(data[vector * 3], data[vector * 3 + 1], data[vector * 3 + 2]);
    }

    public float[] toArray() {
      return data.clone();
    }
  }

  /** Planes: normal, distance and the axial type (0..2 axial, 3..5 nearest axis). */
  public static final class Planes {
    private final float[] data;
    private final int[] types;

    Planes(float[] data, int[] types) {
      this.data = data;
      this.types = types;
    }

    public int count() {
      return types.length;
    }

    public float normal(int plane, int axis) {
      Objects.checkIndex(axis, 3);
      return data[plane * 4 + axis];
    }

    public Vec3f normal(int plane) {
      return new Vec3f(data[plane * 4], data[plane * 4 + 1], data[plane * 4 + 2]);
    }

    public float distance(int plane) {
      return data[plane * 4 + 3];
    }

    public int type(int plane) {
      return types[plane];
    }
  }

  /** Areas: number, face count, first face index, and float mins, maxs and center. */
  public static final class Areas {
    private final int[] ints;
    private final float[] floats;

    Areas(int[] ints, float[] floats) {
      this.ints = ints;
      this.floats = floats;
    }

    public int count() {
      return ints.length / 3;
    }

    public int number(int area) {
      return ints[area * 3];
    }

    public int faceCount(int area) {
      return ints[area * 3 + 1];
    }

    public int firstFace(int area) {
      return ints[area * 3 + 2];
    }

    public float mins(int area, int axis) {
      Objects.checkIndex(axis, 3);
      return floats[area * 9 + axis];
    }

    public float maxs(int area, int axis) {
      Objects.checkIndex(axis, 3);
      return floats[area * 9 + 3 + axis];
    }

    public float center(int area, int axis) {
      Objects.checkIndex(axis, 3);
      return floats[area * 9 + 6 + axis];
    }

    public Vec3f mins(int area) {
      return vector(area * 9);
    }

    public Vec3f maxs(int area) {
      return vector(area * 9 + 3);
    }

    public Vec3f center(int area) {
      return vector(area * 9 + 6);
    }

    private Vec3f vector(int base) {
      return new Vec3f(floats[base], floats[base + 1], floats[base + 2]);
    }
  }

  /**
   * Reachabilities: target area, face and edge (raw: elevators, jump pads and bobbing platforms
   * reuse them as travel data), start and end points, the travel field (type plus flags), travel
   * time and the reserved half-word.
   */
  public static final class Reachabilities {
    static final int INTS = 6;
    private final int[] ints;
    private final float[] floats;

    Reachabilities(int[] ints, float[] floats) {
      this.ints = ints;
      this.floats = floats;
    }

    public int count() {
      return ints.length / INTS;
    }

    public int area(int reach) {
      return ints[reach * INTS];
    }

    public int face(int reach) {
      return ints[reach * INTS + 1];
    }

    public int edge(int reach) {
      return ints[reach * INTS + 2];
    }

    /** The raw travel field: base type in the low 24 bits, flags above. */
    public int travelField(int reach) {
      return ints[reach * INTS + 3];
    }

    public int baseTravelType(int reach) {
      return travelField(reach) & TravelType.TYPE_MASK;
    }

    public int travelFlags(int reach) {
      return travelField(reach) & ~TravelType.TYPE_MASK;
    }

    public Optional<TravelType> kind(int reach) {
      return TravelType.fromId(baseTravelType(reach));
    }

    public boolean hasGeometryReferences(int reach) {
      return TravelType.hasGeometryReferences(baseTravelType(reach));
    }

    /** Travel time in hundredths of a second (unsigned 16-bit). */
    public int travelTime(int reach) {
      return ints[reach * INTS + 4];
    }

    public int reserved(int reach) {
      return ints[reach * INTS + 5];
    }

    public float start(int reach, int axis) {
      Objects.checkIndex(axis, 3);
      return floats[reach * 6 + axis];
    }

    public float end(int reach, int axis) {
      Objects.checkIndex(axis, 3);
      return floats[reach * 6 + 3 + axis];
    }

    public Vec3f start(int reach) {
      return new Vec3f(floats[reach * 6], floats[reach * 6 + 1], floats[reach * 6 + 2]);
    }

    public Vec3f end(int reach) {
      return new Vec3f(floats[reach * 6 + 3], floats[reach * 6 + 4], floats[reach * 6 + 5]);
    }
  }
}

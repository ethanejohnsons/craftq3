package dev.bluevista.craftq3.engine.formats.bsp;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An immutable IBSP 46/47 map in Quake III units. Bulk lumps are held in primitive arrays behind
 * read-only views ({@link Planes}, {@link Nodes}, {@link Vertices}, ...); small lumps are records.
 * Every cross-reference a consumer would follow (node children, leaf and model ranges, brush sides,
 * face vertices and mesh indices) was checked by {@link BspReader}.
 */
public final class BspMap {
  public static final int IDENT = 0x50534249; // "IBSP"
  public static final int VERSION_Q3 = 46;

  /** The RTCW header version; its lump layout is identical, and Quake III 1.32 refuses it. */
  public static final int VERSION_EXTENDED = 47;

  public static final int LIGHTMAP_SIZE = 128;
  public static final int LIGHTMAP_BYTES = LIGHTMAP_SIZE * LIGHTMAP_SIZE * 3;

  /** The {@code lightmapNum} sentinel of a surface lit by vertex colours. */
  public static final int LIGHTMAP_BY_VERTEX = -3;

  private final int version;
  private final int checksum;
  private final String entityText;
  private final List<BspEntity> entities;
  private final List<Texture> textures;
  private final Planes planes;
  private final Nodes nodes;
  private final Leaves leaves;
  private final IndexList leafFaces;
  private final IndexList leafBrushes;
  private final List<Model> models;
  private final Brushes brushes;
  private final BrushSides brushSides;
  private final Vertices vertices;
  private final IndexList meshVertices;
  private final List<Effect> effects;
  private final Faces faces;
  private final Lightmaps lightmaps;
  private final LightGrid lightGrid;
  private final Visibility visibility;
  private final List<String> diagnostics;

  BspMap(Parts parts) {
    this.version = parts.version;
    this.checksum = parts.checksum;
    this.entityText = Objects.requireNonNull(parts.entityText);
    this.entities = List.copyOf(parts.entities);
    this.textures = List.copyOf(parts.textures);
    this.planes = Objects.requireNonNull(parts.planes);
    this.nodes = Objects.requireNonNull(parts.nodes);
    this.leaves = Objects.requireNonNull(parts.leaves);
    this.leafFaces = Objects.requireNonNull(parts.leafFaces);
    this.leafBrushes = Objects.requireNonNull(parts.leafBrushes);
    this.models = List.copyOf(parts.models);
    this.brushes = Objects.requireNonNull(parts.brushes);
    this.brushSides = Objects.requireNonNull(parts.brushSides);
    this.vertices = Objects.requireNonNull(parts.vertices);
    this.meshVertices = Objects.requireNonNull(parts.meshVertices);
    this.effects = List.copyOf(parts.effects);
    this.faces = Objects.requireNonNull(parts.faces);
    this.lightmaps = Objects.requireNonNull(parts.lightmaps);
    this.lightGrid = Objects.requireNonNull(parts.lightGrid);
    this.visibility = Objects.requireNonNull(parts.visibility);
    this.diagnostics = List.copyOf(parts.diagnostics);
  }

  /** Mutable assembly area for the reader and same-package tests. */
  static final class Parts {
    int version = VERSION_Q3;
    int checksum;
    String entityText = "";
    List<BspEntity> entities = List.of();
    List<Texture> textures = List.of();
    Planes planes = new Planes(new float[0]);
    Nodes nodes = new Nodes(new int[0]);
    Leaves leaves = new Leaves(new int[0]);
    IndexList leafFaces = new IndexList(new int[0]);
    IndexList leafBrushes = new IndexList(new int[0]);
    List<Model> models = List.of();
    Brushes brushes = new Brushes(new int[0]);
    BrushSides brushSides = new BrushSides(new int[0]);
    Vertices vertices = Vertices.EMPTY;
    IndexList meshVertices = new IndexList(new int[0]);
    List<Effect> effects = List.of();
    Faces faces = new Faces(new int[0], new float[0]);
    Lightmaps lightmaps = new Lightmaps(new byte[0]);
    LightGrid lightGrid = new LightGrid(new byte[0]);
    Visibility visibility = Visibility.NONE;
    List<String> diagnostics = List.of();
  }

  /** The header version: {@link #VERSION_Q3} or {@link #VERSION_EXTENDED}. */
  public int version() {
    return version;
  }

  /** True for the v47 header, which this engine accepts as an extension. */
  public boolean extendedVersion() {
    return version == VERSION_EXTENDED;
  }

  /** {@code Com_BlockChecksum} of the whole file, as compared against an AAS file's header. */
  public int checksum() {
    return checksum;
  }

  /** The entity lump text up to its terminator, as the game VM receives it. */
  public String entityText() {
    return entityText;
  }

  public List<BspEntity> entities() {
    return entities;
  }

  public List<Texture> textures() {
    return textures;
  }

  public Planes planes() {
    return planes;
  }

  public Nodes nodes() {
    return nodes;
  }

  public Leaves leaves() {
    return leaves;
  }

  public IndexList leafFaces() {
    return leafFaces;
  }

  public IndexList leafBrushes() {
    return leafBrushes;
  }

  public List<Model> models() {
    return models;
  }

  public Brushes brushes() {
    return brushes;
  }

  public BrushSides brushSides() {
    return brushSides;
  }

  public Vertices vertices() {
    return vertices;
  }

  /** Mesh-vertex indices, relative to each face's {@code firstVertex}. */
  public IndexList meshVertices() {
    return meshVertices;
  }

  /** The fog lump (named "effects" by the format description). */
  public List<Effect> effects() {
    return effects;
  }

  public Faces faces() {
    return faces;
  }

  public Lightmaps lightmaps() {
    return lightmaps;
  }

  public LightGrid lightGrid() {
    return lightGrid;
  }

  public Visibility visibility() {
    return visibility;
  }

  /** Lenient-read notes: overlapping lumps, ignored partial records, sanitised values. */
  public List<String> diagnostics() {
    return diagnostics;
  }

  public record Texture(String name, int surfaceFlags, int contents) {}

  public record Model(
      Vec3f mins, Vec3f maxs, int firstFace, int faceCount, int firstBrush, int brushCount) {}

  /** A fog volume: its shader name, brush, and the brush side it is seen through (or -1). */
  public record Effect(String name, int brush, int visibleSide) {}

  /** {@code [normal.x, normal.y, normal.z, dist]} per plane. */
  public static final class Planes {
    static final int STRIDE = 4;
    private final float[] data;

    Planes(float[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / STRIDE;
    }

    public float normal(int plane, int axis) {
      Objects.checkIndex(axis, 3);
      return data[plane * STRIDE + axis];
    }

    public Vec3f normal(int plane) {
      int base = plane * STRIDE;
      return new Vec3f(data[base], data[base + 1], data[base + 2]);
    }

    public float distance(int plane) {
      return data[plane * STRIDE + 3];
    }

    /** A copy in the packed layout {@code [nx, ny, nz, dist]*}. */
    public float[] toArray() {
      return data.clone();
    }
  }

  /** Per node: plane, front child, back child, integer mins and maxs. Leaf children are -1-leaf. */
  public static final class Nodes {
    static final int STRIDE = 9;
    private final int[] data;

    Nodes(int[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / STRIDE;
    }

    public int plane(int node) {
      return data[node * STRIDE];
    }

    /** Child 0 is the front, child 1 the back. */
    public int child(int node, int side) {
      Objects.checkIndex(side, 2);
      return data[node * STRIDE + 1 + side];
    }

    public int front(int node) {
      return data[node * STRIDE + 1];
    }

    public int back(int node) {
      return data[node * STRIDE + 2];
    }

    public int mins(int node, int axis) {
      Objects.checkIndex(axis, 3);
      return data[node * STRIDE + 3 + axis];
    }

    public int maxs(int node, int axis) {
      Objects.checkIndex(axis, 3);
      return data[node * STRIDE + 6 + axis];
    }
  }

  /** Per leaf: cluster, area, integer bounds and its leaf-face and leaf-brush ranges. */
  public static final class Leaves {
    static final int STRIDE = 12;
    private final int[] data;

    Leaves(int[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / STRIDE;
    }

    /** Visibility cluster, or -1 for an opaque (solid) leaf. */
    public int cluster(int leaf) {
      return data[leaf * STRIDE];
    }

    public int area(int leaf) {
      return data[leaf * STRIDE + 1];
    }

    public int mins(int leaf, int axis) {
      Objects.checkIndex(axis, 3);
      return data[leaf * STRIDE + 2 + axis];
    }

    public int maxs(int leaf, int axis) {
      Objects.checkIndex(axis, 3);
      return data[leaf * STRIDE + 5 + axis];
    }

    public int firstLeafFace(int leaf) {
      return data[leaf * STRIDE + 8];
    }

    public int leafFaceCount(int leaf) {
      return data[leaf * STRIDE + 9];
    }

    public int firstLeafBrush(int leaf) {
      return data[leaf * STRIDE + 10];
    }

    public int leafBrushCount(int leaf) {
      return data[leaf * STRIDE + 11];
    }
  }

  /** A read-only {@code int} lump (leaf faces, leaf brushes, mesh vertices). */
  public static final class IndexList {
    private final int[] data;

    IndexList(int[] data) {
      this.data = data;
    }

    public int count() {
      return data.length;
    }

    public int get(int index) {
      return data[index];
    }

    public int[] toArray() {
      return data.clone();
    }

    /** Copies {@code length} values starting at {@code from} into {@code out[outOffset..]}. */
    public void copy(int from, int[] out, int outOffset, int length) {
      System.arraycopy(data, from, out, outOffset, length);
    }
  }

  /** Per brush: first side, side count, texture. */
  public static final class Brushes {
    static final int STRIDE = 3;
    private final int[] data;

    Brushes(int[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / STRIDE;
    }

    public int firstSide(int brush) {
      return data[brush * STRIDE];
    }

    public int sideCount(int brush) {
      return data[brush * STRIDE + 1];
    }

    public int texture(int brush) {
      return data[brush * STRIDE + 2];
    }
  }

  /** Per brush side: plane, texture. */
  public static final class BrushSides {
    static final int STRIDE = 2;
    private final int[] data;

    BrushSides(int[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / STRIDE;
    }

    public int plane(int side) {
      return data[side * STRIDE];
    }

    public int texture(int side) {
      return data[side * STRIDE + 1];
    }
  }

  /**
   * Draw vertices as structure-of-arrays: positions and normals (3 floats each), texture and
   * lightmap coordinates (2 floats each) and RGBA colours (4 bytes each).
   */
  public static final class Vertices {
    static final Vertices EMPTY =
        new Vertices(new float[0], new float[0], new float[0], new float[0], new byte[0]);
    private final float[] positions;
    private final float[] texCoords;
    private final float[] lightmapCoords;
    private final float[] normals;
    private final byte[] colors;

    Vertices(
        float[] positions,
        float[] texCoords,
        float[] lightmapCoords,
        float[] normals,
        byte[] colors) {
      this.positions = positions;
      this.texCoords = texCoords;
      this.lightmapCoords = lightmapCoords;
      this.normals = normals;
      this.colors = colors;
    }

    public int count() {
      return positions.length / 3;
    }

    public float position(int vertex, int axis) {
      Objects.checkIndex(axis, 3);
      return positions[vertex * 3 + axis];
    }

    public Vec3f position(int vertex) {
      return new Vec3f(positions[vertex * 3], positions[vertex * 3 + 1], positions[vertex * 3 + 2]);
    }

    public float texCoord(int vertex, int component) {
      Objects.checkIndex(component, 2);
      return texCoords[vertex * 2 + component];
    }

    public float lightmapCoord(int vertex, int component) {
      Objects.checkIndex(component, 2);
      return lightmapCoords[vertex * 2 + component];
    }

    public float normal(int vertex, int axis) {
      Objects.checkIndex(axis, 3);
      return normals[vertex * 3 + axis];
    }

    public Vec3f normal(int vertex) {
      return new Vec3f(normals[vertex * 3], normals[vertex * 3 + 1], normals[vertex * 3 + 2]);
    }

    /** Colour as {@code 0xRRGGBBAA}. */
    public int rgba(int vertex) {
      int base = vertex * 4;
      return (colors[base] & 255) << 24
          | (colors[base + 1] & 255) << 16
          | (colors[base + 2] & 255) << 8
          | (colors[base + 3] & 255);
    }

    public float[] positionsCopy() {
      return positions.clone();
    }

    public float[] texCoordsCopy() {
      return texCoords.clone();
    }

    public float[] lightmapCoordsCopy() {
      return lightmapCoords.clone();
    }

    public float[] normalsCopy() {
      return normals.clone();
    }

    public byte[] colorsCopy() {
      return colors.clone();
    }
  }

  /**
   * Draw surfaces. Integer fields: texture, effect, type, first vertex, vertex count, first mesh
   * vertex, mesh vertex count, lightmap, lightmap x/y/width/height, patch width/height. Float
   * fields: lightmap origin, lightmap S and T vectors, normal.
   */
  public static final class Faces {
    static final int INTS = 14;
    static final int FLOATS = 12;
    private final int[] ints;
    private final float[] floats;

    Faces(int[] ints, float[] floats) {
      this.ints = ints;
      this.floats = floats;
    }

    public int count() {
      return ints.length / INTS;
    }

    public int texture(int face) {
      return ints[face * INTS];
    }

    /** Fog index into {@link BspMap#effects()}, or -1. */
    public int effect(int face) {
      return ints[face * INTS + 1];
    }

    /** The raw {@code surfaceType} field. */
    public int type(int face) {
      return ints[face * INTS + 2];
    }

    public SurfaceType surfaceType(int face) {
      return SurfaceType.of(type(face));
    }

    public int firstVertex(int face) {
      return ints[face * INTS + 3];
    }

    public int vertexCount(int face) {
      return ints[face * INTS + 4];
    }

    public int firstMeshVertex(int face) {
      return ints[face * INTS + 5];
    }

    public int meshVertexCount(int face) {
      return ints[face * INTS + 6];
    }

    /** Lightmap index, -1 for none, or {@link BspMap#LIGHTMAP_BY_VERTEX}. */
    public int lightmap(int face) {
      return ints[face * INTS + 7];
    }

    public int lightmapX(int face) {
      return ints[face * INTS + 8];
    }

    public int lightmapY(int face) {
      return ints[face * INTS + 9];
    }

    public int lightmapWidth(int face) {
      return ints[face * INTS + 10];
    }

    public int lightmapHeight(int face) {
      return ints[face * INTS + 11];
    }

    public int patchWidth(int face) {
      return ints[face * INTS + 12];
    }

    public int patchHeight(int face) {
      return ints[face * INTS + 13];
    }

    public float lightmapOrigin(int face, int axis) {
      Objects.checkIndex(axis, 3);
      return floats[face * FLOATS + axis];
    }

    /** Lightmap projection vector {@code s} (0) or {@code t} (1); flares keep colour here. */
    public float lightmapVector(int face, int vector, int axis) {
      Objects.checkIndex(vector, 2);
      Objects.checkIndex(axis, 3);
      return floats[face * FLOATS + 3 + vector * 3 + axis];
    }

    public float normal(int face, int axis) {
      Objects.checkIndex(axis, 3);
      return floats[face * FLOATS + 9 + axis];
    }

    public Vec3f normal(int face) {
      int base = face * FLOATS + 9;
      return new Vec3f(floats[base], floats[base + 1], floats[base + 2]);
    }
  }

  /** 128x128 RGB lightmap pages, stored back to back. */
  public static final class Lightmaps {
    private final byte[] data;

    Lightmaps(byte[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / LIGHTMAP_BYTES;
    }

    /** One byte of page {@code index}: offset {@code (y * 128 + x) * 3 + channel}. */
    public int unsigned(int index, int offset) {
      Objects.checkIndex(offset, LIGHTMAP_BYTES);
      return data[index * LIGHTMAP_BYTES + offset] & 255;
    }

    /** A copy of page {@code index}. */
    public byte[] page(int index) {
      Objects.checkIndex(index, count());
      return Arrays.copyOfRange(data, index * LIGHTMAP_BYTES, (index + 1) * LIGHTMAP_BYTES);
    }
  }

  /**
   * The light grid: per point ambient RGB, directed RGB, and the light direction's latitude and
   * longitude bytes. Its expected size follows from the world model bounds; the renderer discards a
   * grid of the wrong size.
   */
  public static final class LightGrid {
    static final int STRIDE = 8;
    private final byte[] data;

    LightGrid(byte[] data) {
      this.data = data;
    }

    public int count() {
      return data.length / STRIDE;
    }

    /** Ambient colour as {@code 0xRRGGBB}. */
    public int ambient(int point) {
      return rgb(point * STRIDE);
    }

    /** Directed colour as {@code 0xRRGGBB}. */
    public int directed(int point) {
      return rgb(point * STRIDE + 3);
    }

    public int latitude(int point) {
      return data[point * STRIDE + 6] & 255;
    }

    public int longitude(int point) {
      return data[point * STRIDE + 7] & 255;
    }

    public byte[] toArray() {
      return data.clone();
    }

    private int rgb(int offset) {
      return (data[offset] & 255) << 16 | (data[offset + 1] & 255) << 8 | (data[offset + 2] & 255);
    }
  }

  /** Cluster-to-cluster potentially visible sets. */
  public static final class Visibility {
    static final Visibility NONE = new Visibility(0, 0, new byte[0]);
    private final int clusters;
    private final int bytesPerCluster;
    private final byte[] bits;

    Visibility(int clusters, int bytesPerCluster, byte[] bits) {
      this.clusters = clusters;
      this.bytesPerCluster = bytesPerCluster;
      this.bits = bits;
    }

    public int clusters() {
      return clusters;
    }

    public int bytesPerCluster() {
      return bytesPerCluster;
    }

    /** True when there is no visibility data, so everything is potentially visible. */
    public boolean absent() {
      return clusters == 0;
    }

    /**
     * Whether {@code to} is potentially visible from {@code from}. As in the original engine, a
     * negative source cluster (or a map without visibility) sees everything.
     */
    public boolean visible(int from, int to) {
      if (from < 0 || clusters == 0) return true;
      if (from >= clusters || to < 0 || to >= clusters) return false;
      return (bits[from * bytesPerCluster + (to >> 3)] & (1 << (to & 7))) != 0;
    }

    /** The raw row bytes of cluster {@code from}. */
    public byte[] row(int from) {
      Objects.checkIndex(from, clusters);
      return Arrays.copyOfRange(bits, from * bytesPerCluster, (from + 1) * bytesPerCluster);
    }
  }
}

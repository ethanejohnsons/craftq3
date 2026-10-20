package dev.bluevista.craftq3.assets.bsp;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Map;

/** Immutable IBSP46 data in Q3 units, without host or rendering dependencies. */
public record BspMap(
    List<Map<String, String>> entities,
    List<Texture> textures,
    List<Plane> planes,
    List<Node> nodes,
    List<Leaf> leaves,
    List<Integer> leafFaces,
    List<Integer> leafBrushes,
    List<Model> models,
    List<Brush> brushes,
    List<BrushSide> brushSides,
    List<Vertex> vertices,
    List<Integer> meshVertices,
    List<Effect> effects,
    List<Face> faces,
    List<Bytes> lightmaps,
    List<LightVolume> lightVolumes,
    Visibility visibility) {
  public BspMap {
    entities = entities.stream().map(Map::copyOf).toList();
    textures = List.copyOf(textures);
    planes = List.copyOf(planes);
    nodes = List.copyOf(nodes);
    leaves = List.copyOf(leaves);
    leafFaces = List.copyOf(leafFaces);
    leafBrushes = List.copyOf(leafBrushes);
    models = List.copyOf(models);
    brushes = List.copyOf(brushes);
    brushSides = List.copyOf(brushSides);
    vertices = List.copyOf(vertices);
    meshVertices = List.copyOf(meshVertices);
    effects = List.copyOf(effects);
    faces = List.copyOf(faces);
    lightmaps = List.copyOf(lightmaps);
    lightVolumes = List.copyOf(lightVolumes);
  }

  public record Texture(String name, int flags, int contents) {}

  public record Plane(Vec3 normal, float distance) {}

  public record Bounds(Vec3 min, Vec3 max) {}

  public record Node(int plane, int front, int back, Bounds bounds) {}

  public record Leaf(
      int cluster,
      int area,
      Bounds bounds,
      int firstFace,
      int faceCount,
      int firstBrush,
      int brushCount) {}

  public record Model(
      Bounds bounds, int firstFace, int faceCount, int firstBrush, int brushCount) {}

  public record Brush(int firstSide, int sideCount, int texture) {}

  public record BrushSide(int plane, int texture) {}

  public record Uv(float u, float v) {}

  public record Vertex(Vec3 position, Uv textureUv, Uv lightmapUv, Vec3 normal, int rgba) {}

  public record Effect(String name, int brush, int visibleSide) {}

  public record Face(
      int texture,
      int effect,
      int type,
      int firstVertex,
      int vertexCount,
      int firstMeshVertex,
      int meshVertexCount,
      int lightmap,
      int lightmapX,
      int lightmapY,
      int lightmapWidth,
      int lightmapHeight,
      Vec3 lightmapOrigin,
      Vec3 lightmapS,
      Vec3 lightmapT,
      Vec3 normal,
      int patchWidth,
      int patchHeight) {}

  public record LightVolume(int ambientRgb, int directionalRgb, int latitude, int longitude) {}

  /** Defensive byte storage (unlike an array-valued record). */
  public static final class Bytes {
    private final byte[] data;

    public Bytes(byte[] data) {
      this.data = data.clone();
    }

    public int size() {
      return data.length;
    }

    public int unsigned(int index) {
      return Byte.toUnsignedInt(data[index]);
    }

    public byte[] copy() {
      return data.clone();
    }
  }

  public record Visibility(int clusters, int bytesPerCluster, Bytes bits) {
    public boolean visible(int from, int to) {
      if (from < 0 || clusters == 0) return true;
      if (from >= clusters || to < 0 || to >= clusters) return false;
      return (bits.unsigned(from * bytesPerCluster + to / 8) & (1 << (to & 7))) != 0;
    }
  }
}

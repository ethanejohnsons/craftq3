package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.formats.bsp.BspBuilder;
import dev.bluevista.craftq3.engine.formats.bsp.BspLump;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.render.material.MaterialRegistry;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A small authoring API over {@link BspBuilder} for synthetic render fixtures. */
final class TestBsp {
  private String entities = "{\n\"classname\" \"worldspawn\"\n}\n";
  private final List<Object[]> textures = new ArrayList<>();
  private final List<float[]> planes = new ArrayList<>();
  private final List<int[]> nodes = new ArrayList<>();
  private final List<int[]> leaves = new ArrayList<>();
  private final List<Integer> leafFaces = new ArrayList<>();
  private final List<float[]> modelBounds = new ArrayList<>();
  private final List<int[]> models = new ArrayList<>();
  private final List<int[]> brushes = new ArrayList<>();
  private final List<int[]> sides = new ArrayList<>();
  private final List<float[]> vertices = new ArrayList<>();
  private final List<Integer> colors = new ArrayList<>();
  private final List<Integer> meshVertices = new ArrayList<>();
  private final List<Object[]> effects = new ArrayList<>();
  private final List<int[]> faceInts = new ArrayList<>();
  private final List<float[]> faceFloats = new ArrayList<>();
  private byte[] lightmaps = new byte[0];
  private byte[] lightGrid = new byte[0];
  private byte[] visibility = new byte[0];

  TestBsp entities(String text) {
    entities = text;
    return this;
  }

  int texture(String name, int flags) {
    textures.add(new Object[] {name, flags});
    return textures.size() - 1;
  }

  int plane(float nx, float ny, float nz, float distance) {
    planes.add(new float[] {nx, ny, nz, distance});
    return planes.size() - 1;
  }

  /** Children use the Q3 convention: negative values are {@code -1 - leaf}. */
  int node(int plane, int front, int back, int[] mins, int[] maxs) {
    nodes.add(new int[] {plane, front, back, mins[0], mins[1], mins[2], maxs[0], maxs[1], maxs[2]});
    return nodes.size() - 1;
  }

  int leaf(int cluster, int area, int... faces) {
    int first = leafFaces.size();
    for (int face : faces) leafFaces.add(face);
    leaves.add(
        new int[] {cluster, area, -512, -512, -512, 512, 512, 512, first, faces.length, 0, 0});
    return leaves.size() - 1;
  }

  int model(float[] mins, float[] maxs, int firstFace, int faceCount, int firstBrush, int brushes) {
    modelBounds.add(new float[] {mins[0], mins[1], mins[2], maxs[0], maxs[1], maxs[2]});
    models.add(new int[] {firstFace, faceCount, firstBrush, brushes});
    return models.size() - 1;
  }

  /** A brush whose sides use {@code planeIndices} (texture 0). */
  int brush(int... planeIndices) {
    int first = sides.size();
    for (int plane : planeIndices) sides.add(new int[] {plane, 0});
    brushes.add(new int[] {first, planeIndices.length, 0});
    return brushes.size() - 1;
  }

  /** An axis-aligned box brush; returns the brush index. */
  int box(float[] min, float[] max) {
    return brush(
        plane(-1, 0, 0, -min[0]),
        plane(1, 0, 0, max[0]),
        plane(0, -1, 0, -min[1]),
        plane(0, 1, 0, max[1]),
        plane(0, 0, -1, -min[2]),
        plane(0, 0, 1, max[2]));
  }

  int effect(String name, int brush) {
    effects.add(new Object[] {name, brush});
    return effects.size() - 1;
  }

  int vertex(
      float x,
      float y,
      float z,
      float s,
      float t,
      float ls,
      float lt,
      float nx,
      float ny,
      float nz,
      int rgba) {
    vertices.add(new float[] {x, y, z, s, t, ls, lt, nx, ny, nz});
    colors.add(rgba);
    return vertices.size() - 1;
  }

  /** An indexed face over {@code vertexCount} vertices from {@code firstVertex}. */
  int planar(
      int texture,
      int effect,
      int lightmap,
      int firstVertex,
      int vertexCount,
      float[] normal,
      int... relativeIndices) {
    int firstMesh = meshVertices.size();
    for (int index : relativeIndices) meshVertices.add(index);
    return face(
        new int[] {
          texture,
          effect,
          SurfaceType.PLANAR.id(),
          firstVertex,
          vertexCount,
          firstMesh,
          relativeIndices.length,
          lightmap,
          0,
          0,
          0,
          0,
          0,
          0
        },
        normal);
  }

  int patch(int texture, int lightmap, int firstVertex, int width, int height) {
    return face(
        new int[] {
          texture,
          -1,
          SurfaceType.PATCH.id(),
          firstVertex,
          width * height,
          0,
          0,
          lightmap,
          0,
          0,
          0,
          0,
          width,
          height
        },
        new float[] {0, 0, 1});
  }

  private int face(int[] ints, float[] normal) {
    faceInts.add(ints);
    faceFloats.add(new float[] {0, 0, 0, 0, 0, 0, 0, 0, 0, normal[0], normal[1], normal[2]});
    return faceInts.size() - 1;
  }

  TestBsp lightmaps(byte[] pages) {
    lightmaps = pages.clone();
    return this;
  }

  TestBsp lightGrid(byte[] points) {
    lightGrid = points.clone();
    return this;
  }

  TestBsp visibility(int clusters, int bytesPerCluster, byte... bits) {
    ByteBuffer b = BspBuilder.buffer(8 + bits.length).putInt(clusters).putInt(bytesPerCluster);
    b.put(bits);
    visibility = b.array();
    return this;
  }

  BspMap read() {
    BspBuilder builder = new BspBuilder().entities(entities);
    ByteBuffer t = BspBuilder.buffer(72 * textures.size());
    for (Object[] texture : textures) {
      BspBuilder.name(t, (String) texture[0], 64);
      t.putInt((Integer) texture[1]).putInt(1);
    }
    builder.lump(BspLump.TEXTURES, t.array());
    ByteBuffer p = BspBuilder.buffer(16 * planes.size());
    for (float[] plane : planes) for (float v : plane) p.putFloat(v);
    builder.lump(BspLump.PLANES, p.array());
    ByteBuffer n = BspBuilder.buffer(36 * nodes.size());
    for (int[] node : nodes) for (int v : node) n.putInt(v);
    builder.lump(BspLump.NODES, n.array());
    ByteBuffer l = BspBuilder.buffer(48 * leaves.size());
    for (int[] leaf : leaves) for (int v : leaf) l.putInt(v);
    builder.lump(BspLump.LEAVES, l.array());
    builder.lump(BspLump.LEAF_FACES, ints(leafFaces));
    ByteBuffer m = BspBuilder.buffer(40 * models.size());
    for (int i = 0; i < models.size(); i++) {
      for (float v : modelBounds.get(i)) m.putFloat(v);
      for (int v : models.get(i)) m.putInt(v);
    }
    builder.lump(BspLump.MODELS, m.array());
    ByteBuffer b = BspBuilder.buffer(12 * brushes.size());
    for (int[] brush : brushes) for (int v : brush) b.putInt(v);
    builder.lump(BspLump.BRUSHES, b.array());
    ByteBuffer s = BspBuilder.buffer(8 * sides.size());
    for (int[] side : sides) for (int v : side) s.putInt(v);
    builder.lump(BspLump.BRUSH_SIDES, s.array());
    ByteBuffer v = BspBuilder.buffer(44 * vertices.size());
    for (int i = 0; i < vertices.size(); i++) {
      for (float f : vertices.get(i)) v.putFloat(f);
      int rgba = colors.get(i);
      v.put((byte) (rgba >>> 24))
          .put((byte) (rgba >>> 16))
          .put((byte) (rgba >>> 8))
          .put((byte) rgba);
    }
    builder.lump(BspLump.VERTICES, v.array());
    builder.lump(BspLump.MESH_VERTICES, ints(meshVertices));
    ByteBuffer e = BspBuilder.buffer(72 * effects.size());
    for (Object[] effect : effects) {
      BspBuilder.name(e, (String) effect[0], 64);
      e.putInt((Integer) effect[1]).putInt(-1);
    }
    builder.lump(BspLump.EFFECTS, e.array());
    ByteBuffer f = BspBuilder.buffer(104 * faceInts.size());
    for (int i = 0; i < faceInts.size(); i++) {
      int[] ints = faceInts.get(i);
      for (int k = 0; k < 12; k++) f.putInt(ints[k]);
      for (float value : faceFloats.get(i)) f.putFloat(value);
      f.putInt(ints[12]).putInt(ints[13]);
    }
    builder.lump(BspLump.FACES, f.array());
    builder.lump(BspLump.LIGHTMAPS, lightmaps);
    builder.lump(BspLump.LIGHT_GRID, lightGrid);
    builder.lump(BspLump.VISIBILITY, visibility);
    return BspReader.read(builder.build());
  }

  private static byte[] ints(List<Integer> values) {
    ByteBuffer b = BspBuilder.buffer(4 * values.size());
    for (int value : values) b.putInt(value);
    return b.array();
  }

  /** Shader source resolving names through {@code defined}, otherwise implicitly. */
  static MaterialRegistry.Source shaders(Map<String, ShaderDefinition> defined) {
    return (name, lightmapped) -> {
      ShaderDefinition shader = defined.get(ShaderDefinition.canonicalName(name));
      return shader != null ? shader : ShaderDefinition.implicit(name, lightmapped);
    };
  }

  static RenderScene scene(BspMap map) {
    return RenderScene.build("test", map, shaders(Map.of()));
  }

  static RenderScene scene(BspMap map, int subdivisions) {
    return RenderScene.build("test", map, shaders(Map.of()), new RenderScene.Options(subdivisions));
  }
}

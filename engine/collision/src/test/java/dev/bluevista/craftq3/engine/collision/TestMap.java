package dev.bluevista.craftq3.engine.collision;

import static dev.bluevista.craftq3.engine.formats.bsp.BspBuilder.buffer;

import dev.bluevista.craftq3.engine.formats.bsp.BspBuilder;
import dev.bluevista.craftq3.engine.formats.bsp.BspLump;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Assembles small synthetic BSPs lump by lump for collision tests. */
final class TestMap {
  private record Texture(String name, int flags, int contents) {}

  private record Leaf(int cluster, int area, int[] faces, int[] brushes) {}

  private record Model(
      float[] mins, float[] maxs, int firstFace, int faces, int firstBrush, int brushes) {}

  private record Face(
      int type, int texture, int firstVertex, int vertexCount, int width, int height) {}

  private final List<Texture> textures = new ArrayList<>();
  private final List<float[]> planes = new ArrayList<>();
  private final List<int[]> nodes = new ArrayList<>();
  private final List<Leaf> leaves = new ArrayList<>();
  private final List<Model> models = new ArrayList<>();
  private final List<int[]> brushes = new ArrayList<>();
  private final List<int[]> sides = new ArrayList<>();
  private final List<float[]> vertices = new ArrayList<>();
  private final List<Face> faces = new ArrayList<>();
  private byte[] visibility = new byte[0];

  int texture(String name, int flags, int contents) {
    textures.add(new Texture(name, flags, contents));
    return textures.size() - 1;
  }

  int plane(float nx, float ny, float nz, float dist) {
    planes.add(new float[] {nx, ny, nz, dist});
    return planes.size() - 1;
  }

  /** Children use the BSP convention: negative values are {@code -1 - leaf}. */
  int node(int plane, int front, int back) {
    nodes.add(new int[] {plane, front, back});
    return nodes.size() - 1;
  }

  int leaf(int cluster, int area, int[] leafFaces, int[] leafBrushes) {
    leaves.add(new Leaf(cluster, area, leafFaces, leafBrushes));
    return leaves.size() - 1;
  }

  int model(float[] mins, float[] maxs, int firstFace, int faceCount, int firstBrush, int count) {
    models.add(new Model(mins, maxs, firstFace, faceCount, firstBrush, count));
    return models.size() - 1;
  }

  /** An axis-aligned brush with sides in the compiler's -x, +x, -y, +y, -z, +z order. */
  int box(float[] min, float[] max, int texture) {
    int first = sides.size();
    for (int axis = 0; axis < 3; axis++) {
      float[] negative = {0, 0, 0};
      negative[axis] = -1;
      float[] positive = {0, 0, 0};
      positive[axis] = 1;
      sides.add(new int[] {plane(negative[0], negative[1], negative[2], -min[axis]), texture});
      sides.add(new int[] {plane(positive[0], positive[1], positive[2], max[axis]), texture});
    }
    brushes.add(new int[] {first, 6, texture});
    return brushes.size() - 1;
  }

  /** A brush from explicit planes {@code [nx, ny, nz, dist]}. */
  int brush(int texture, float[]... brushPlanes) {
    int first = sides.size();
    for (float[] p : brushPlanes) sides.add(new int[] {plane(p[0], p[1], p[2], p[3]), texture});
    brushes.add(new int[] {first, brushPlanes.length, texture});
    return brushes.size() - 1;
  }

  /** A patch face over a row-major control grid ({@code width * height} points). */
  int patch(int texture, int width, int height, float[][] points) {
    int first = vertices.size();
    for (float[] p : points) vertices.add(p);
    faces.add(new Face(SurfaceType.PATCH.id(), texture, first, points.length, width, height));
    return faces.size() - 1;
  }

  void visibility(int clusters, byte[] rows) {
    int stride = (clusters + 7) / 8;
    visibility = buffer(8 + rows.length).putInt(clusters).putInt(stride).put(rows).array();
  }

  BspMap build() {
    BspBuilder b = new BspBuilder().entities("{\n\"classname\" \"worldspawn\"\n}\n");
    ByteBuffer t = buffer(72 * textures.size());
    for (Texture texture : textures) {
      BspBuilder.name(t, texture.name(), 64);
      t.putInt(texture.flags()).putInt(texture.contents());
    }
    b.lump(BspLump.TEXTURES, t.array());
    ByteBuffer p = buffer(16 * planes.size());
    for (float[] plane : planes) for (float v : plane) p.putFloat(v);
    b.lump(BspLump.PLANES, p.array());
    ByteBuffer n = buffer(36 * nodes.size());
    for (int[] node : nodes) {
      n.putInt(node[0]).putInt(node[1]).putInt(node[2]);
      for (int i = 0; i < 3; i++) n.putInt(-65536);
      for (int i = 0; i < 3; i++) n.putInt(65536);
    }
    b.lump(BspLump.NODES, n.array());
    ByteBuffer l = buffer(48 * leaves.size());
    List<Integer> leafFaces = new ArrayList<>();
    List<Integer> leafBrushes = new ArrayList<>();
    for (Leaf leaf : leaves) {
      l.putInt(leaf.cluster()).putInt(leaf.area());
      for (int i = 0; i < 3; i++) l.putInt(-65536);
      for (int i = 0; i < 3; i++) l.putInt(65536);
      l.putInt(leafFaces.size()).putInt(leaf.faces().length);
      for (int f : leaf.faces()) leafFaces.add(f);
      l.putInt(leafBrushes.size()).putInt(leaf.brushes().length);
      for (int brush : leaf.brushes()) leafBrushes.add(brush);
    }
    b.lump(BspLump.LEAVES, l.array());
    b.lump(BspLump.LEAF_FACES, ints(leafFaces));
    b.lump(BspLump.LEAF_BRUSHES, ints(leafBrushes));
    ByteBuffer m = buffer(40 * models.size());
    for (Model model : models) {
      for (float v : model.mins()) m.putFloat(v);
      for (float v : model.maxs()) m.putFloat(v);
      m.putInt(model.firstFace()).putInt(model.faces());
      m.putInt(model.firstBrush()).putInt(model.brushes());
    }
    b.lump(BspLump.MODELS, m.array());
    ByteBuffer br = buffer(12 * brushes.size());
    for (int[] brush : brushes) br.putInt(brush[0]).putInt(brush[1]).putInt(brush[2]);
    b.lump(BspLump.BRUSHES, br.array());
    ByteBuffer s = buffer(8 * sides.size());
    for (int[] side : sides) s.putInt(side[0]).putInt(side[1]);
    b.lump(BspLump.BRUSH_SIDES, s.array());
    ByteBuffer v = buffer(44 * vertices.size());
    for (float[] vertex : vertices) {
      v.putFloat(vertex[0]).putFloat(vertex[1]).putFloat(vertex[2]);
      v.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
      v.putFloat(0).putFloat(0).putFloat(1).putInt(-1);
    }
    b.lump(BspLump.VERTICES, v.array());
    ByteBuffer f = buffer(104 * faces.size());
    for (Face face : faces) {
      int start = f.position();
      f.putInt(face.texture()).putInt(-1).putInt(face.type());
      f.putInt(face.firstVertex()).putInt(face.vertexCount());
      f.putInt(0).putInt(0).putInt(-1);
      f.position(start + 96);
      f.putInt(face.width()).putInt(face.height());
    }
    b.lump(BspLump.FACES, f.array());
    b.lump(BspLump.VISIBILITY, visibility);
    return BspReader.read(b.build(), "synthetic");
  }

  private static byte[] ints(List<Integer> values) {
    ByteBuffer b = buffer(4 * values.size());
    for (int value : values) b.putInt(value);
    return b.array();
  }

  // ---- ready-made maps ------------------------------------------------------------------------

  record BoxSpec(float[] min, float[] max, int contents, int flags) {}

  static float[] v(float x, float y, float z) {
    return new float[] {x, y, z};
  }

  static final float[] WORLD_MIN = {-4096, -4096, -4096};
  static final float[] WORLD_MAX = {4096, 4096, 4096};

  /**
   * Boxes split by the plane x = 0: each brush is listed in the front leaf, the back leaf or both,
   * like a compiled map. Model 0 covers every brush.
   */
  static BspMap boxes(List<BoxSpec> specs) {
    TestMap map = new TestMap();
    int split = map.plane(1, 0, 0, 0);
    List<Integer> front = new ArrayList<>();
    List<Integer> back = new ArrayList<>();
    for (int i = 0; i < specs.size(); i++) {
      BoxSpec spec = specs.get(i);
      int texture = map.texture("textures/fixture" + i, spec.flags(), spec.contents());
      int brush = map.box(spec.min(), spec.max(), texture);
      if (spec.max()[0] >= 0) front.add(brush);
      if (spec.min()[0] <= 0) back.add(brush);
    }
    map.node(split, -1, -2);
    map.leaf(0, 0, new int[0], front.stream().mapToInt(Integer::intValue).toArray());
    map.leaf(1, 0, new int[0], back.stream().mapToInt(Integer::intValue).toArray());
    map.model(WORLD_MIN, WORLD_MAX, 0, 0, 0, specs.size());
    return map.build();
  }
}

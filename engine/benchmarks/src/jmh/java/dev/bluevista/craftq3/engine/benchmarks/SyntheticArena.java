package dev.bluevista.craftq3.engine.benchmarks;

import static dev.bluevista.craftq3.engine.formats.bsp.BspBuilder.buffer;

import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.formats.bsp.BspBuilder;
import dev.bluevista.craftq3.engine.formats.bsp.BspLump;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A synthetic arena BSP for the collision benchmarks: a 4096-unit floor with walls, a grid of
 * pillars, sloped ramps and curved patch domes, split by an axis-aligned BSP of 64 leaves. It is
 * generated from scratch and contains no game data.
 */
final class SyntheticArena {
  static final float HALF = 2048;
  private static final int LEAF_DEPTH = 6;

  private record Bounds(float[] min, float[] max) {
    boolean overlaps(float[] lo, float[] hi) {
      for (int axis = 0; axis < 2; axis++)
        if (max[axis] < lo[axis] || min[axis] > hi[axis]) return false;
      return true;
    }
  }

  private record Face(int firstVertex, int width, int height) {}

  private final List<float[]> planes = new ArrayList<>();
  private final List<int[]> sides = new ArrayList<>();
  private final List<int[]> brushes = new ArrayList<>();
  private final List<Bounds> brushBounds = new ArrayList<>();
  private final List<float[]> vertices = new ArrayList<>();
  private final List<Face> faces = new ArrayList<>();
  private final List<Bounds> faceBounds = new ArrayList<>();
  private final List<int[]> nodes = new ArrayList<>();
  private final List<int[][]> leaves = new ArrayList<>();

  private SyntheticArena() {}

  static BspMap build() {
    return new SyntheticArena().generate();
  }

  private BspMap generate() {
    box(v(-HALF, -HALF, -64), v(HALF, HALF, 0));
    box(v(-HALF - 64, -HALF, -64), v(-HALF, HALF, 512));
    box(v(HALF, -HALF, -64), v(HALF + 64, HALF, 512));
    box(v(-HALF, -HALF - 64, -64), v(HALF, -HALF, 512));
    box(v(-HALF, HALF, -64), v(HALF, HALF + 64, 512));
    for (int gx = -3; gx <= 3; gx++)
      for (int gy = -3; gy <= 3; gy++) {
        float x = gx * 512, y = gy * 512;
        if (((gx + gy) & 1) == 0) box(v(x - 32, y - 32, 0), v(x + 32, y + 32, 96 + 32 * (gx & 3)));
        else ramp(x - 128, y - 64, x + 128, y + 64, 64);
      }
    for (int i = 0; i < 4; i++) dome(i % 2 == 0 ? -768 : 768, i < 2 ? -768 : 768, 160, 96);
    split(0, v(-HALF - 64, -HALF - 64, 0), v(HALF + 64, HALF + 64, 0));
    return write();
  }

  /** Recursively splits x, then y, until {@link #LEAF_DEPTH}; returns the child reference. */
  private int split(int depth, float[] lo, float[] hi) {
    if (depth == LEAF_DEPTH) {
      List<Integer> leafBrushes = new ArrayList<>(), leafFaces = new ArrayList<>();
      for (int b = 0; b < brushBounds.size(); b++)
        if (brushBounds.get(b).overlaps(lo, hi)) leafBrushes.add(b);
      for (int f = 0; f < faceBounds.size(); f++)
        if (faceBounds.get(f).overlaps(lo, hi)) leafFaces.add(f);
      leaves.add(new int[][] {ints(leafFaces), ints(leafBrushes)});
      return -leaves.size();
    }
    int axis = depth & 1;
    float mid = (lo[axis] + hi[axis]) / 2;
    float[] normal = {0, 0, 0};
    normal[axis] = 1;
    int plane = plane(normal[0], normal[1], normal[2], mid);
    int node = nodes.size();
    nodes.add(new int[] {plane, 0, 0});
    float[] frontLo = lo.clone(), backHi = hi.clone();
    frontLo[axis] = mid;
    backHi[axis] = mid;
    nodes.get(node)[1] = split(depth + 1, frontLo, hi);
    nodes.get(node)[2] = split(depth + 1, lo, backHi);
    return node;
  }

  private int plane(float nx, float ny, float nz, float dist) {
    planes.add(new float[] {nx, ny, nz, dist});
    return planes.size() - 1;
  }

  /** Six axial sides in the compiler's -x, +x, -y, +y, -z, +z order, then {@code extra}. */
  private void brush(float[] min, float[] max, float[]... extra) {
    int first = sides.size();
    for (int axis = 0; axis < 3; axis++) {
      float[] negative = {0, 0, 0};
      negative[axis] = -1;
      float[] positive = {0, 0, 0};
      positive[axis] = 1;
      sides.add(new int[] {plane(negative[0], negative[1], negative[2], -min[axis]), 0});
      sides.add(new int[] {plane(positive[0], positive[1], positive[2], max[axis]), 0});
    }
    for (float[] p : extra) sides.add(new int[] {plane(p[0], p[1], p[2], p[3]), 0});
    brushes.add(new int[] {first, sides.size() - first, 0});
    brushBounds.add(new Bounds(min, max));
  }

  private void box(float[] min, float[] max) {
    brush(min, max);
  }

  /** A wedge rising along +x from z = 0 at {@code x0} to {@code height} at {@code x1}. */
  private void ramp(float x0, float y0, float x1, float y1, float height) {
    float length = x1 - x0;
    float scale = (float) Math.sqrt(height * height + length * length);
    float nx = -height / scale, nz = length / scale;
    brush(v(x0, y0, 0), v(x1, y1, height), new float[] {nx, 0, nz, nx * x0});
  }

  /** A 3x3 patch dome centred on (x, y) with the given radius and height. */
  private void dome(float x, float y, float radius, float height) {
    int first = vertices.size();
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 3; column++)
        vertices.add(
            v(
                x + (column - 1) * radius,
                y + (row - 1) * radius,
                row == 1 && column == 1 ? height * 2 : 0));
    faces.add(new Face(first, 3, 3));
    faceBounds.add(new Bounds(v(x - radius, y - radius, 0), v(x + radius, y + radius, height)));
  }

  private BspMap write() {
    BspBuilder b = new BspBuilder().entities("{\n\"classname\" \"worldspawn\"\n}\n");
    ByteBuffer t = buffer(72);
    BspBuilder.name(t, "textures/synthetic/solid", 64);
    t.putInt(0).putInt(Contents.SOLID);
    b.lump(BspLump.TEXTURES, t.array());
    ByteBuffer p = buffer(16 * planes.size());
    for (float[] plane : planes) for (float value : plane) p.putFloat(value);
    b.lump(BspLump.PLANES, p.array());
    ByteBuffer n = buffer(36 * nodes.size());
    for (int[] node : nodes) {
      n.putInt(node[0]).putInt(node[1]).putInt(node[2]);
      for (int i = 0; i < 3; i++) n.putInt(-65536);
      for (int i = 0; i < 3; i++) n.putInt(65536);
    }
    b.lump(BspLump.NODES, n.array());
    ByteBuffer l = buffer(48 * leaves.size());
    List<Integer> leafFaces = new ArrayList<>(), leafBrushes = new ArrayList<>();
    for (int[][] leaf : leaves) {
      l.putInt(0).putInt(0);
      for (int i = 0; i < 3; i++) l.putInt(-65536);
      for (int i = 0; i < 3; i++) l.putInt(65536);
      l.putInt(leafFaces.size()).putInt(leaf[0].length);
      for (int f : leaf[0]) leafFaces.add(f);
      l.putInt(leafBrushes.size()).putInt(leaf[1].length);
      for (int brush : leaf[1]) leafBrushes.add(brush);
    }
    b.lump(BspLump.LEAVES, l.array());
    b.lump(BspLump.LEAF_FACES, bytes(leafFaces));
    b.lump(BspLump.LEAF_BRUSHES, bytes(leafBrushes));
    ByteBuffer m = buffer(40);
    for (float value : v(-HALF - 64, -HALF - 64, -64)) m.putFloat(value);
    for (float value : v(HALF + 64, HALF + 64, 512)) m.putFloat(value);
    m.putInt(0).putInt(faces.size()).putInt(0).putInt(brushes.size());
    b.lump(BspLump.MODELS, m.array());
    ByteBuffer br = buffer(12 * brushes.size());
    for (int[] brush : brushes) br.putInt(brush[0]).putInt(brush[1]).putInt(brush[2]);
    b.lump(BspLump.BRUSHES, br.array());
    ByteBuffer s = buffer(8 * sides.size());
    for (int[] side : sides) s.putInt(side[0]).putInt(side[1]);
    b.lump(BspLump.BRUSH_SIDES, s.array());
    ByteBuffer vb = buffer(44 * vertices.size());
    for (float[] vertex : vertices) {
      vb.putFloat(vertex[0]).putFloat(vertex[1]).putFloat(vertex[2]);
      vb.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
      vb.putFloat(0).putFloat(0).putFloat(1).putInt(-1);
    }
    b.lump(BspLump.VERTICES, vb.array());
    ByteBuffer f = buffer(104 * faces.size());
    for (Face face : faces) {
      int start = f.position();
      f.putInt(0).putInt(-1).putInt(SurfaceType.PATCH.id());
      f.putInt(face.firstVertex()).putInt(face.width() * face.height());
      f.putInt(0).putInt(0).putInt(-1);
      f.position(start + 96);
      f.putInt(face.width()).putInt(face.height());
    }
    b.lump(BspLump.FACES, f.array());
    return BspReader.read(b.build(), "synthetic-arena");
  }

  private static int[] ints(List<Integer> values) {
    return values.stream().mapToInt(Integer::intValue).toArray();
  }

  private static byte[] bytes(List<Integer> values) {
    ByteBuffer out = buffer(4 * values.size());
    for (int value : values) out.putInt(value);
    return out.array();
  }

  static float[] v(float x, float y, float z) {
    return new float[] {x, y, z};
  }
}

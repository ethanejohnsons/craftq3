package dev.bluevista.craftq3.engine.game;

import static dev.bluevista.craftq3.engine.formats.bsp.BspBuilder.buffer;

import dev.bluevista.craftq3.engine.formats.bsp.BspBuilder;
import dev.bluevista.craftq3.engine.formats.bsp.BspLump;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * A synthetic corridor map, entirely made up for tests: three leaves along x split at x = 0 and x =
 * 512, a solid floor brush (z -64..0) under all of them and one inline door model.
 *
 * <pre>
 *   leaf 0: x &lt; 0      cluster 0  area 0
 *   leaf 1: 0..512     cluster 1  area 1
 *   leaf 2: x &gt; 512    cluster 2  area 2
 *   PVS: clusters 0 and 1 see each other; cluster 2 sees only itself.
 * </pre>
 */
final class TestMaps {
  static final float MIN_X = -1024, MAX_X = 1536, HALF_Y = 512, MIN_Z = -256, MAX_Z = 512;

  private TestMaps() {}

  static GameMap corridor() {
    return corridor(
        "{\n\"classname\" \"worldspawn\"\n}\n"
            + "{\n\"classname\" \"info_player_deathmatch\"\n\"origin\" \"-200 0 24\"\n}\n");
  }

  static GameMap corridor(String entities) {
    BspMap bsp = BspReader.read(bytes(entities), "maps/corridor.bsp");
    return GameMap.of("corridor", bsp, Optional.empty());
  }

  static byte[] bytes(String entities) {
    BspBuilder b = new BspBuilder().entities(entities);
    ByteBuffer texture = buffer(72);
    BspBuilder.name(texture, "textures/solid", 64);
    texture.putInt(0).putInt(1);
    b.lump(BspLump.TEXTURES, texture.array());
    // Planes 0, 1: node splits. Planes 2..7: floor sides. Planes 8..13: door sides.
    ByteBuffer planes = buffer(16 * 14);
    plane(planes, 1, 0, 0, 0);
    plane(planes, 1, 0, 0, 512);
    box(planes, MIN_X, -HALF_Y, -64, MAX_X, HALF_Y, 0);
    box(planes, -8, -64, 0, 8, 64, 128);
    b.lump(BspLump.PLANES, planes.array());
    ByteBuffer nodes = buffer(36 * 2);
    node(nodes, 0, 1, -1);
    node(nodes, 1, -3, -2);
    b.lump(BspLump.NODES, nodes.array());
    ByteBuffer leaves = buffer(48 * 3);
    leaf(leaves, 0, 0, (int) MIN_X, 0);
    leaf(leaves, 1, 1, 0, 512);
    leaf(leaves, 2, 2, 512, (int) MAX_X);
    b.lump(BspLump.LEAVES, leaves.array());
    b.lump(BspLump.LEAF_BRUSHES, buffer(4).putInt(0).array());
    ByteBuffer models = buffer(40 * 2);
    model(models, MIN_X, -HALF_Y, MIN_Z, MAX_X, HALF_Y, MAX_Z, 0, 1);
    model(models, -8, -64, 0, 8, 64, 128, 1, 1);
    b.lump(BspLump.MODELS, models.array());
    ByteBuffer brushes = buffer(24);
    brushes.putInt(0).putInt(6).putInt(0);
    brushes.putInt(6).putInt(6).putInt(0);
    b.lump(BspLump.BRUSHES, brushes.array());
    ByteBuffer sides = buffer(8 * 12);
    for (int i = 0; i < 12; i++) sides.putInt(2 + i).putInt(0);
    b.lump(BspLump.BRUSH_SIDES, sides.array());
    ByteBuffer vis = buffer(8 + 3);
    vis.putInt(3).putInt(1).put((byte) 0b011).put((byte) 0b011).put((byte) 0b100);
    b.lump(BspLump.VISIBILITY, vis.array());
    return b.build();
  }

  private static void plane(ByteBuffer out, float x, float y, float z, float d) {
    out.putFloat(x).putFloat(y).putFloat(z).putFloat(d);
  }

  private static void box(
      ByteBuffer out, float x0, float y0, float z0, float x1, float y1, float z1) {
    plane(out, -1, 0, 0, -x0);
    plane(out, 1, 0, 0, x1);
    plane(out, 0, -1, 0, -y0);
    plane(out, 0, 1, 0, y1);
    plane(out, 0, 0, -1, -z0);
    plane(out, 0, 0, 1, z1);
  }

  private static void node(ByteBuffer out, int plane, int front, int back) {
    out.putInt(plane).putInt(front).putInt(back);
    out.putInt((int) MIN_X).putInt((int) -HALF_Y).putInt((int) MIN_Z);
    out.putInt((int) MAX_X).putInt((int) HALF_Y).putInt((int) MAX_Z);
  }

  private static void leaf(ByteBuffer out, int cluster, int area, int x0, int x1) {
    out.putInt(cluster).putInt(area);
    out.putInt(x0).putInt((int) -HALF_Y).putInt((int) MIN_Z);
    out.putInt(x1).putInt((int) HALF_Y).putInt((int) MAX_Z);
    out.putInt(0).putInt(0).putInt(0).putInt(1);
  }

  private static void model(
      ByteBuffer out,
      float x0,
      float y0,
      float z0,
      float x1,
      float y1,
      float z1,
      int firstBrush,
      int brushes) {
    out.putFloat(x0).putFloat(y0).putFloat(z0).putFloat(x1).putFloat(y1).putFloat(z1);
    out.putInt(0).putInt(0).putInt(firstBrush).putInt(brushes);
  }
}

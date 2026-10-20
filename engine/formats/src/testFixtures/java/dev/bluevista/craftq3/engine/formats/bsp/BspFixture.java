package dev.bluevista.craftq3.engine.formats.bsp;

import static dev.bluevista.craftq3.engine.formats.bsp.BspBuilder.buffer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A minimal, entirely synthetic map with one of every lump: one plane, node, leaf, brush, model,
 * fog, lightmap and light-grid point, and either an indexed triangle face over four vertices or a
 * 3x3 patch over nine.
 */
public final class BspFixture {
  private BspFixture() {}

  public static byte[] map(boolean patch) {
    BspBuilder builder =
        new BspBuilder()
            .lump(
                BspLump.ENTITIES,
                ("{\n\"classname\" \"worldspawn\"\n}\n{\n\"classname\" \"info_player_deathmatch\"\n"
                        + "\"origin\" \"0 -200 100\"\n\"angle\" \"90\"\n}\n\0")
                    .getBytes(StandardCharsets.ISO_8859_1));
    ByteBuffer texture = buffer(72);
    BspBuilder.name(texture, "textures/test", 64);
    texture.putInt(7).putInt(1);
    builder.lump(BspLump.TEXTURES, texture.array());
    builder.lump(
        BspLump.PLANES, buffer(16).putFloat(0).putFloat(0).putFloat(1).putFloat(0).array());
    ByteBuffer node = buffer(36).putInt(0).putInt(-1).putInt(-1);
    for (int i = 0; i < 3; i++) node.putInt(-256);
    for (int i = 0; i < 3; i++) node.putInt(256);
    builder.lump(BspLump.NODES, node.array());
    ByteBuffer leaf = buffer(48);
    leaf.putInt(0).putInt(0);
    leaf.position(32);
    leaf.putInt(0).putInt(1).putInt(0).putInt(1);
    builder.lump(BspLump.LEAVES, leaf.array());
    builder.lump(BspLump.LEAF_FACES, buffer(4).putInt(0).array());
    builder.lump(BspLump.LEAF_BRUSHES, buffer(4).putInt(0).array());
    ByteBuffer model = buffer(40);
    for (int i = 0; i < 3; i++) model.putFloat(-256);
    for (int i = 0; i < 3; i++) model.putFloat(256);
    model.putInt(0).putInt(1).putInt(0).putInt(1);
    builder.lump(BspLump.MODELS, model.array());
    builder.lump(BspLump.BRUSHES, buffer(12).putInt(0).putInt(1).putInt(0).array());
    builder.lump(BspLump.BRUSH_SIDES, buffer(8).putInt(0).putInt(0).array());
    builder.lump(BspLump.VERTICES, vertices(patch));
    builder.lump(
        BspLump.MESH_VERTICES,
        patch ? new byte[0] : buffer(12).putInt(0).putInt(1).putInt(2).array());
    ByteBuffer effect = buffer(72);
    BspBuilder.name(effect, "fog/test", 64);
    effect.putInt(0).putInt(0);
    builder.lump(BspLump.EFFECTS, effect.array());
    builder.lump(BspLump.FACES, face(patch));
    byte[] lightmap = new byte[BspMap.LIGHTMAP_BYTES];
    lightmap[0] = (byte) 200;
    builder.lump(BspLump.LIGHTMAPS, lightmap);
    builder.lump(BspLump.LIGHT_GRID, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    builder.lump(BspLump.VISIBILITY, buffer(9).putInt(1).putInt(1).put((byte) 1).array());
    return builder.build();
  }

  private static byte[] vertices(boolean patch) {
    int count = patch ? 9 : 4;
    ByteBuffer vertices = buffer(44 * count);
    for (int i = 0; i < count; i++) {
      float x = patch ? (i % 3 - 1) * 128 : (i == 2 ? 128 : -128);
      float y = patch ? (i / 3 - 1) * 128 : (i == 3 ? 128 : -128);
      float z = patch && i == 4 ? 100 : 0;
      vertices.putFloat(x).putFloat(y).putFloat(z);
      vertices.putFloat(0.25f).putFloat(0.75f).putFloat(0.5f).putFloat(0.5f);
      vertices.putFloat(0).putFloat(0).putFloat(1).putInt(0xff0000ff);
    }
    return vertices.array();
  }

  private static byte[] face(boolean patch) {
    ByteBuffer face = buffer(104);
    face.putInt(0)
        .putInt(0)
        .putInt(patch ? SurfaceType.PATCH.id() : SurfaceType.PLANAR.id())
        .putInt(patch ? 0 : 1)
        .putInt(patch ? 9 : 3)
        .putInt(0)
        .putInt(patch ? 0 : 3)
        .putInt(0)
        .putInt(0)
        .putInt(0)
        .putInt(128)
        .putInt(128);
    face.position(92);
    face.putFloat(1).putInt(patch ? 3 : 0).putInt(patch ? 3 : 0);
    return face.array();
  }
}

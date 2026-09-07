package dev.bluevista.craftq3.assets.bsp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Entirely synthetic test data, with no original Quake assets. */
public final class BspFixture {
  private BspFixture() {}

  static ByteBuffer buffer(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  public static byte[] map(boolean patch) {
    byte[][] lumps = new byte[17][];
    lumps[0] =
        ("{\n\"classname\" \"worldspawn\"\n}\n{\n\"classname\" \"info_player_deathmatch\"\n\"origin\" \"0 -200 100\"\n\"angle\" \"90\"\n}\n\0")
            .getBytes(StandardCharsets.ISO_8859_1);
    ByteBuffer texture = buffer(72);
    texture.put("textures/test".getBytes(StandardCharsets.US_ASCII));
    texture.position(64);
    texture.putInt(7).putInt(1);
    lumps[1] = texture.array();
    lumps[2] = buffer(16).putFloat(0).putFloat(0).putFloat(1).putFloat(0).array();
    lumps[3] =
        buffer(36)
            .putInt(0)
            .putInt(-1)
            .putInt(-1)
            .putInt(-256)
            .putInt(-256)
            .putInt(-256)
            .putInt(256)
            .putInt(256)
            .putInt(256)
            .array();
    ByteBuffer leaf = buffer(48);
    leaf.putInt(0).putInt(0);
    leaf.position(32);
    leaf.putInt(0).putInt(1).putInt(0).putInt(1);
    lumps[4] = leaf.array();
    lumps[5] = buffer(4).putInt(0).array();
    lumps[6] = buffer(4).putInt(0).array();
    ByteBuffer model = buffer(40);
    for (int i = 0; i < 3; i++) model.putFloat(-256);
    for (int i = 0; i < 3; i++) model.putFloat(256);
    model.putInt(0).putInt(1).putInt(0).putInt(1);
    lumps[7] = model.array();
    lumps[8] = buffer(12).putInt(0).putInt(1).putInt(0).array();
    lumps[9] = buffer(8).putInt(0).putInt(0).array();
    int count = patch ? 9 : 4;
    ByteBuffer vertices = buffer(44 * count);
    for (int i = 0; i < count; i++) {
      float x = patch ? (i % 3 - 1) * 128 : (i == 2 ? 128 : -128);
      float y = patch ? (i / 3 - 1) * 128 : (i == 3 ? 128 : -128);
      float z = patch && i == 4 ? 100 : 0;
      vertices
          .putFloat(x)
          .putFloat(y)
          .putFloat(z)
          .putFloat(0.25f)
          .putFloat(0.75f)
          .putFloat(0.5f)
          .putFloat(0.5f)
          .putFloat(0)
          .putFloat(0)
          .putFloat(1)
          .putInt(0xff0000ff);
    }
    lumps[10] = vertices.array();
    lumps[11] = patch ? new byte[0] : buffer(12).putInt(0).putInt(1).putInt(2).array();
    ByteBuffer effect = buffer(72);
    effect.put("fog/test".getBytes(StandardCharsets.US_ASCII));
    effect.position(64);
    effect.putInt(0).putInt(0);
    lumps[12] = effect.array();
    ByteBuffer face = buffer(104);
    face.putInt(0)
        .putInt(0)
        .putInt(patch ? 2 : 1)
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
    lumps[13] = face.array();
    lumps[14] = new byte[49152];
    lumps[14][0] = (byte) 200;
    lumps[15] = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
    lumps[16] = buffer(9).putInt(1).putInt(1).put((byte) 1).array();
    int size = 144;
    for (byte[] lump : lumps) size += lump.length;
    ByteBuffer result = buffer(size);
    result.putInt(0x50534249).putInt(46);
    int offset = 144;
    for (byte[] lump : lumps) {
      result.putInt(offset).putInt(lump.length);
      offset += lump.length;
    }
    for (byte[] lump : lumps) result.put(lump);
    return result.array();
  }

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    Files.createDirectories(output.getParent());
    Files.write(output, map(true));
  }
}

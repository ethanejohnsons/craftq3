package dev.bluevista.craftq3.assets.aas;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Authored three-area navigation graph, with a portal and both oriented/packed travel fields. */
final class AasFixture {
  static final int CHECKSUM = 0xa1b2c3d4;
  private static final int[] COUNTS = {2, 7, 2, 7, 6, 3, 4, 4, 4, 4, 3, 2, 2, 3};

  private AasFixture() {}

  static int offset(int lump) {
    int offset = AasReader.HEADER_BYTES;
    for (int i = 0; i < lump; i++) offset += COUNTS[i] * AasMap.LumpKind.values()[i].stride();
    return offset;
  }

  static byte[] map(int version) {
    ByteBuffer b = ByteBuffer.allocate(offset(14)).order(ByteOrder.LITTLE_ENDIAN);
    ints(b, 0x53414145, version, CHECKSUM);
    for (int i = 0; i < 14; i++)
      ints(b, offset(i), COUNTS[i] * AasMap.LumpKind.values()[i].stride());
    ints(b, 2, 0);
    vector(b, -15, -15, -24);
    vector(b, 15, 15, 32);
    ints(b, 4, 0);
    vector(b, -15, -15, -24);
    vector(b, 15, 15, 16);
    vector(b, 0, 0, 0);
    vector(b, 0, 0, 0);
    vector(b, 0, 1, 0);
    vector(b, 0, 0, 1);
    vector(b, -1, 0, 0);
    vector(b, -1, 1, 0);
    vector(b, -1, 0, 1);
    vector(b, 1, 0, 0);
    b.putFloat(0);
    ints(b, 0);
    vector(b, -1, 0, 0);
    b.putFloat(1);
    ints(b, 0);
    ints(b, 0, 0, 1, 2, 2, 3, 3, 1, 4, 5, 5, 6, 6, 4);
    ints(b, 1, 2, 3, -6, -5, -4);
    ints(b, 0, 0, 0, 0, 0, 0); // Face zero.
    ints(b, 0, 4, 3, 0, 1, 2);
    ints(b, 1, 1, 3, 3, 3, 2);
    ints(b, 1, -1, -2, 2);
    area(b, 0, 0, 0, 0, 0);
    area(b, 1, 1, 0, 0, 1);
    area(b, 2, 2, 1, -1, 0);
    area(b, 3, 1, 3, -2, -1);
    ints(b, 0, 0, 0, 0, 0, 0, 0);
    ints(b, 0, 1, 2, 1, 0, 1, 1);
    ints(b, 8, 1, 2, -1, 0, 1, 2);
    ints(b, 0, 1, 2, 2, 0, 1, 3);
    reach(b, 0, 0, 0, 0, 0, 0);
    reach(b, 2, 1, -1, 2 | (1 << 24), 65535, 0xbeef);
    reach(b, 3, 0x20002, -81659335, 19, 300, 0);
    reach(b, 1, 1, 1, 2, 25, 0);
    ints(b, 0, 0, 0, 0, -1, 2, 1, -3, -2);
    ints(b, 0, 0, 0, 0, 0, 2, 1, 2, 1, 1);
    ints(b, 1, 1);
    ints(b, 0, 0, 0, 0, 2, 2, 1, 0, 2, 2, 1, 1);
    if (b.position() != b.capacity()) throw new AssertionError("Bad authored AAS fixture size");
    if (version == 5) {
      int mask = 0;
      for (int i = 8; i < AasReader.HEADER_BYTES; i++) {
        b.put(i, (byte) (b.get(i) ^ mask));
        mask = (mask + 119) & 255;
      }
    }
    return b.array();
  }

  static byte[] integer(int offset, int value) {
    byte[] result = map(4);
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    return result;
  }

  private static void area(ByteBuffer b, int number, int count, int first, float min, float max) {
    ints(b, number, count, first);
    vector(b, min, 0, 0);
    vector(b, max, number == 0 ? 0 : 1, number == 0 ? 0 : 1);
    vector(b, (min + max) / 2, 0, 0);
  }

  private static void reach(
      ByteBuffer b, int area, int face, int edge, int type, int time, int padding) {
    ints(b, area, face, edge);
    vector(b, 0, 0, 0);
    vector(b, 1, 2, 3);
    ints(b, type);
    b.putShort((short) time).putShort((short) padding);
  }

  private static void ints(ByteBuffer b, int... values) {
    for (int value : values) b.putInt(value);
  }

  private static void vector(ByteBuffer b, float x, float y, float z) {
    b.putFloat(x).putFloat(y).putFloat(z);
  }
}

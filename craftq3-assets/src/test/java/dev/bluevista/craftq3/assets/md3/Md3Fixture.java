package dev.bluevista.craftq3.assets.md3;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Small authored model: two frames, one attachment, one triangle and one material. */
final class Md3Fixture {
  static final int FRAMES = 108, TAGS = 220, SURFACE = 444, END = 704;

  private Md3Fixture() {}

  static byte[] model() {
    ByteBuffer b = ByteBuffer.allocate(END).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x33504449).putInt(15);
    name(b, "fixture.md3", 64);
    b.putInt(7).putInt(2).putInt(1).putInt(1).putInt(0);
    b.putInt(FRAMES).putInt(TAGS).putInt(SURFACE).putInt(END);
    for (int frame = 0; frame < 2; frame++) {
      vector(b, -2, -2, -2);
      vector(b, 2, 2, 2);
      vector(b, frame, 2, 3);
      b.putFloat(3);
      name(b, "frame" + frame, 16);
    }
    for (int frame = 0; frame < 2; frame++) {
      name(b, "tag_torso", 64);
      vector(b, 0, 2 * frame, 4);
      vector(b, 1 - frame, frame, 0);
      vector(b, -frame, 1 - frame, 0);
      vector(b, 0, 0, 1);
    }
    b.putInt(0x33504449);
    name(b, "BODY_1", 64);
    b.putInt(9).putInt(2).putInt(1).putInt(3).putInt(1);
    b.putInt(176).putInt(108).putInt(188).putInt(212).putInt(260);
    name(b, "textures/test.tga", 64);
    b.putInt(7);
    b.putInt(0).putInt(1).putInt(2);
    b.putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(1);
    for (int frame = 0; frame < 2; frame++) {
      for (int vertex = 0; vertex < 3; vertex++) {
        b.putShort((short) (vertex == 1 ? 64 : 0));
        b.putShort((short) (vertex == 2 ? 64 : 0));
        b.putShort((short) (128 * frame));
        b.putShort((short) (frame == 0 ? 0 : 0x4040));
      }
    }
    return b.array();
  }

  static byte[] integer(int at, int value) {
    byte[] result = model();
    ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
    return result;
  }

  static String config(int rows) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < rows; i++) text.append(i * 10).append(" 10 5 20\n");
    return text.toString();
  }

  private static void name(ByteBuffer b, String name, int size) {
    int position = b.position();
    b.put(name.getBytes(StandardCharsets.US_ASCII));
    b.position(position + size);
  }

  private static void vector(ByteBuffer b, float x, float y, float z) {
    b.putFloat(x).putFloat(y).putFloat(z);
  }
}

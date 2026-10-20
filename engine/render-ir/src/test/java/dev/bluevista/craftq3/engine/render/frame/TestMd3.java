package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.formats.md3.Md3Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Writes a one-surface MD3 (IDP3 version 15) for tests: positions are in whole 1/64 units, every
 * vertex shares one packed normal ({@code latitude << 8 | longitude}).
 */
final class TestMd3 {
  private TestMd3() {}

  /** Packed (0, 0, 1). */
  static final int UP = 0;

  /**
   * @param frames per frame, xyz per vertex
   * @param triangles three vertex indices per triangle
   * @param texCoords st per vertex
   */
  static Md3Model model(
      String surface,
      String shader,
      float[][] frames,
      int[] triangles,
      float[] texCoords,
      int normal) {
    int vertices = texCoords.length / 2;
    int frameCount = frames.length;
    int surfaceBytes =
        108 + 68 + triangles.length * 4 + texCoords.length * 4 + frameCount * vertices * 8;
    int frameBytes = frameCount * 56;
    int size = 108 + frameBytes + surfaceBytes;
    ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(Md3Reader.IDENT).putInt(Md3Reader.VERSION);
    name(b, "test.md3", 64);
    b.putInt(0).putInt(frameCount).putInt(0).putInt(1).putInt(0);
    b.putInt(108).putInt(108 + frameBytes).putInt(108 + frameBytes).putInt(size);
    for (int f = 0; f < frameCount; f++) {
      for (int i = 0; i < 3; i++) b.putFloat(-64);
      for (int i = 0; i < 3; i++) b.putFloat(64);
      for (int i = 0; i < 3; i++) b.putFloat(0);
      b.putFloat(64);
      name(b, "frame" + f, 16);
    }
    int triangleOffset = 108 + 68;
    int stOffset = triangleOffset + triangles.length * 4;
    int xyzOffset = stOffset + texCoords.length * 4;
    b.putInt(Md3Reader.IDENT);
    name(b, surface, 64);
    b.putInt(0).putInt(frameCount).putInt(1).putInt(vertices).putInt(triangles.length / 3);
    b.putInt(triangleOffset).putInt(108).putInt(stOffset).putInt(xyzOffset).putInt(surfaceBytes);
    name(b, shader, 64);
    b.putInt(0);
    for (int index : triangles) b.putInt(index);
    for (float st : texCoords) b.putFloat(st);
    for (float[] frame : frames)
      for (int v = 0; v < vertices; v++) {
        for (int axis = 0; axis < 3; axis++)
          b.putShort((short) Math.round(frame[v * 3 + axis] / Md3Reader.XYZ_SCALE));
        b.putShort((short) normal);
      }
    return Md3Reader.read(b.array(), "test.md3");
  }

  /** The legacy fixture: a unit right triangle; frame 1 is frame 0 moved +2 along X. */
  static Md3Model triangle(String shader, int normal) {
    return model(
        "body",
        shader,
        new float[][] {{0, 0, 0, 1, 0, 0, 0, 1, 0}, {2, 0, 0, 3, 0, 0, 2, 1, 0}},
        new int[] {0, 2, 1},
        new float[] {0, 0, 1, 0, 0, 1},
        normal);
  }

  private static void name(ByteBuffer b, String name, int size) {
    int position = b.position();
    b.put(name.getBytes(StandardCharsets.US_ASCII));
    b.position(position + size);
  }
}

package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code refdef_t}, 368 bytes, identical in both profiles. */
public final class RefdefLayout {
  public static final int X = 0;
  public static final int Y = 4;
  public static final int WIDTH = 8;
  public static final int HEIGHT = 12;
  public static final int FOV_X = 16;
  public static final int FOV_Y = 20;
  public static final int VIEWORG = 24;
  public static final int VIEWAXIS = 36;
  public static final int TIME = 72;
  public static final int RDFLAGS = 76;
  public static final int AREAMASK = 80;

  /** {@code MAX_MAP_AREA_BYTES}. */
  public static final int AREAMASK_BYTES = 32;

  public static final int TEXT = 112;

  /** {@code MAX_RENDER_STRINGS} x {@code MAX_RENDER_STRING_LENGTH}. */
  public static final int TEXT_LINES = 8, TEXT_LINE_BYTES = 32;

  public static final int BYTES = TEXT + TEXT_LINES * TEXT_LINE_BYTES;

  private RefdefLayout() {}

  /** A decoded {@code refdef_t}. */
  public record Refdef(
      int x,
      int y,
      int width,
      int height,
      float fovX,
      float fovY,
      Vec3f viewOrigin,
      Vec3f[] viewAxis,
      int time,
      int rdFlags,
      byte[] areamask,
      String[] text) {}

  public static Refdef read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    String[] text = new String[TEXT_LINES];
    for (int i = 0; i < TEXT_LINES; i++)
      text[i] = VmStructs.readChars(memory, address + TEXT + i * TEXT_LINE_BYTES, TEXT_LINE_BYTES);
    return new Refdef(
        memory.readInt(address + X),
        memory.readInt(address + Y),
        memory.readInt(address + WIDTH),
        memory.readInt(address + HEIGHT),
        memory.readFloat(address + FOV_X),
        memory.readFloat(address + FOV_Y),
        VmStructs.readVec3(memory, address + VIEWORG),
        VmStructs.readAxis(memory, address + VIEWAXIS),
        memory.readInt(address + TIME),
        memory.readInt(address + RDFLAGS),
        memory.readBytes(address + AREAMASK, AREAMASK_BYTES),
        text);
  }

  public static void write(QvmMemory memory, int address, Refdef refdef) {
    memory.fill(address, BYTES, 0);
    memory.writeInt(address + X, refdef.x());
    memory.writeInt(address + Y, refdef.y());
    memory.writeInt(address + WIDTH, refdef.width());
    memory.writeInt(address + HEIGHT, refdef.height());
    memory.writeFloat(address + FOV_X, refdef.fovX());
    memory.writeFloat(address + FOV_Y, refdef.fovY());
    VmStructs.writeVec3(memory, address + VIEWORG, refdef.viewOrigin());
    VmStructs.writeAxis(memory, address + VIEWAXIS, refdef.viewAxis());
    memory.writeInt(address + TIME, refdef.time());
    memory.writeInt(address + RDFLAGS, refdef.rdFlags());
    if (refdef.areamask().length > AREAMASK_BYTES)
      throw new IllegalArgumentException("Area mask exceeds " + AREAMASK_BYTES + " bytes");
    memory.writeBytes(address + AREAMASK, refdef.areamask());
    for (int i = 0; i < Math.min(TEXT_LINES, refdef.text().length); i++)
      memory.writeString(address + TEXT + i * TEXT_LINE_BYTES, refdef.text()[i], TEXT_LINE_BYTES);
  }
}

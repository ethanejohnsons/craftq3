package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code glyphInfo_t}, 80 bytes (1.32 only: retail has no {@code trap_R_RegisterFont}). */
public final class GlyphInfoLayout {
  public static final int HEIGHT = 0;
  public static final int TOP = 4;
  public static final int BOTTOM = 8;
  public static final int PITCH = 12;
  public static final int X_SKIP = 16;
  public static final int IMAGE_WIDTH = 20;
  public static final int IMAGE_HEIGHT = 24;
  public static final int S = 28;
  public static final int T = 32;
  public static final int S2 = 36;
  public static final int T2 = 40;
  public static final int GLYPH = 44;
  public static final int SHADER_NAME = 48;

  /** {@code char shaderName[32]}. */
  public static final int SHADER_NAME_BYTES = 32;

  public static final int BYTES = SHADER_NAME + SHADER_NAME_BYTES;

  private GlyphInfoLayout() {}

  /** One glyph of a registered font. */
  public record GlyphInfo(
      int height,
      int top,
      int bottom,
      int pitch,
      int xSkip,
      int imageWidth,
      int imageHeight,
      float s,
      float t,
      float s2,
      float t2,
      int glyph,
      String shaderName) {}

  public static void write(QvmMemory memory, int address, GlyphInfo glyph) {
    memory.fill(address, BYTES, 0);
    memory.writeInt(address + HEIGHT, glyph.height());
    memory.writeInt(address + TOP, glyph.top());
    memory.writeInt(address + BOTTOM, glyph.bottom());
    memory.writeInt(address + PITCH, glyph.pitch());
    memory.writeInt(address + X_SKIP, glyph.xSkip());
    memory.writeInt(address + IMAGE_WIDTH, glyph.imageWidth());
    memory.writeInt(address + IMAGE_HEIGHT, glyph.imageHeight());
    memory.writeFloat(address + S, glyph.s());
    memory.writeFloat(address + T, glyph.t());
    memory.writeFloat(address + S2, glyph.s2());
    memory.writeFloat(address + T2, glyph.t2());
    memory.writeInt(address + GLYPH, glyph.glyph());
    memory.writeString(address + SHADER_NAME, glyph.shaderName(), SHADER_NAME_BYTES);
  }

  public static GlyphInfo read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    return new GlyphInfo(
        memory.readInt(address + HEIGHT),
        memory.readInt(address + TOP),
        memory.readInt(address + BOTTOM),
        memory.readInt(address + PITCH),
        memory.readInt(address + X_SKIP),
        memory.readInt(address + IMAGE_WIDTH),
        memory.readInt(address + IMAGE_HEIGHT),
        memory.readFloat(address + S),
        memory.readFloat(address + T),
        memory.readFloat(address + S2),
        memory.readFloat(address + T2),
        memory.readInt(address + GLYPH),
        VmStructs.readChars(memory, address + SHADER_NAME, SHADER_NAME_BYTES));
  }
}

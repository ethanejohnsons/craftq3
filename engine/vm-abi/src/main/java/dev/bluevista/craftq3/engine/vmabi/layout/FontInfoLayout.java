package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.vm.QvmMemory;
import java.util.List;

/** {@code fontInfo_t}: 256 glyphs, a scale and a {@code MAX_QPATH} name; 20548 bytes. */
public final class FontInfoLayout {
  /** {@code GLYPHS_PER_FONT}. */
  public static final int GLYPH_COUNT = 256;

  public static final int GLYPHS = 0;
  public static final int GLYPH_SCALE = GLYPH_COUNT * GlyphInfoLayout.BYTES;
  public static final int NAME = GLYPH_SCALE + 4;

  /** {@code MAX_QPATH}. */
  public static final int NAME_BYTES = 64;

  public static final int BYTES = NAME + NAME_BYTES;

  private FontInfoLayout() {}

  public static int glyph(int index) {
    if (index < 0 || index >= GLYPH_COUNT) throw new IndexOutOfBoundsException("Glyph " + index);
    return GLYPHS + index * GlyphInfoLayout.BYTES;
  }

  /** Writes a font; glyphs beyond {@code glyphs.size()} are zeroed. */
  public static void write(
      QvmMemory memory,
      int address,
      List<GlyphInfoLayout.GlyphInfo> glyphs,
      float glyphScale,
      String name) {
    if (glyphs.size() > GLYPH_COUNT)
      throw new IllegalArgumentException("A font has at most " + GLYPH_COUNT + " glyphs");
    memory.fill(address, BYTES, 0);
    for (int i = 0; i < glyphs.size(); i++)
      GlyphInfoLayout.write(memory, address + glyph(i), glyphs.get(i));
    memory.writeFloat(address + GLYPH_SCALE, glyphScale);
    memory.writeString(address + NAME, name, NAME_BYTES);
  }

  public static float glyphScale(QvmMemory memory, int address) {
    return memory.readFloat(address + GLYPH_SCALE);
  }

  public static String name(QvmMemory memory, int address) {
    return VmStructs.readChars(memory, address + NAME, NAME_BYTES);
  }
}

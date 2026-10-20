package dev.bluevista.craftq3.engine.formats.bsp;

/**
 * The seventeen lumps of an IBSP version 46/47 file, in header order, with their record sizes.
 * Lightmaps and the light grid are read leniently: a trailing partial record is ignored, as the
 * original renderer does.
 */
public enum BspLump {
  ENTITIES(1),
  TEXTURES(72),
  PLANES(16),
  NODES(36),
  LEAVES(48),
  LEAF_FACES(4),
  LEAF_BRUSHES(4),
  MODELS(40),
  BRUSHES(12),
  BRUSH_SIDES(8),
  VERTICES(44),
  MESH_VERTICES(4),
  EFFECTS(72),
  FACES(104),
  LIGHTMAPS(BspMap.LIGHTMAP_BYTES),
  LIGHT_GRID(8),
  VISIBILITY(1);

  /** Byte size of the header: ident, version and one offset/length pair per lump. */
  public static final int HEADER_BYTES = 8 + 8 * 17;

  private final int stride;

  BspLump(int stride) {
    this.stride = stride;
  }

  /** Position of this lump's offset/length pair in the header directory. */
  public int index() {
    return ordinal();
  }

  /** Byte size of one record. */
  public int stride() {
    return stride;
  }

  /** Whether a trailing partial record makes the file invalid (as {@code "funny lump size"}). */
  public boolean requiresWholeRecords() {
    return this != LIGHTMAPS && this != LIGHT_GRID;
  }

  /** Byte offset of this lump's directory entry within the header. */
  public int headerOffset() {
    return 8 + 8 * ordinal();
  }
}

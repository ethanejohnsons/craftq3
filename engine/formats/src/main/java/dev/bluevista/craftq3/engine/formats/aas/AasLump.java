package dev.bluevista.craftq3.engine.formats.aas;

/** The fourteen lumps of an EAAS file, in header order, with record sizes and element budgets. */
public enum AasLump {
  BOUNDING_BOXES(32, 64),
  VERTICES(12, 262_144),
  PLANES(20, 131_072),
  EDGES(8, 524_288),
  EDGE_INDICES(4, 2_097_152),
  FACES(24, 262_144),
  FACE_INDICES(4, 1_048_576),
  AREAS(48, 65_536),
  AREA_SETTINGS(28, 65_536),
  REACHABILITIES(44, 1_048_576),
  NODES(12, 262_144),
  PORTALS(20, 65_536),
  PORTAL_INDICES(4, 131_072),
  CLUSTERS(16, 65_536);

  /** Ident, version, BSP checksum and one offset/length pair per lump. */
  public static final int HEADER_BYTES = 12 + 8 * 14;

  private final int stride;
  private final int maxElements;

  AasLump(int stride, int maxElements) {
    this.stride = stride;
    this.maxElements = maxElements;
  }

  public int index() {
    return ordinal();
  }

  public int stride() {
    return stride;
  }

  public int maxElements() {
    return maxElements;
  }

  /** Byte offset of this lump's directory entry within the header. */
  public int headerOffset() {
    return 12 + 8 * ordinal();
  }
}

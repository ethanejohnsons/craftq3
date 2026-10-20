package dev.bluevista.craftq3.engine.formats.bsp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Assembles an IBSP file from raw lump payloads, laid out back to back after the header. */
public final class BspBuilder {
  private final Map<BspLump, byte[]> lumps = new EnumMap<>(BspLump.class);
  private int version = BspMap.VERSION_Q3;

  public BspBuilder version(int value) {
    this.version = value;
    return this;
  }

  public BspBuilder lump(BspLump kind, byte[] payload) {
    lumps.put(kind, payload.clone());
    return this;
  }

  public BspBuilder entities(String text) {
    return lump(BspLump.ENTITIES, (text + '\0').getBytes(StandardCharsets.ISO_8859_1));
  }

  public byte[] build() {
    int size = BspLump.HEADER_BYTES;
    for (BspLump kind : BspLump.values()) size += payload(kind).length;
    ByteBuffer file = buffer(size).putInt(BspMap.IDENT).putInt(version);
    int offset = BspLump.HEADER_BYTES;
    for (BspLump kind : BspLump.values()) {
      file.putInt(offset).putInt(payload(kind).length);
      offset += payload(kind).length;
    }
    for (BspLump kind : BspLump.values()) file.put(payload(kind));
    return file.array();
  }

  private byte[] payload(BspLump kind) {
    return lumps.getOrDefault(kind, new byte[0]);
  }

  /** A zeroed little-endian buffer. */
  public static ByteBuffer buffer(int size) {
    return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
  }

  /** Writes {@code value} NUL-padded to {@code width} bytes. */
  public static void name(ByteBuffer into, String value, int width) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    if (bytes.length >= width) throw new IllegalArgumentException("Fixture name too long");
    into.put(bytes);
    for (int i = bytes.length; i < width; i++) into.put((byte) 0);
  }
}

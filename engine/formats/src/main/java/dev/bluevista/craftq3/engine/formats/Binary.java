package dev.bluevista.craftq3.engine.formats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Little-endian helpers shared by the binary readers. */
public final class Binary {
  private Binary() {}

  /** A little-endian view of {@code data}. */
  public static ByteBuffer littleEndian(byte[] data) {
    return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
  }

  /** A little-endian view of {@code length} bytes of {@code data} at {@code offset}. */
  public static ByteBuffer littleEndian(byte[] data, int offset, int length) {
    return ByteBuffer.wrap(data, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  /** Reads a NUL-padded fixed-width Latin-1 string and advances {@code buffer} by {@code width}. */
  public static String fixedString(ByteBuffer buffer, int width) {
    byte[] bytes = new byte[width];
    buffer.get(bytes);
    int length = 0;
    while (length < width && bytes[length] != 0) length++;
    return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
  }

  /** Text up to the first NUL, decoded as Latin-1 like the original byte-oriented parsers. */
  public static String latin1UntilNul(byte[] data) {
    int length = 0;
    while (length < data.length && data[length] != 0) length++;
    return new String(data, 0, length, StandardCharsets.ISO_8859_1);
  }
}

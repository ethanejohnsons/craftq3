package dev.bluevista.craftq3.engine.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Test-only builder of raw (non-Huffman) bit streams, least significant bit first, as protocol 48
 * sends them.
 */
public final class RawBitWriter {
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private int current, used;

  public RawBitWriter bits(long value, int width) {
    for (int i = 0; i < width; i++) {
      current |= (int) ((value >>> i) & 1) << used;
      if (++used == 8) {
        bytes.write(current);
        current = used = 0;
      }
    }
    return this;
  }

  public RawBitWriter bit(boolean set) {
    return bits(set ? 1 : 0, 1);
  }

  public RawBitWriter byteValue(int value) {
    return bits(value, 8);
  }

  public RawBitWriter shortValue(int value) {
    return bits(value, 16);
  }

  public RawBitWriter intValue(int value) {
    return bits(value, 32);
  }

  /** The float encoding: a 0 bit and 13 bits of value + 4096 when integral and small. */
  public RawBitWriter floatValue(float value) {
    if (value == (int) value && value >= -4096 && value <= 4095)
      return bits(0, 1).bits((int) value + 4096, 13);
    return bits(1, 1).intValue(Float.floatToRawIntBits(value));
  }

  public RawBitWriter string(String text) {
    for (byte b : text.getBytes(StandardCharsets.ISO_8859_1)) byteValue(b & 255);
    return byteValue(0);
  }

  /** Zero-pads the last byte and returns the message. */
  public byte[] toByteArray() {
    var copy = new ByteArrayOutputStream();
    copy.writeBytes(bytes.toByteArray());
    if (used > 0) copy.write(current);
    return copy.toByteArray();
  }

  public int bitLength() {
    return bytes.size() * 8 + used;
  }
}

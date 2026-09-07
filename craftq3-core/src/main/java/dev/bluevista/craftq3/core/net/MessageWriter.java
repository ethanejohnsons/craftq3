package dev.bluevista.craftq3.core.net;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Bounded protocol-68 bit fields: low residual bits followed by fixed-Huffman byte symbols. */
public final class MessageWriter {
  private final byte[] data;
  private int bit;

  public MessageWriter() {
    this(Protocol68Channel.MAX_MESSAGE);
  }

  public MessageWriter(int capacity) {
    if (capacity < 1 || capacity > Protocol68Channel.MAX_MESSAGE)
      throw new IllegalArgumentException("Message capacity must be 1..16384");
    data = new byte[capacity];
  }

  public int bitPosition() {
    return bit;
  }

  /**
   * Groups fields atomically. If the callback fails, its new bits are cleared and the original
   * cursor is restored. Nested transactions are supported; callbacks must not retain this writer.
   */
  public <T> T transaction(Function<MessageWriter, T> operation) {
    Objects.requireNonNull(operation);
    int checkpoint = bit;
    try {
      return operation.apply(this);
    } catch (RuntimeException | Error failure) {
      if (bit != checkpoint) {
        int first = checkpoint >>> 3;
        data[first] &= (byte) ((1 << (checkpoint & 7)) - 1);
        Arrays.fill(data, first + 1, (bit + 7) >>> 3, (byte) 0);
        bit = checkpoint;
      }
      throw failure;
    }
  }

  /** Writes the low width bits of value. Capacity failure leaves the message untouched. */
  public void bits(int value, int width) {
    checkWidth(width);
    int residual = width & 7;
    int required = residual;
    for (int shift = residual; shift < width; shift += 8)
      required += HuffmanCodebook.length(HuffmanCodebook.code((value >>> shift) & 255));
    // Native wire messages retain a trailing byte when the final bit is exactly byte-aligned.
    if (bit + required >= data.length * 8)
      throw new IllegalArgumentException("Compressed message capacity exceeded");
    raw(value, residual);
    for (int shift = residual; shift < width; shift += 8) {
      int code = HuffmanCodebook.code((value >>> shift) & 255);
      raw(code, HuffmanCodebook.length(code));
    }
  }

  public void byteValue(int value) {
    bits(value, 8);
  }

  /** Native MSG field convention; negative widths encode the same low bits as positive widths. */
  public void field(int value, int width) {
    if (width < -31 || width == 0 || width > 32)
      throw new IllegalArgumentException("Invalid MSG field width");
    bits(value, Math.abs(width));
  }

  public void shortValue(int value) {
    bits(value, 16);
  }

  public void intValue(int value) {
    bits(value, 32);
  }

  public void floatValue(float value) {
    intValue(Float.floatToRawIntBits(value));
  }

  /** NUL-terminated byte text; null and strings of 1024 or more bytes encode as empty. */
  public void stringValue(String value) {
    string(value, MessageStrings.NORMAL_CAPACITY);
  }

  /** System-info byte text; the native capacity, including its terminator, is 8192 bytes. */
  public void bigStringValue(String value) {
    string(value, MessageStrings.BIG_CAPACITY);
  }

  private void string(String value, int capacity) {
    int length = value == null ? 0 : Math.min(value.length(), capacity);
    for (int i = 0; i < length; i++)
      if (value.charAt(i) == 0) {
        length = i;
        break;
      }
    if (length == capacity) length = 0;
    final int count = length;
    transaction(
        writer -> {
          for (int i = 0; i < count; i++) {
            char c = value.charAt(i);
            if (c > 255) throw new IllegalArgumentException("Wire strings require byte text");
            writer.byteValue(MessageStrings.sanitize(c));
          }
          writer.byteValue(0);
          return null;
        });
  }

  public byte[] bytes() {
    return Arrays.copyOf(data, bit == 0 ? 0 : bit / 8 + 1);
  }

  static void checkWidth(int width) {
    if (width < 1 || width > 32)
      throw new IllegalArgumentException("Bit field width must be 1..32");
  }

  private void raw(int value, int count) {
    for (int i = 0; i < count; i++, bit++)
      data[bit >>> 3] |= (byte) (((value >>> i) & 1) << (bit & 7));
  }
}

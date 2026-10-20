package dev.bluevista.craftq3.engine.net.msg;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.huffman.HuffmanCodebook;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Bounded protocol-68 bit fields: the low {@code width % 8} bits are written raw, then each whole
 * byte as a fixed-Huffman symbol (see {@link HuffmanCodebook}). The writer owns one buffer and can
 * be {@link #reset() reset} and reused, so steady-state encoding does not allocate. Confined to one
 * thread.
 */
public final class MessageWriter {
  private final byte[] data;
  private int bit;

  public MessageWriter() {
    this(Protocol68.MAX_MSGLEN);
  }

  public MessageWriter(int capacity) {
    if (capacity < 1 || capacity > Protocol68.MAX_MSGLEN)
      throw new IllegalArgumentException("Message capacity must be 1..16384");
    data = new byte[capacity];
  }

  public int bitPosition() {
    return bit;
  }

  public int capacity() {
    return data.length;
  }

  /** Clears the message for reuse. */
  public void reset() {
    Arrays.fill(data, 0, byteLength(), (byte) 0);
    bit = 0;
  }

  /** A cursor checkpoint for {@link #rollback(int)}. */
  public int mark() {
    return bit;
  }

  /** Clears every bit written after {@code checkpoint} and restores the cursor to it. */
  public void rollback(int checkpoint) {
    if (checkpoint < 0 || checkpoint > bit)
      throw new IllegalArgumentException("Invalid checkpoint");
    if (bit == checkpoint) return;
    int first = checkpoint >>> 3;
    data[first] &= (byte) ((1 << (checkpoint & 7)) - 1);
    Arrays.fill(data, first + 1, (bit + 7) >>> 3, (byte) 0);
    bit = checkpoint;
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
      rollback(checkpoint);
      throw failure;
    }
  }

  /** Writes the low width bits of value. Capacity failure leaves the message untouched. */
  public void bits(int value, int width) {
    checkWidth(width);
    int residual = width & 7;
    int required = residual;
    for (int shift = residual; shift < width; shift += 8)
      required += HuffmanCodebook.symbolLength((value >>> shift) & 255);
    // Native wire messages retain a trailing byte when the final bit is exactly byte-aligned.
    if (bit + required >= data.length * 8)
      throw new IllegalArgumentException("Compressed message capacity exceeded");
    raw(value & ((1 << residual) - 1), residual);
    for (int shift = residual; shift < width; shift += 8) {
      int symbol = (value >>> shift) & 255;
      raw(HuffmanCodebook.bits(symbol), HuffmanCodebook.symbolLength(symbol));
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
    string(value, Protocol68.MAX_STRING_CHARS);
  }

  /** System-info byte text; the native capacity, including its terminator, is 8192 bytes. */
  public void bigStringValue(String value) {
    string(value, Protocol68.BIG_INFO_STRING);
  }

  private void string(String value, int capacity) {
    int length = value == null ? 0 : Math.min(value.length(), capacity);
    for (int i = 0; i < length; i++)
      if (value.charAt(i) == 0) {
        length = i;
        break;
      }
    if (length == capacity) length = 0;
    int checkpoint = bit;
    try {
      for (int i = 0; i < length; i++) {
        char c = value.charAt(i);
        if (c > 255) throw new IllegalArgumentException("Wire strings require byte text");
        byteValue(MessageStrings.sanitize(c));
      }
      byteValue(0);
    } catch (RuntimeException failure) {
      rollback(checkpoint);
      throw failure;
    }
  }

  /** Encoded size in bytes, including the native trailing byte of an aligned message. */
  public int byteLength() {
    return bit == 0 ? 0 : bit / 8 + 1;
  }

  /** An owned copy of the encoded message. */
  public byte[] bytes() {
    return Arrays.copyOf(data, byteLength());
  }

  /** Copies the encoded message into {@code target} and returns its length. */
  public int copyTo(byte[] target, int offset) {
    int length = byteLength();
    System.arraycopy(data, 0, target, offset, length);
    return length;
  }

  static void checkWidth(int width) {
    if (width < 1 || width > 32)
      throw new IllegalArgumentException("Bit field width must be 1..32");
  }

  /** Appends the low {@code count} (at most 24) bits of {@code value}, least significant first. */
  private void raw(int value, int count) {
    while (count > 0) {
      int index = bit >>> 3, shift = bit & 7;
      int take = Math.min(8 - shift, count);
      data[index] |= (byte) ((value & ((1 << take) - 1)) << shift);
      value >>>= take;
      bit += take;
      count -= take;
    }
  }
}

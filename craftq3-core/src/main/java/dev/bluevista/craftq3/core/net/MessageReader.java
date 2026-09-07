package dev.bluevista.craftq3.core.net;

import java.util.Objects;
import java.util.function.Function;

/** Owned compressed message with checked, transactional bit-field reads. */
public final class MessageReader {
  private final byte[] data;
  private final int limit;
  private int bit;

  public MessageReader(byte[] data) {
    this(data, data.length * 8);
  }

  /** An optional exact bit limit supports deterministic tests and nested message boundaries. */
  public MessageReader(byte[] data, int bitLimit) {
    if (data.length > Protocol68Channel.MAX_MESSAGE || bitLimit < 0 || bitLimit > data.length * 8)
      throw new IllegalArgumentException("Invalid compressed message bounds");
    this.data = data.clone();
    limit = bitLimit;
  }

  public int bitPosition() {
    return bit;
  }

  public int remainingBits() {
    return limit - bit;
  }

  /** Groups reads atomically, restoring the cursor if the callback fails. */
  public <T> T transaction(Function<MessageReader, T> operation) {
    Objects.requireNonNull(operation);
    int checkpoint = bit;
    try {
      return operation.apply(this);
    } catch (RuntimeException | Error failure) {
      bit = checkpoint;
      throw failure;
    }
  }

  /** Returns width bits; a width of 32 preserves their exact int representation. */
  public int bits(int width) {
    MessageWriter.checkWidth(width);
    int cursor = bit;
    int residual = width & 7;
    if (residual > limit - cursor) throw truncated();
    int value = window(cursor, residual);
    cursor += residual;
    for (int shift = residual; shift < width; shift += 8) {
      int available = Math.min(11, limit - cursor);
      int code = HuffmanCodebook.decode(window(cursor, available));
      int length = code >>> 8;
      if (length == 0 || length > available) throw truncated();
      value |= (code & 255) << shift;
      cursor += length;
    }
    bit = cursor;
    return value;
  }

  public int signedBits(int width) {
    int value = bits(width);
    return value << (32 - width) >> (32 - width);
  }

  /**
   * Native MSG field convention: negative widths request sign extension. The reference uses the
   * whole-byte portion's sign bit for unaligned widths; preserve that observed wire behavior here.
   * Use signedBits for mathematical sign extension of an arbitrary width.
   */
  public int field(int width) {
    if (width >= 1) return bits(width);
    if (width < -31 || width == 0) throw new IllegalArgumentException("Invalid MSG field width");
    int value = bits(-width);
    int signWidth = (-width) & ~7;
    if (signWidth > 0 && (value & (1 << (signWidth - 1))) != 0) value |= -1 << signWidth;
    return value;
  }

  public int byteValue() {
    return bits(8);
  }

  public int shortValue() {
    return signedBits(16);
  }

  public int intValue() {
    return bits(32);
  }

  public float floatValue() {
    return Float.intBitsToFloat(intValue());
  }

  /** Reads at most 1023 bytes; the native overlong form consumes one additional byte. */
  public String stringValue() {
    return string(MessageStrings.NORMAL_CAPACITY, false);
  }

  public String bigStringValue() {
    return string(MessageStrings.BIG_CAPACITY, false);
  }

  /** A newline terminates this form and is consumed; a carriage return is ordinary data. */
  public String stringLine() {
    return string(MessageStrings.NORMAL_CAPACITY, true);
  }

  private String string(int capacity, boolean line) {
    return transaction(
        reader -> {
          var text = new StringBuilder();
          while (true) {
            int c = reader.byteValue();
            if (c == 0 || line && c == '\n' || text.length() == capacity - 1)
              return text.toString();
            text.append(MessageStrings.sanitize(c));
          }
        });
  }

  private int window(int position, int length) {
    int result = 0;
    for (int i = 0; i < length; i++)
      result |= ((data[(position + i) >>> 3] >>> ((position + i) & 7)) & 1) << i;
    return result;
  }

  private IllegalArgumentException truncated() {
    return new IllegalArgumentException("Truncated or invalid Huffman field at bit " + bit);
  }
}

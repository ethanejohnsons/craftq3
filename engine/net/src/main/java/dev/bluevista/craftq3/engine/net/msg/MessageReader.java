package dev.bluevista.craftq3.engine.net.msg;

import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.huffman.HuffmanCodebook;
import java.util.Objects;
import java.util.function.Function;

/**
 * Checked, transactional bit-field reads over an owned copy of a message. A reader can be {@link
 * #reset(byte[], int, int) reset} with new bytes and reused; it then copies into its existing
 * buffer and does not allocate. Confined to one thread.
 *
 * <p>Two bit encodings exist. {@link Coding#HUFFMAN} is the protocol 66 to 68 encoding: residual
 * low bits raw, then fixed-Huffman byte symbols. {@link Coding#RAW} is the uncompressed encoding of
 * protocol 48 (1.27) and earlier: every bit raw, least significant first.
 */
public final class MessageReader {
  /** Bit encoding of the message. */
  public enum Coding {
    HUFFMAN,
    RAW
  }

  private byte[] data;
  private int limit;
  private int bit;
  private Coding coding = Coding.HUFFMAN;
  private char[] text;

  /** An empty reusable reader; call {@link #reset(byte[], int, int)} before reading. */
  public MessageReader() {
    data = new byte[0];
  }

  public MessageReader(byte[] data) {
    this(data, data.length * 8);
  }

  /** An optional exact bit limit supports deterministic tests and nested message boundaries. */
  public MessageReader(byte[] data, int bitLimit) {
    if (data.length > Protocol68.MAX_MSGLEN || bitLimit < 0 || bitLimit > data.length * 8)
      throw new IllegalArgumentException("Invalid compressed message bounds");
    this.data = data.clone();
    limit = bitLimit;
  }

  /** Replaces the message with a copy of {@code source[offset, offset + length)}. */
  public MessageReader reset(byte[] source, int offset, int length) {
    if (length < 0
        || length > Protocol68.MAX_MSGLEN
        || offset < 0
        || offset > source.length - length)
      throw new IllegalArgumentException("Invalid compressed message bounds");
    if (data.length < length)
      data = new byte[Math.max(length, Math.min(Protocol68.MAX_MSGLEN, data.length * 2))];
    System.arraycopy(source, offset, data, 0, length);
    limit = length * 8;
    bit = 0;
    return this;
  }

  public Coding coding() {
    return coding;
  }

  /** Selects the bit encoding; the default is {@link Coding#HUFFMAN}. */
  public MessageReader coding(Coding value) {
    coding = Objects.requireNonNull(value);
    return this;
  }

  public int bitPosition() {
    return bit;
  }

  public int remainingBits() {
    return limit - bit;
  }

  /** A cursor checkpoint for {@link #rewind(int)}. */
  public int mark() {
    return bit;
  }

  /** Restores a checkpoint returned by {@link #mark()}. */
  public void rewind(int checkpoint) {
    if (checkpoint < 0 || checkpoint > limit) throw new IllegalArgumentException("Invalid mark");
    bit = checkpoint;
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
    if (coding == Coding.RAW) {
      if (width > limit - cursor) throw truncated();
      int value = window(cursor, Math.min(width, 16));
      if (width > 16) value |= window(cursor + 16, width - 16) << 16;
      bit = cursor + width;
      return value;
    }
    int residual = width & 7;
    if (residual > limit - cursor) throw truncated();
    int value = window(cursor, residual);
    cursor += residual;
    for (int shift = residual; shift < width; shift += 8) {
      int available = Math.min(HuffmanCodebook.MAX_CODE_BITS, limit - cursor);
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
    return string(Protocol68.MAX_STRING_CHARS, false);
  }

  public String bigStringValue() {
    return string(Protocol68.BIG_INFO_STRING, false);
  }

  /** A newline terminates this form and is consumed; a carriage return is ordinary data. */
  public String stringLine() {
    return string(Protocol68.MAX_STRING_CHARS, true);
  }

  private String string(int capacity, boolean line) {
    if (text == null || text.length < capacity) text = new char[capacity];
    int checkpoint = bit;
    try {
      int count = 0;
      while (true) {
        int c = byteValue();
        if (c == 0 || line && c == '\n' || count == capacity - 1) return new String(text, 0, count);
        text[count++] = MessageStrings.sanitize(c);
      }
    } catch (RuntimeException failure) {
      bit = checkpoint;
      throw failure;
    }
  }

  /**
   * Up to 16 bits starting at {@code position}, least significant first; bits past the data read 0.
   */
  private int window(int position, int length) {
    if (length == 0) return 0;
    int index = position >>> 3;
    int value = data[index] & 255;
    if (index + 1 < data.length) value |= (data[index + 1] & 255) << 8;
    if (index + 2 < data.length) value |= (data[index + 2] & 255) << 16;
    return (value >>> (position & 7)) & ((1 << length) - 1);
  }

  private NetFormatException truncated() {
    return new NetFormatException("Truncated or invalid Huffman field at bit " + bit);
  }
}

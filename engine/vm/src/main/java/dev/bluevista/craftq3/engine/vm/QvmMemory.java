package dev.bluevista.craftq3.engine.vm;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A VM's little-endian data segment, sized to a power of two. Guest addresses use Quake III's
 * data-mask semantics: the starting address is masked with {@link #mask()} (so it always lands
 * inside the segment), and the whole range from there must fit without wrapping. Every host
 * accessor checks the complete range.
 */
public final class QvmMemory {
  static final VarHandle INT =
      MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
  static final VarHandle SHORT =
      MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

  final byte[] data;
  private final int mask;

  QvmMemory(int size) {
    if (size <= 0 || Integer.bitCount(size) != 1)
      throw new IllegalArgumentException("QVM memory size must be a power of two: " + size);
    data = new byte[size];
    mask = size - 1;
  }

  public int size() {
    return data.length;
  }

  /** {@code size() - 1}: the mask applied to every guest address. */
  public int mask() {
    return mask;
  }

  /**
   * Translates a guest address to its offset in the segment and checks that {@code length} bytes
   * fit from there. Returns the masked offset.
   */
  public int resolve(int address, int length) {
    int offset = address & mask;
    if (length < 0 || length > data.length - offset)
      throw new QvmException(
          QvmException.Reason.MEMORY_BOUNDS,
          "Memory range "
              + Integer.toUnsignedString(address)
              + "+"
              + length
              + " exceeds "
              + data.length
              + " bytes");
    return offset;
  }

  public int readUnsignedByte(int address) {
    return data[address & mask] & 0xff;
  }

  public int readUnsignedShort(int address) {
    return (short) SHORT.get(data, resolve(address, 2)) & 0xffff;
  }

  public int readInt(int address) {
    return (int) INT.get(data, resolve(address, 4));
  }

  public float readFloat(int address) {
    return Float.intBitsToFloat(readInt(address));
  }

  public void writeByte(int address, int value) {
    data[address & mask] = (byte) value;
  }

  public void writeShort(int address, int value) {
    SHORT.set(data, resolve(address, 2), (short) value);
  }

  public void writeInt(int address, int value) {
    INT.set(data, resolve(address, 4), value);
  }

  public void writeFloat(int address, float value) {
    writeInt(address, Float.floatToRawIntBits(value));
  }

  /** A copy of {@code length} bytes. */
  public byte[] readBytes(int address, int length) {
    int offset = resolve(address, length);
    return Arrays.copyOfRange(data, offset, offset + length);
  }

  public void writeBytes(int address, byte[] bytes) {
    System.arraycopy(bytes, 0, data, resolve(address, bytes.length), bytes.length);
  }

  public void fill(int address, int length, int value) {
    int offset = resolve(address, length);
    Arrays.fill(data, offset, offset + length, (byte) value);
  }

  /** Copies within the segment with {@code memmove} semantics (overlap is defined). */
  public void copy(int destination, int source, int length) {
    int from = resolve(source, length);
    int to = resolve(destination, length);
    System.arraycopy(data, from, data, to, length);
  }

  /**
   * A little-endian view of {@code length} bytes that writes through to VM memory. The view is only
   * valid while the owning VM is not reset; never retain it across frames.
   */
  public ByteBuffer slice(int address, int length) {
    return ByteBuffer.wrap(data, resolve(address, length), length)
        .slice()
        .order(ByteOrder.LITTLE_ENDIAN);
  }

  /**
   * Reads a NUL-terminated ISO-8859-1 string of at most {@code maxBytes} bytes including the
   * terminator. Fails if no terminator lies within the bound and the segment.
   */
  public String readString(int address, int maxBytes) {
    if (maxBytes <= 0)
      throw new QvmException(
          QvmException.Reason.MEMORY_BOUNDS, "String read requires a positive bound");
    int offset = resolve(address, 1);
    int available = Math.min(maxBytes, data.length - offset);
    for (int i = 0; i < available; i++)
      if (data[offset + i] == 0) return new String(data, offset, i, StandardCharsets.ISO_8859_1);
    throw new QvmException(
        QvmException.Reason.MEMORY_BOUNDS,
        "Unterminated VM string at " + offset + " within " + maxBytes + " bytes");
  }

  /**
   * Writes {@code text} as ISO-8859-1 into a {@code maxBytes} buffer, truncating so the NUL
   * terminator always fits (like {@code Q_strncpyz}). The whole buffer must lie in memory. Returns
   * the number of characters written, excluding the terminator.
   */
  public int writeString(int address, String text, int maxBytes) {
    int offset = resolve(address, Math.max(maxBytes, 0));
    if (maxBytes <= 0) return 0;
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    int count = Math.min(bytes.length, maxBytes - 1);
    System.arraycopy(bytes, 0, data, offset, count);
    data[offset + count] = 0;
    return count;
  }

  void reset(byte[] initial) {
    System.arraycopy(initial, 0, data, 0, initial.length);
    Arrays.fill(data, initial.length, data.length, (byte) 0);
  }
}

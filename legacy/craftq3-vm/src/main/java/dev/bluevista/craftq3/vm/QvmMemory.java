package dev.bluevista.craftq3.vm;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Owned little-endian bytes. Public host access always checks the complete range without wrapping.
 */
public final class QvmMemory {
  private final byte[] data;

  QvmMemory(int size) {
    data = new byte[size];
  }

  public int size() {
    return data.length;
  }

  public void checkRange(int address, int length) {
    if (address < 0 || length < 0 || (long) address + length > data.length) {
      throw new QvmException(
          QvmException.Reason.MEMORY_BOUNDS,
          "Memory range " + address + "+" + length + " exceeds " + data.length + " bytes");
    }
  }

  public int readUnsignedByte(int address) {
    checkRange(address, 1);
    return data[address] & 255;
  }

  public int readUnsignedShort(int address) {
    checkRange(address, 2);
    return (data[address] & 255) | (data[address + 1] & 255) << 8;
  }

  public int readInt(int address) {
    checkRange(address, 4);
    return (data[address] & 255)
        | (data[address + 1] & 255) << 8
        | (data[address + 2] & 255) << 16
        | data[address + 3] << 24;
  }

  public float readFloat(int address) {
    return Float.intBitsToFloat(readInt(address));
  }

  public void writeByte(int address, int value) {
    checkRange(address, 1);
    data[address] = (byte) value;
  }

  public void writeShort(int address, int value) {
    checkRange(address, 2);
    data[address] = (byte) value;
    data[address + 1] = (byte) (value >>> 8);
  }

  public void writeInt(int address, int value) {
    checkRange(address, 4);
    data[address] = (byte) value;
    data[address + 1] = (byte) (value >>> 8);
    data[address + 2] = (byte) (value >>> 16);
    data[address + 3] = (byte) (value >>> 24);
  }

  public void writeFloat(int address, float value) {
    writeInt(address, Float.floatToRawIntBits(value));
  }

  public byte[] readBytes(int address, int length) {
    checkRange(address, length);
    return Arrays.copyOfRange(data, address, address + length);
  }

  public void writeBytes(int address, byte[] bytes) {
    checkRange(address, bytes.length);
    System.arraycopy(bytes, 0, data, address, bytes.length);
  }

  public void fill(int address, int length, int value) {
    checkRange(address, length);
    Arrays.fill(data, address, address + length, (byte) value);
  }

  public void copy(int destination, int source, int length) {
    checkRange(source, length);
    checkRange(destination, length);
    System.arraycopy(data, source, data, destination, length);
  }

  public String readCString(int address, int maxBytes) {
    checkRange(address, 1);
    if (maxBytes <= 0)
      throw new QvmException(
          QvmException.Reason.MEMORY_BOUNDS, "String read requires a positive bound");
    int available = Math.min(maxBytes, data.length - address);
    for (int i = 0; i < available; i++)
      if (data[address + i] == 0) return new String(data, address, i, StandardCharsets.ISO_8859_1);
    throw new QvmException(
        QvmException.Reason.MEMORY_BOUNDS, "Unterminated VM string within requested bound");
  }

  public int writeCString(int address, String text, int maxBytes) {
    checkRange(address, maxBytes);
    if (maxBytes <= 0) return 0;
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    int count = Math.min(bytes.length, maxBytes - 1);
    System.arraycopy(bytes, 0, data, address, count);
    data[address + count] = 0;
    return count;
  }

  void reset(byte[] initial) {
    Arrays.fill(data, (byte) 0);
    writeBytes(0, initial);
  }
}

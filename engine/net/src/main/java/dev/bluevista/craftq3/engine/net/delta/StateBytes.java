package dev.bluevista.craftq3.engine.net.delta;

/** Little-endian word access to canonical state images. */
final class StateBytes {
  private StateBytes() {}

  /** An owned copy of {@code bytes}, or a zero state when {@code bytes} is null. */
  static byte[] baseline(byte[] bytes, int size) {
    return bytes == null ? new byte[size] : copy(bytes, size);
  }

  static byte[] copy(byte[] bytes, int size) {
    check(bytes, size);
    return bytes.clone();
  }

  static void check(byte[] bytes, int size) {
    if (bytes.length != size)
      throw new IllegalArgumentException("State must contain exactly " + size + " bytes");
  }

  static int word(byte[] bytes, int offset) {
    return (bytes[offset] & 255)
        | (bytes[offset + 1] & 255) << 8
        | (bytes[offset + 2] & 255) << 16
        | bytes[offset + 3] << 24;
  }

  static void word(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) value;
    bytes[offset + 1] = (byte) (value >>> 8);
    bytes[offset + 2] = (byte) (value >>> 16);
    bytes[offset + 3] = (byte) (value >>> 24);
  }
}

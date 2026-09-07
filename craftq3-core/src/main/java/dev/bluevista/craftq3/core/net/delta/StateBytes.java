package dev.bluevista.craftq3.core.net.delta;

final class StateBytes {
  private StateBytes() {}

  static byte[] baseline(byte[] bytes, int size) {
    return bytes == null ? new byte[size] : copy(bytes, size);
  }

  static byte[] copy(byte[] bytes, int size) {
    if (bytes.length != size)
      throw new IllegalArgumentException("State must contain exactly " + size + " bytes");
    return bytes.clone();
  }

  static int word(byte[] bytes, int offset) {
    return (bytes[offset] & 255)
        | (bytes[offset + 1] & 255) << 8
        | (bytes[offset + 2] & 255) << 16
        | bytes[offset + 3] << 24;
  }

  static void word(byte[] bytes, int offset, int value) {
    for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
  }
}

package dev.bluevista.craftq3.botlib.weapon;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class WeaponAbi {
  private WeaponAbi() {}

  static String string(String value) {
    Objects.requireNonNull(value);
    if (value.length() > 79 || value.chars().anyMatch(c -> c == 0 || c > 255))
      throw new IllegalArgumentException(
          "Weapon ABI string must be non-NUL Latin-1 and at most 79 bytes");
    return value;
  }

  static void finite(float... values) {
    for (float value : values)
      if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite weapon value");
  }

  static Vec3 vector(Vec3 value) {
    Objects.requireNonNull(value);
    finite((float) value.x(), (float) value.y(), (float) value.z());
    return new Vec3((float) value.x(), (float) value.y(), (float) value.z());
  }

  static ByteBuffer output(ByteBuffer destination, int offset, int size) {
    Objects.requireNonNull(destination);
    if (offset < 0 || (long) offset + size > destination.limit())
      throw new IndexOutOfBoundsException("Weapon ABI destination range");
    if (destination.isReadOnly()) throw new ReadOnlyBufferException();
    ByteBuffer output = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    output.position(offset);
    return output;
  }

  static void putString(ByteBuffer output, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    output.put(bytes);
    for (int i = bytes.length; i < 80; i++) output.put((byte) 0);
  }

  static void putVector(ByteBuffer output, Vec3 value) {
    output.putFloat((float) value.x()).putFloat((float) value.y()).putFloat((float) value.z());
  }
}

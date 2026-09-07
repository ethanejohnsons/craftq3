package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

final class MovementAbi {
  private MovementAbi() {}

  static ByteBuffer slice(ByteBuffer bytes, int offset, int size) {
    Objects.requireNonNull(bytes);
    if (offset < 0 || (long) offset + size > bytes.limit())
      throw new IllegalArgumentException("Movement record outside buffer");
    return bytes.slice(offset, size).order(ByteOrder.LITTLE_ENDIAN);
  }

  static Vec3 vector(Vec3 value) {
    Objects.requireNonNull(value);
    return new Vec3((float) value.x(), (float) value.y(), (float) value.z());
  }

  static Vec3 vector(ByteBuffer bytes, int offset) {
    return new Vec3(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
  }

  static void vector(ByteBuffer bytes, int offset, Vec3 value) {
    bytes.putFloat(offset, (float) value.x());
    bytes.putFloat(offset + 4, (float) value.y());
    bytes.putFloat(offset + 8, (float) value.z());
  }
}

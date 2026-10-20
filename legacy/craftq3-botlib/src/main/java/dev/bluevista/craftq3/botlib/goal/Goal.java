package dev.bluevista.craftq3.botlib.goal;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Owned 56-byte bot_goal_t value; Q3 coordinates and opaque game identifiers are preserved. */
public record Goal(
    Vec3 origin, int area, Vec3 mins, Vec3 maxs, int entity, int number, int flags, int itemInfo) {
  public static final int BYTE_SIZE = 56;
  public static final int ITEM = 1, ROAM = 2, DROPPED = 4;

  public Goal {
    origin = quantized(origin);
    mins = quantized(mins);
    maxs = quantized(maxs);
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException("Inverted bot goal bounds");
  }

  /** Reads without changing the caller's buffer position/order. */
  public static Goal readFrom(ByteBuffer bytes, int offset) {
    var data = slice(bytes, offset);
    return new Goal(
        vector(data, 0),
        data.getInt(12),
        vector(data, 16),
        vector(data, 28),
        data.getInt(40),
        data.getInt(44),
        data.getInt(48),
        data.getInt(52));
  }

  /** Writes without changing the caller's buffer position/order. */
  public void writeTo(ByteBuffer bytes, int offset) {
    var data = slice(bytes, offset);
    vector(data, 0, origin);
    data.putInt(12, area);
    vector(data, 16, mins);
    vector(data, 28, maxs);
    data.putInt(40, entity);
    data.putInt(44, number);
    data.putInt(48, flags);
    data.putInt(52, itemInfo);
  }

  private static ByteBuffer slice(ByteBuffer bytes, int offset) {
    Objects.requireNonNull(bytes);
    if (offset < 0 || (long) offset + BYTE_SIZE > bytes.limit())
      throw new IllegalArgumentException("Bot goal outside buffer");
    return bytes.slice(offset, BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static Vec3 quantized(Vec3 value) {
    Objects.requireNonNull(value);
    return new Vec3((float) value.x(), (float) value.y(), (float) value.z());
  }

  private static Vec3 vector(ByteBuffer bytes, int offset) {
    return new Vec3(bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8));
  }

  private static void vector(ByteBuffer bytes, int offset, Vec3 value) {
    bytes.putFloat(offset, (float) value.x());
    bytes.putFloat(offset + 4, (float) value.y());
    bytes.putFloat(offset + 8, (float) value.z());
  }
}

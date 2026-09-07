package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;

/** Immutable 68-byte bot_initmove_t input; flags and identifiers remain opaque Q3 values. */
public record MovementInit(
    Vec3 origin,
    Vec3 velocity,
    Vec3 viewOffset,
    int entity,
    int client,
    float thinkTime,
    int presenceType,
    Vec3 viewAngles,
    int moveFlags) {
  public static final int BYTE_SIZE = 68;

  public MovementInit {
    origin = MovementAbi.vector(origin);
    velocity = MovementAbi.vector(velocity);
    viewOffset = MovementAbi.vector(viewOffset);
    viewAngles = MovementAbi.vector(viewAngles);
    if (!Float.isFinite(thinkTime) || thinkTime < 0 || thinkTime > 3600)
      throw new IllegalArgumentException("Invalid movement think time");
  }

  public static MovementInit readFrom(ByteBuffer bytes, int offset) {
    var data = MovementAbi.slice(bytes, offset, BYTE_SIZE);
    return new MovementInit(
        MovementAbi.vector(data, 0),
        MovementAbi.vector(data, 12),
        MovementAbi.vector(data, 24),
        data.getInt(36),
        data.getInt(40),
        data.getFloat(44),
        data.getInt(48),
        MovementAbi.vector(data, 52),
        data.getInt(64));
  }

  public void writeTo(ByteBuffer bytes, int offset) {
    var data = MovementAbi.slice(bytes, offset, BYTE_SIZE);
    MovementAbi.vector(data, 0, origin);
    MovementAbi.vector(data, 12, velocity);
    MovementAbi.vector(data, 24, viewOffset);
    data.putInt(36, entity);
    data.putInt(40, client);
    data.putFloat(44, thinkTime);
    data.putInt(48, presenceType);
    MovementAbi.vector(data, 52, viewAngles);
    data.putInt(64, moveFlags);
  }
}

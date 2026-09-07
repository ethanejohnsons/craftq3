package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;

/** Explicit 52-byte bot_moveresult_t record for future verified movement providers. */
public record MovementResult(
    int failure,
    int type,
    int blocked,
    int blockEntity,
    int travelType,
    int flags,
    int weapon,
    Vec3 direction,
    Vec3 idealViewAngles) {
  public static final int BYTE_SIZE = 52;

  public MovementResult {
    direction = MovementAbi.vector(direction);
    idealViewAngles = MovementAbi.vector(idealViewAngles);
  }

  public static MovementResult readFrom(ByteBuffer bytes, int offset) {
    var data = MovementAbi.slice(bytes, offset, BYTE_SIZE);
    return new MovementResult(
        data.getInt(0),
        data.getInt(4),
        data.getInt(8),
        data.getInt(12),
        data.getInt(16),
        data.getInt(20),
        data.getInt(24),
        MovementAbi.vector(data, 28),
        MovementAbi.vector(data, 40));
  }

  public void writeTo(ByteBuffer bytes, int offset) {
    var data = MovementAbi.slice(bytes, offset, BYTE_SIZE);
    data.putInt(0, failure);
    data.putInt(4, type);
    data.putInt(8, blocked);
    data.putInt(12, blockEntity);
    data.putInt(16, travelType);
    data.putInt(20, flags);
    data.putInt(24, weapon);
    MovementAbi.vector(data, 28, direction);
    MovementAbi.vector(data, 40, idealViewAngles);
  }
}

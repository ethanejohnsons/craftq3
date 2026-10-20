package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * The original {@code bot_initmove_t}: the bot's origin, velocity and view, its entity and client
 * numbers, think time, presence type and input movement flags. Flags and identifiers stay opaque
 * Quake III values.
 */
public record MovementInit(
    Vec3f origin,
    Vec3f velocity,
    Vec3f viewOffset,
    int entity,
    int client,
    float thinkTime,
    int presenceType,
    Vec3f viewAngles,
    int moveFlags) {
  public MovementInit {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(velocity);
    Objects.requireNonNull(viewOffset);
    Objects.requireNonNull(viewAngles);
    if (!Float.isFinite(thinkTime) || thinkTime < 0 || thinkTime > 3600)
      throw new IllegalArgumentException("Invalid movement think time");
  }
}

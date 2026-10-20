package dev.bluevista.craftq3.engine.botlib.ea;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/** Immutable original bot_input_t value; movement is in Q3 units and view angles in degrees. */
public record BotInput(
    float thinkTime, Vec3f direction, float speed, Vec3f viewAngles, int actionFlags, int weapon) {
  public static final double MAX_DIRECTION_COMPONENT = 1_000_000;
  public static final double MAX_VIEW_COMPONENT = 1_000_000_000;

  public BotInput {
    checkThinkTime(thinkTime);
    direction = vector(direction, MAX_DIRECTION_COMPONENT);
    viewAngles = vector(viewAngles, MAX_VIEW_COMPONENT);
    if (!Float.isFinite(speed) || speed < -400 || speed > 400)
      throw new IllegalArgumentException("Bot input speed outside [-400, 400]");
  }

  public boolean hasAction(int flag) {
    return (actionFlags & flag) != 0;
  }

  /**
   * The original qagame passes absolute level time in seconds, so any finite value is valid; a
   * level may run for hours.
   */
  static void checkThinkTime(float thinkTime) {
    if (!Float.isFinite(thinkTime))
      throw new IllegalArgumentException("Bot think time is not finite");
  }

  static Vec3f vector(Vec3f vector, double maxComponent) {
    Objects.requireNonNull(vector);
    if (Math.abs(vector.x()) > maxComponent
        || Math.abs(vector.y()) > maxComponent
        || Math.abs(vector.z()) > maxComponent)
      throw new IllegalArgumentException("Bot vector component exceeds " + maxComponent);
    return vector;
  }
}

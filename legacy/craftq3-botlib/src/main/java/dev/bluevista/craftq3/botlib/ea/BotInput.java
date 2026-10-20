package dev.bluevista.craftq3.botlib.ea;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;

/** Immutable original bot_input_t value; movement is in Q3 units and view angles in degrees. */
public record BotInput(
    float thinkTime, Vec3 direction, float speed, Vec3 viewAngles, int actionFlags, int weapon) {
  public static final int BYTE_SIZE = 40;
  public static final float MAX_THINK_TIME = 3600;
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
   * Writes exactly 40 little-endian ABI bytes without changing the destination's position/order.
   */
  public void writeTo(ByteBuffer destination, int offset) {
    Objects.requireNonNull(destination);
    if (offset < 0 || (long) offset + BYTE_SIZE > destination.limit())
      throw new IndexOutOfBoundsException("bot_input_t destination range");
    if (destination.isReadOnly()) throw new ReadOnlyBufferException();
    ByteBuffer output = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    output.putFloat(offset, thinkTime);
    output.putFloat(offset + 4, (float) direction.x());
    output.putFloat(offset + 8, (float) direction.y());
    output.putFloat(offset + 12, (float) direction.z());
    output.putFloat(offset + 16, speed);
    output.putFloat(offset + 20, (float) viewAngles.x());
    output.putFloat(offset + 24, (float) viewAngles.y());
    output.putFloat(offset + 28, (float) viewAngles.z());
    output.putInt(offset + 32, actionFlags);
    output.putInt(offset + 36, weapon);
  }

  static void checkThinkTime(float thinkTime) {
    if (!Float.isFinite(thinkTime) || thinkTime < 0 || thinkTime > MAX_THINK_TIME)
      throw new IllegalArgumentException("Bot think time outside [0, 3600] seconds");
  }

  static Vec3 vector(Vec3 vector, double maxComponent) {
    Objects.requireNonNull(vector);
    if (Math.abs(vector.x()) > maxComponent
        || Math.abs(vector.y()) > maxComponent
        || Math.abs(vector.z()) > maxComponent)
      throw new IllegalArgumentException("Bot vector component exceeds " + maxComponent);
    // VM ABI values are floats. Keep that quantization without normalizing direction or angles.
    return new Vec3((float) vector.x(), (float) vector.y(), (float) vector.z());
  }
}

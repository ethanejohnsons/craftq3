package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.Optional;

/** Native-observed target-box clipping; separate from solid-world collision. */
public final class AasTargetBox {
  private final Vec3f minimum;
  private final Vec3f maximum;

  public AasTargetBox(Vec3f minimum, Vec3f maximum) {
    this.minimum = Objects.requireNonNull(minimum);
    this.maximum = Objects.requireNonNull(maximum);
    if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z())
      throw new IllegalArgumentException("Inverted AAS target box");
  }

  /**
   * Intersects the segment with the interior of an expanded box face. Exact edge/corner entries do
   * not count. An initial overlap can report an entry behind the start, with negative fraction.
   */
  public Optional<AasPresenceTrace.Result> clip(Vec3f start, Vec3f end, int presence) {
    start = Objects.requireNonNull(start);
    end = Objects.requireNonNull(end);
    if (presence != 2 && presence != 4)
      throw new IllegalArgumentException("Unsupported target-box presence");
    float[] from = {start.x(), start.y(), start.z()};
    float[] to = {end.x(), end.y(), end.z()};
    float[] low = {minimum.x() - 15, minimum.y() - 15, minimum.z() - (presence == 2 ? 32 : 8)};
    float[] high = {maximum.x() + 15, maximum.y() + 15, maximum.z() + 24};
    float[] direction = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
    if (direction[0] == 0 && direction[1] == 0 && direction[2] == 0) return Optional.empty();

    float enter = Float.NEGATIVE_INFINITY, leave = Float.POSITIVE_INFINITY;
    int entryAxis = -1;
    for (int axis = 0; axis < 3; axis++) {
      if (direction[axis] == 0) {
        if (!(low[axis] < from[axis] && from[axis] < high[axis])) return Optional.empty();
        continue;
      }
      // Round endpoint-to-plane distances independently before their subtraction.
      float fromLow = from[axis] - low[axis], toLow = to[axis] - low[axis];
      float fromHigh = from[axis] - high[axis], toHigh = to[axis] - high[axis];
      float lowFraction = fromLow / (fromLow - toLow);
      float highFraction = fromHigh / (fromHigh - toHigh);
      // Preserve geometric orientation when a tiny segment cancels in one plane distance.
      float near = direction[axis] > 0 ? lowFraction : highFraction;
      if (near > enter) {
        enter = near;
        entryAxis = axis;
      }
      leave = Math.min(leave, direction[axis] > 0 ? highFraction : lowFraction);
    }
    if (entryAxis < 0 || enter > leave || leave < 0 || enter > 1) return Optional.empty();
    float[] hit = {
      from[0] + direction[0] * enter, from[1] + direction[1] * enter, from[2] + direction[2] * enter
    };
    for (int axis = 0; axis < 3; axis++)
      if (axis != entryAxis && !(low[axis] < hit[axis] && hit[axis] < high[axis]))
        return Optional.empty();
    if (!Float.isFinite(enter))
      throw new IllegalArgumentException("Target-box arithmetic overflow");
    return Optional.of(
        new AasPresenceTrace.Result(
            false, enter, new Vec3f(hit[0], hit[1], hit[2]), 0, 0, 0, 0, 0));
  }
}

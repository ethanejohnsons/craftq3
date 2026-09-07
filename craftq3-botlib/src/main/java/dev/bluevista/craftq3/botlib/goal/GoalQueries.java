package dev.bluevista.craftq3.botlib.goal;

import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.function.IntToDoubleFunction;

/** Independent geometric goal queries; game decisions and world state remain with their owners. */
public final class GoalQueries {
  private GoalQueries() {}

  /** Closed overlap with the published normal-presence hull, including vertical player extent. */
  public static boolean touching(Vec3 origin, Goal goal) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(goal);
    return overlap(
            (float) origin.x(),
            (float) goal.origin().x(),
            (float) goal.mins().x(),
            (float) goal.maxs().x(),
            -15,
            15)
        && overlap(
            (float) origin.y(),
            (float) goal.origin().y(),
            (float) goal.mins().y(),
            (float) goal.maxs().y(),
            -15,
            15)
        && overlap(
            (float) origin.z(),
            (float) goal.origin().z(),
            (float) goal.mins().z(),
            (float) goal.maxs().z(),
            -24,
            32);
  }

  private static boolean overlap(
      float position, float origin, float min, float max, float playerMin, float playerMax) {
    return position >= origin + (min - playerMax) && position <= origin + (max - playerMin);
  }

  /**
   * Native item-presence query: clear solid-only ray to the lower goal corner and an entity update
   * older than half a second. View angles, validity flags and trace hit entity do not change it.
   */
  public static boolean visibleButMissing(
      int viewer,
      Vec3 eye,
      Vec3 viewAngles,
      Goal goal,
      float time,
      TraceWorld world,
      IntToDoubleFunction lastUpdateTime) {
    Objects.requireNonNull(eye);
    Objects.requireNonNull(viewAngles);
    Objects.requireNonNull(goal);
    Objects.requireNonNull(world);
    Objects.requireNonNull(lastUpdateTime);
    if (!Float.isFinite(time) || time < 0)
      throw new IllegalArgumentException("Invalid goal query time");
    if ((goal.flags() & Goal.ITEM) == 0) return false;
    Vec3 target =
        new Vec3(
            (float) goal.origin().x() + (float) goal.mins().x(),
            (float) goal.origin().y() + (float) goal.mins().y(),
            (float) goal.origin().z() + (float) goal.mins().z());
    if (world.trace(TraceRequest.ray(eye, target, 1).ignoring(viewer)).fraction() < 1
        || goal.entity() <= 0) return false;
    float updated = (float) lastUpdateTime.applyAsDouble(goal.entity());
    if (!Float.isFinite(updated))
      throw new IllegalArgumentException("Invalid goal entity update time");
    return updated < time - .5f;
  }
}

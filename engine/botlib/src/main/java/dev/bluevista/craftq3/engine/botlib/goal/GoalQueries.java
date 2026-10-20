package dev.bluevista.craftq3.engine.botlib.goal;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.function.IntToDoubleFunction;

/** Independent geometric goal queries; game decisions and world state remain with their owners. */
public final class GoalQueries {
  private GoalQueries() {}

  /** Closed overlap with the published normal-presence hull, including vertical player extent. */
  public static boolean touching(Vec3f origin, Goal goal) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(goal);
    return overlap(origin.x(), goal.origin().x(), goal.mins().x(), goal.maxs().x(), -15, 15)
        && overlap(origin.y(), goal.origin().y(), goal.mins().y(), goal.maxs().y(), -15, 15)
        && overlap(origin.z(), goal.origin().z(), goal.mins().z(), goal.maxs().z(), -24, 32);
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
      Vec3f eye,
      Vec3f viewAngles,
      Goal goal,
      float time,
      BotCollision world,
      IntToDoubleFunction lastUpdateTime) {
    Objects.requireNonNull(eye);
    Objects.requireNonNull(viewAngles);
    Objects.requireNonNull(goal);
    Objects.requireNonNull(world);
    Objects.requireNonNull(lastUpdateTime);
    if (!Float.isFinite(time) || time < 0)
      throw new IllegalArgumentException("Invalid goal query time");
    if ((goal.flags() & Goal.ITEM) == 0) return false;
    Vec3f target =
        new Vec3f(
            goal.origin().x() + goal.mins().x(),
            goal.origin().y() + goal.mins().y(),
            goal.origin().z() + goal.mins().z());
    if (world.trace(eye, target, viewer, Contents.SOLID).fraction() < 1 || goal.entity() <= 0)
      return false;
    float updated = (float) lastUpdateTime.applyAsDouble(goal.entity());
    if (!Float.isFinite(updated))
      throw new IllegalArgumentException("Invalid goal entity update time");
    return updated < time - .5f;
  }
}

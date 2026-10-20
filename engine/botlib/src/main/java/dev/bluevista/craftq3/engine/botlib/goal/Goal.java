package dev.bluevista.craftq3.engine.botlib.goal;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * The original {@code bot_goal_t}: origin, area, bounds, entity, number, flags and item info index.
 * Coordinates are Quake III units and identifiers are opaque game values.
 */
public record Goal(
    Vec3f origin,
    int area,
    Vec3f mins,
    Vec3f maxs,
    int entity,
    int number,
    int flags,
    int itemInfo) {
  public static final int ITEM = 1, ROAM = 2, DROPPED = 4;

  public Goal {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(mins);
    Objects.requireNonNull(maxs);
    if (mins.x() > maxs.x() || mins.y() > maxs.y() || mins.z() > maxs.z())
      throw new IllegalArgumentException("Inverted bot goal bounds");
  }

  /** A goal at {@code origin} in {@code area} with zero bounds and identifiers. */
  public static Goal at(Vec3f origin, int area) {
    return new Goal(origin, area, Vec3f.ZERO, Vec3f.ZERO, 0, 0, 0, 0);
  }
}

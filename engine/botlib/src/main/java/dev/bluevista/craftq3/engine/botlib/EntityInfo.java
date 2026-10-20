package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * What botlib knows about an entity ({@code aas_entityinfo_t}): whether it was updated this frame,
 * when it was last updated and how long before that, its last two origins, its resolved bounds and
 * the rest of the last reported state.
 */
public record EntityInfo(
    boolean valid,
    int type,
    int flags,
    float lastUpdateTime,
    float updateInterval,
    int number,
    Vec3f origin,
    Vec3f angles,
    Vec3f oldOrigin,
    Vec3f lastVisibleOrigin,
    Vec3f mins,
    Vec3f maxs,
    int groundEntity,
    int solid,
    int modelIndex,
    int modelIndex2,
    int frame,
    int event,
    int eventParm,
    int powerups,
    int weapon,
    int legsAnim,
    int torsoAnim) {
  /** The zeroed record the original writes for an entity it never saw. */
  public static final EntityInfo EMPTY =
      new EntityInfo(
          false,
          0,
          0,
          0,
          0,
          0,
          Vec3f.ZERO,
          Vec3f.ZERO,
          Vec3f.ZERO,
          Vec3f.ZERO,
          Vec3f.ZERO,
          Vec3f.ZERO,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0);

  public EntityInfo {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(angles);
    Objects.requireNonNull(oldOrigin);
    Objects.requireNonNull(lastVisibleOrigin);
    Objects.requireNonNull(mins);
    Objects.requireNonNull(maxs);
  }
}

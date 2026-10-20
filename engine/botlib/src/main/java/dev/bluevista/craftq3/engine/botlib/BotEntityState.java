package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * One entity as the game reports it to botlib each frame ({@code bot_entitystate_t}). Solid uses
 * the game's values: 0 not solid, 1 trigger, 2 bounding box ({@code mins}/{@code maxs}), 3 inline
 * BSP model ({@code modelIndex}).
 */
public record BotEntityState(
    int type,
    int flags,
    Vec3f origin,
    Vec3f angles,
    Vec3f oldOrigin,
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
  public static final int SOLID_NOT = 0;
  public static final int SOLID_TRIGGER = 1;
  public static final int SOLID_BBOX = 2;
  public static final int SOLID_BSP = 3;

  public BotEntityState {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(angles);
    Objects.requireNonNull(oldOrigin);
    Objects.requireNonNull(mins);
    Objects.requireNonNull(maxs);
  }

  /** A solid-box entity at {@code origin} with otherwise zero fields, for hosts and tests. */
  public static BotEntityState box(int type, Vec3f origin, Vec3f mins, Vec3f maxs) {
    return new BotEntityState(
        type, 0, origin, Vec3f.ZERO, origin, mins, maxs, 0, SOLID_BBOX, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  public BotEntityState withAngles(Vec3f value) {
    return new BotEntityState(
        type,
        flags,
        origin,
        value,
        oldOrigin,
        mins,
        maxs,
        groundEntity,
        solid,
        modelIndex,
        modelIndex2,
        frame,
        event,
        eventParm,
        powerups,
        weapon,
        legsAnim,
        torsoAnim);
  }

  public BotEntityState withBounds(Vec3f newMins, Vec3f newMaxs) {
    return new BotEntityState(
        type,
        flags,
        origin,
        angles,
        oldOrigin,
        newMins,
        newMaxs,
        groundEntity,
        solid,
        modelIndex,
        modelIndex2,
        frame,
        event,
        eventParm,
        powerups,
        weapon,
        legsAnim,
        torsoAnim);
  }
}

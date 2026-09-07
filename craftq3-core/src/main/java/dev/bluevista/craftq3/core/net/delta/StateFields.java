package dev.bluevista.craftq3.core.net.delta;

import java.util.List;
import java.util.Objects;

/** Immutable protocol metadata: canonical byte offsets and ordered wire widths, zero for floats. */
public final class StateFields {
  private StateFields() {}

  public record Field(String name, int byteOffset, int bitWidth) {
    public Field {
      Objects.requireNonNull(name);
      if (byteOffset < 0 || (byteOffset & 3) != 0 || bitWidth < -31 || bitWidth > 32)
        throw new IllegalArgumentException("Invalid protocol field metadata");
    }
  }

  public static final List<Field> ENTITY =
      List.of(
          new Field("pos.trTime", 16, 32),
          new Field("pos.trBase[0]", 24, 0),
          new Field("pos.trBase[1]", 28, 0),
          new Field("pos.trDelta[0]", 36, 0),
          new Field("pos.trDelta[1]", 40, 0),
          new Field("pos.trBase[2]", 32, 0),
          new Field("apos.trBase[1]", 64, 0),
          new Field("pos.trDelta[2]", 44, 0),
          new Field("apos.trBase[0]", 60, 0),
          new Field("event", 180, 10),
          new Field("angles2[1]", 132, 0),
          new Field("eType", 4, 8),
          new Field("torsoAnim", 200, 8),
          new Field("eventParm", 184, 8),
          new Field("legsAnim", 196, 8),
          new Field("groundEntityNum", 148, 10),
          new Field("pos.trType", 12, 8),
          new Field("eFlags", 8, 19),
          new Field("otherEntityNum", 140, 10),
          new Field("weapon", 192, 8),
          new Field("clientNum", 168, 8),
          new Field("angles[1]", 120, 0),
          new Field("pos.trDuration", 20, 32),
          new Field("apos.trType", 48, 8),
          new Field("origin[0]", 92, 0),
          new Field("origin[1]", 96, 0),
          new Field("origin[2]", 100, 0),
          new Field("solid", 176, 24),
          new Field("powerups", 188, 16),
          new Field("modelindex", 160, 8),
          new Field("otherEntityNum2", 144, 10),
          new Field("loopSound", 156, 8),
          new Field("generic1", 204, 8),
          new Field("origin2[2]", 112, 0),
          new Field("origin2[0]", 104, 0),
          new Field("origin2[1]", 108, 0),
          new Field("modelindex2", 164, 8),
          new Field("angles[0]", 116, 0),
          new Field("time", 84, 32),
          new Field("apos.trTime", 52, 32),
          new Field("apos.trDuration", 56, 32),
          new Field("apos.trBase[2]", 68, 0),
          new Field("apos.trDelta[0]", 72, 0),
          new Field("apos.trDelta[1]", 76, 0),
          new Field("apos.trDelta[2]", 80, 0),
          new Field("time2", 88, 32),
          new Field("angles[2]", 124, 0),
          new Field("angles2[0]", 128, 0),
          new Field("angles2[2]", 136, 0),
          new Field("constantLight", 152, 32),
          new Field("frame", 172, 16));

  public static final List<Field> PLAYER =
      List.of(
          new Field("commandTime", 0, 32),
          new Field("origin[0]", 20, 0),
          new Field("origin[1]", 24, 0),
          new Field("bobCycle", 8, 8),
          new Field("velocity[0]", 32, 0),
          new Field("velocity[1]", 36, 0),
          new Field("viewangles[1]", 156, 0),
          new Field("viewangles[0]", 152, 0),
          new Field("weaponTime", 44, -16),
          new Field("origin[2]", 28, 0),
          new Field("velocity[2]", 40, 0),
          new Field("legsTimer", 72, 8),
          new Field("pm_time", 16, -16),
          new Field("eventSequence", 108, 16),
          new Field("torsoAnim", 84, 8),
          new Field("movementDir", 88, 4),
          new Field("events[0]", 112, 8),
          new Field("legsAnim", 76, 8),
          new Field("events[1]", 116, 8),
          new Field("pm_flags", 12, 16),
          new Field("groundEntityNum", 68, 10),
          new Field("weaponstate", 148, 4),
          new Field("eFlags", 104, 16),
          new Field("externalEvent", 128, 10),
          new Field("gravity", 48, 16),
          new Field("speed", 52, 16),
          new Field("delta_angles[1]", 60, 16),
          new Field("externalEventParm", 132, 8),
          new Field("viewheight", 164, -8),
          new Field("damageEvent", 168, 8),
          new Field("damageYaw", 172, 8),
          new Field("damagePitch", 176, 8),
          new Field("damageCount", 180, 8),
          new Field("generic1", 440, 8),
          new Field("pm_type", 4, 8),
          new Field("delta_angles[0]", 56, 16),
          new Field("delta_angles[2]", 64, 16),
          new Field("torsoTimer", 80, 12),
          new Field("eventParms[0]", 120, 8),
          new Field("eventParms[1]", 124, 8),
          new Field("clientNum", 140, 8),
          new Field("weapon", 144, 5),
          new Field("viewangles[2]", 160, 0),
          new Field("grapplePoint[0]", 92, 0),
          new Field("grapplePoint[1]", 96, 0),
          new Field("grapplePoint[2]", 100, 0),
          new Field("jumppad_ent", 448, 10),
          new Field("loopSound", 444, 16));
}

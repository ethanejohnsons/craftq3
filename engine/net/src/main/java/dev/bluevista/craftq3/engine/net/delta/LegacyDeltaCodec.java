package dev.bluevista.craftq3.engine.net.delta;

import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.EntityDeltaCodec.EntityUpdate;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.delta.StateFields.Field;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * State deltas of the pre-1.29 protocols (43: the 1999 releases, {@code .dm3}; 46 and 48: 1.25 and
 * 1.27, {@code .dm_48}), decoded onto the canonical 1.32 entity and player images so that they feed
 * the same snapshot structures as protocol 68. The layouts were measured on the retail demos;
 * {@code DemoProtocol.Q3_1_09} and {@code DemoProtocol.Q3_1_27} record how.
 *
 * <p>An entity body is a remove bit, a change bit and, when changed, a 5-bit mask index followed by
 * the values of the fields the mask selects, without per-field bits. Index 31 is followed by an
 * explicit mask with one bit per field; the other indexes name masks of a fixed table that the
 * stream does not carry. The table is the same in protocols 43 and 48 (every entry the 1.27 demos
 * use decodes the 1.09 demos too) and all 31 entries are known. A player-state delta is one change
 * bit per field, a few trailing fields that depend on the {@link Variant}, then the four arrays.
 */
public final class LegacyDeltaCodec {
  private LegacyDeltaCodec() {}

  /** Layouts: 1999 servers announce protocol 43, 1.25 servers 46 and 1.27 servers 48. */
  public enum Variant {
    /**
     * 50 entity fields (no {@code generic1}) with a 16-bit {@code eFlags}; an 8-bit player {@code
     * externalEvent} and three trailing player fields, none of them identified.
     */
    PROTOCOL_43(3),
    /** Five trailing player fields after {@code damageCount}, none of them identified. */
    PROTOCOL_46(5),
    /** Six trailing player fields; the fourth is {@code jumppad_ent}. */
    PROTOCOL_48(6);

    private final int trailingFields;

    Variant(int trailingFields) {
      this.trailingFields = trailingFields;
    }

    /** Number of change bits after {@code damageCount}. */
    public int trailingFields() {
      return trailingFields;
    }

    /** The 46/48 layout for a server info {@code protocol} value: 46 is 1.25, otherwise 1.27. */
    public static Variant forAnnouncedProtocol(int protocol) {
      return protocol == 46 ? PROTOCOL_46 : PROTOCOL_48;
    }

    StateFields.Table entity() {
      return this == PROTOCOL_43 ? ENTITY_43 : ENTITY;
    }

    StateFields.Table player() {
      return this == PROTOCOL_43 ? PLAYER_43 : PLAYER;
    }
  }

  /** Mask index followed by an explicit field mask. */
  static final int EXPLICIT_MASK = 31;

  static final int MASK_INDEX_BITS = 5;

  /**
   * Entity fields in wire order ({@code entityState_t} declaration order with {@code frame} before
   * {@code clientNum}) with their canonical offsets. The 8-bit field between {@code
   * groundEntityNum} and {@code loopSound} is stored as {@code constantLight}; the demos only set
   * it to 1 on dead players that touch the ground, and its meaning is unresolved.
   */
  static final List<Field> ENTITY_FIELDS =
      List.of(
          new Field("eType", 4, 8),
          new Field("eFlags", 8, 19),
          new Field("pos.trType", 12, 8),
          new Field("pos.trTime", 16, 32),
          new Field("pos.trDuration", 20, 32),
          new Field("pos.trBase[0]", 24, 0),
          new Field("pos.trBase[1]", 28, 0),
          new Field("pos.trBase[2]", 32, 0),
          new Field("pos.trDelta[0]", 36, 0),
          new Field("pos.trDelta[1]", 40, 0),
          new Field("pos.trDelta[2]", 44, 0),
          new Field("apos.trType", 48, 8),
          new Field("apos.trTime", 52, 32),
          new Field("apos.trDuration", 56, 32),
          new Field("apos.trBase[0]", 60, 0),
          new Field("apos.trBase[1]", 64, 0),
          new Field("apos.trBase[2]", 68, 0),
          new Field("apos.trDelta[0]", 72, 0),
          new Field("apos.trDelta[1]", 76, 0),
          new Field("apos.trDelta[2]", 80, 0),
          new Field("time", 84, 32),
          new Field("time2", 88, 32),
          new Field("origin[0]", 92, 0),
          new Field("origin[1]", 96, 0),
          new Field("origin[2]", 100, 0),
          new Field("origin2[0]", 104, 0),
          new Field("origin2[1]", 108, 0),
          new Field("origin2[2]", 112, 0),
          new Field("angles[0]", 116, 0),
          new Field("angles[1]", 120, 0),
          new Field("angles[2]", 124, 0),
          new Field("angles2[0]", 128, 0),
          new Field("angles2[1]", 132, 0),
          new Field("angles2[2]", 136, 0),
          new Field("otherEntityNum", 140, 10),
          new Field("otherEntityNum2", 144, 10),
          new Field("groundEntityNum", 148, 10),
          new Field("constantLight", 152, 8),
          new Field("loopSound", 156, 8),
          new Field("modelindex", 160, 8),
          new Field("modelindex2", 164, 8),
          new Field("frame", 172, 16),
          new Field("clientNum", 168, 8),
          new Field("solid", 176, 24),
          new Field("event", 180, 10),
          new Field("eventParm", 184, 8),
          new Field("powerups", 188, 16),
          new Field("weapon", 192, 8),
          new Field("legsAnim", 196, 8),
          new Field("torsoAnim", 200, 8),
          new Field("generic1", 204, 8));

  static final StateFields.Table ENTITY =
      new StateFields.Table(ENTITY_FIELDS, Protocol68.ENTITY_STATE_BYTES);

  /** Protocol 43: the same fields without {@code generic1}, with a 16-bit {@code eFlags}. */
  static final StateFields.Table ENTITY_43 =
      new StateFields.Table(
          java.util.stream.IntStream.range(0, ENTITY_FIELDS.size() - 1)
              .mapToObj(
                  i ->
                      i == LegacyDeltaCodec.E_FLAGS
                          ? new Field("eFlags", ENTITY_FIELDS.get(i).byteOffset(), 16)
                          : ENTITY_FIELDS.get(i))
              .toList(),
          Protocol68.ENTITY_STATE_BYTES);

  private static final int E_TYPE = 0,
      E_FLAGS = 1,
      TR_TYPE = 2,
      TR_TIME = 3,
      TR_BASE_X = 5,
      TR_BASE_Y = 6,
      TR_BASE_Z = 7,
      TR_DELTA_X = 8,
      TR_DELTA_Y = 9,
      TR_DELTA_Z = 10,
      APOS_PITCH = 14,
      APOS_YAW = 15,
      ORIGIN_X = 22,
      ORIGIN_Y = 23,
      ORIGIN_Z = 24,
      MOVEMENT_DIR = 32,
      OTHER_ENTITY = 34,
      LOOP_SOUND = 38,
      EVENT = 44,
      EVENT_PARM = 45,
      WEAPON = 47,
      LEGS_ANIM = 48,
      TORSO_ANIM = 49;

  /**
   * The mask table, by index. Each entry was resolved by the exact bit length its occurrences must
   * have for the rest of their messages to parse to the end-of-message padding, then by the
   * continuity of the states it produces with the same entity's neighbouring updates (positions,
   * view angles, animations). Entries 3, 7, 11, 13, 14, 19 and 20 occur in the 1.27 demos and the
   * 1.09 demos alike; the others (the player movement masks) only in the 1.09 demos, whose players
   * are interpolated rather than extrapolated.
   */
  static final int[][] MASKS = new int[EXPLICIT_MASK][];

  static {
    int x = TR_BASE_X, y = TR_BASE_Y, z = TR_BASE_Z;
    MASKS[0] = new int[] {x, y, APOS_YAW};
    MASKS[1] = new int[] {x, y};
    MASKS[2] = new int[] {x, y, APOS_PITCH, APOS_YAW};
    // A bullet impact without a shooter.
    MASKS[3] = new int[] {E_TYPE, x, y, z, EVENT_PARM};
    MASKS[4] = new int[] {x, y, APOS_YAW, EVENT};
    MASKS[5] = new int[] {x, y, z, APOS_YAW};
    MASKS[6] = new int[] {x, y, z, APOS_PITCH, APOS_YAW};
    // A new event on an existing entity.
    MASKS[7] = new int[] {EVENT};
    MASKS[8] = new int[] {y, APOS_YAW};
    MASKS[9] = new int[] {x, APOS_YAW};
    MASKS[10] = new int[] {x, y, APOS_YAW, MOVEMENT_DIR};
    // A new missile.
    MASKS[11] =
        new int[] {E_TYPE, TR_TYPE, TR_TIME, x, y, z, TR_DELTA_X, TR_DELTA_Y, TR_DELTA_Z, WEAPON};
    MASKS[12] = new int[] {x, y, z};
    // A missile exploding: it becomes a stationary general entity carrying the impact event.
    MASKS[13] =
        new int[] {
          E_TYPE, TR_TYPE, TR_TIME, x, y, z, TR_DELTA_X, TR_DELTA_Y, TR_DELTA_Z, EVENT, EVENT_PARM
        };
    // A corpse settling or sinking.
    MASKS[14] = new int[] {z};
    MASKS[15] = new int[] {y};
    MASKS[16] = new int[] {x, y, z, APOS_PITCH, APOS_YAW, EVENT};
    MASKS[17] = new int[] {x, y, EVENT};
    MASKS[18] = new int[] {x};
    // A bullet impact.
    MASKS[19] = new int[] {E_TYPE, x, y, z, OTHER_ENTITY, EVENT_PARM};
    // A looped speaker.
    MASKS[20] = new int[] {E_TYPE, x, y, z, ORIGIN_X, ORIGIN_Y, ORIGIN_Z, LOOP_SOUND, EVENT_PARM};
    MASKS[21] = new int[] {x, y, z, APOS_PITCH, APOS_YAW, MOVEMENT_DIR};
    MASKS[22] = new int[] {x, y, APOS_PITCH};
    MASKS[23] = new int[] {y, APOS_PITCH, APOS_YAW};
    MASKS[24] = new int[] {x, y, APOS_PITCH, APOS_YAW, MOVEMENT_DIR};
    MASKS[25] = new int[] {x, y, APOS_PITCH, APOS_YAW, EVENT};
    MASKS[26] = new int[] {x, y, APOS_YAW, MOVEMENT_DIR, LEGS_ANIM};
    MASKS[27] = new int[] {x, y, APOS_YAW, EVENT, EVENT_PARM};
    MASKS[28] = new int[] {x, y, z, APOS_YAW, EVENT};
    MASKS[29] = new int[] {x, APOS_PITCH, APOS_YAW};
    MASKS[30] = new int[] {x, y, APOS_YAW, TORSO_ANIM};
  }

  /**
   * Player fields in wire order with their canonical offsets: {@code playerState_t} declaration
   * order with 1.32 widths, the animation timers before the animations, no {@code grapplePoint},
   * ending at {@code damageCount}.
   */
  static final StateFields.Table PLAYER =
      new StateFields.Table(
          List.of(
              new Field("commandTime", 0, 32),
              new Field("pm_type", 4, 8),
              new Field("bobCycle", 8, 8),
              new Field("pm_flags", 12, 16),
              new Field("pm_time", 16, -16),
              new Field("origin[0]", 20, 0),
              new Field("origin[1]", 24, 0),
              new Field("origin[2]", 28, 0),
              new Field("velocity[0]", 32, 0),
              new Field("velocity[1]", 36, 0),
              new Field("velocity[2]", 40, 0),
              new Field("weaponTime", 44, -16),
              new Field("gravity", 48, 16),
              new Field("speed", 52, 16),
              new Field("delta_angles[0]", 56, 16),
              new Field("delta_angles[1]", 60, 16),
              new Field("delta_angles[2]", 64, 16),
              new Field("groundEntityNum", 68, 10),
              new Field("legsTimer", 72, 8),
              new Field("torsoTimer", 80, 12),
              new Field("legsAnim", 76, 8),
              new Field("torsoAnim", 84, 8),
              new Field("movementDir", 88, 4),
              new Field("eFlags", 104, 16),
              new Field("eventSequence", 108, 16),
              new Field("events[0]", 112, 8),
              new Field("events[1]", 116, 8),
              new Field("eventParms[0]", 120, 8),
              new Field("eventParms[1]", 124, 8),
              new Field("externalEvent", 128, 10),
              new Field("externalEventParm", 132, 8),
              new Field("clientNum", 140, 8),
              new Field("weapon", 144, 5),
              new Field("weaponstate", 148, 4),
              new Field("viewangles[0]", 152, 0),
              new Field("viewangles[1]", 156, 0),
              new Field("viewangles[2]", 160, 0),
              new Field("viewheight", 164, -8),
              new Field("damageEvent", 168, 8),
              new Field("damageYaw", 172, 8),
              new Field("damagePitch", 176, 8),
              new Field("damageCount", 180, 8)),
          Protocol68.PLAYER_STATE_BYTES);

  private static final int EXTERNAL_EVENT = 29;

  /** Protocol 43: {@code externalEvent} is 8 bits wide. */
  static final StateFields.Table PLAYER_43 = playerWithExternalEventBits(8);

  private static StateFields.Table playerWithExternalEventBits(int width) {
    var fields = new java.util.ArrayList<Field>();
    for (int i = 0; i < PLAYER.size(); i++)
      fields.add(
          new Field(
              "player[" + i + "]",
              PLAYER.offset(i),
              i == EXTERNAL_EVENT ? width : PLAYER.width(i)));
    return new StateFields.Table(fields, Protocol68.PLAYER_STATE_BYTES);
  }

  /** Index of {@code jumppad_ent} among the protocol-48 trailing player fields. */
  static final int TRAILING_JUMPPAD = 3;

  static final Field JUMPPAD_ENT = new Field("jumppad_ent", 448, 10);

  /** Wire order of the player arrays: stats, persistant, ammo, powerups. */
  private static final int[] ARRAYS = {
    PlayerDeltaCodec.STATS_OFFSET,
    PlayerDeltaCodec.PERSISTANT_OFFSET,
    PlayerDeltaCodec.AMMO_OFFSET,
    PlayerDeltaCodec.POWERUPS_OFFSET
  };

  private static final int[] ARRAY_WIDTHS = {-16, -16, -16, 32};

  private static SnapshotDeltaCodec.StateReader states(Variant variant) {
    Objects.requireNonNull(variant);
    return new SnapshotDeltaCodec.StateReader() {
      @Override
      public void player(MessageReader reader, byte[] state) {
        readPlayerInPlace(reader, state, variant);
      }

      @Override
      public boolean entity(MessageReader reader, byte[] state, int number) {
        return readEntityInPlace(reader, state, number, variant);
      }
    };
  }

  /**
   * Reads a snapshot body (after its opcode). Delta updates require the exact referenced previous
   * snapshot, as for protocol 68.
   */
  public static Snapshot readSnapshot(
      MessageReader reader,
      int sequence,
      Baselines baselines,
      IntFunction<Snapshot> history,
      Variant variant) {
    return SnapshotDeltaCodec.readBody(reader, sequence, baselines, history, false, states(variant))
        .orElseThrow();
  }

  /** Consumes a missing-base update without publishing its reconstructed state. */
  public static Optional<Snapshot> readSnapshotAvailable(
      MessageReader reader,
      int sequence,
      Baselines baselines,
      IntFunction<Snapshot> history,
      Variant variant) {
    return SnapshotDeltaCodec.readBody(reader, sequence, baselines, history, true, states(variant));
  }

  /** Reads a gamestate baseline: an entity number and a body applied to the zero state. */
  public static EntityUpdate readBaseline(MessageReader reader, Variant variant) {
    Objects.requireNonNull(variant);
    int checkpoint = reader.mark();
    try {
      int number = reader.bits(Protocol68.GENTITYNUM_BITS);
      if (number == Protocol68.ENTITYNUM_NONE)
        throw new NetFormatException("Legacy baseline for entity 1023");
      byte[] state = new byte[Protocol68.ENTITY_STATE_BYTES];
      if (!readEntityInPlace(reader, state, number, variant))
        throw new NetFormatException("Legacy baseline " + number + " is a removal");
      return new EntityUpdate(number, false, state);
    } catch (RuntimeException failure) {
      reader.rewind(checkpoint);
      throw failure;
    }
  }

  /** Applies a player-state delta to {@code state} in place; the caller handles rollback. */
  static void readPlayerInPlace(MessageReader reader, byte[] state, Variant variant) {
    var fields = variant.player();
    for (int i = 0; i < fields.size(); i++)
      if (reader.bits(1) != 0) StateBytes.word(state, fields.offset(i), value(reader, fields, i));
    for (int i = 0; i < variant.trailingFields; i++) {
      if (reader.bits(1) == 0) continue;
      if (variant != Variant.PROTOCOL_48 || i != TRAILING_JUMPPAD)
        throw new NetFormatException(
            "Protocol-"
                + variant.name().substring(variant.name().indexOf('_') + 1)
                + " player field "
                + (fields.size() + i)
                + " after damageCount is set; its width is unknown");
      StateBytes.word(state, JUMPPAD_ENT.byteOffset(), reader.field(JUMPPAD_ENT.bitWidth()));
    }
    for (int a = 0; a < ARRAYS.length; a++) {
      if (reader.bits(1) == 0) continue;
      int mask = reader.bits(Protocol68.PLAYER_ARRAY_LENGTH);
      for (int i = 0; i < Protocol68.PLAYER_ARRAY_LENGTH; i++)
        if ((mask & (1 << i)) != 0)
          StateBytes.word(state, ARRAYS[a] + i * 4, reader.field(ARRAY_WIDTHS[a]));
    }
  }

  /**
   * Applies one entity body in place; {@code state} holds the baseline on entry. Returns false when
   * the entity is removed (the state is then unspecified).
   */
  static boolean readEntityInPlace(
      MessageReader reader, byte[] state, int number, Variant variant) {
    var fields = variant.entity();
    if (reader.bits(1) != 0) return false;
    if (reader.bits(1) != 0) {
      int index = reader.bits(MASK_INDEX_BITS);
      if (index == EXPLICIT_MASK) {
        long mask = reader.bits(32) & 0xffffffffL;
        mask |= (long) reader.bits(fields.size() - 32) << 32;
        for (int i = 0; i < fields.size(); i++)
          if ((mask & (1L << i)) != 0)
            StateBytes.word(state, fields.offset(i), value(reader, fields, i));
      } else {
        for (int field : MASKS[index])
          StateBytes.word(state, fields.offset(field), value(reader, fields, field));
      }
    }
    StateBytes.word(state, 0, number);
    return true;
  }

  private static int value(MessageReader reader, StateFields.Table fields, int index) {
    int width = fields.width(index);
    if (width != 0) return reader.field(width);
    if (reader.bits(1) != 0) return reader.intValue();
    return Float.floatToRawIntBits(
        (float) (reader.bits(DeltaFields.FLOAT_INT_BITS) - DeltaFields.FLOAT_INT_BIAS));
  }
}

package dev.bluevista.craftq3.engine.net.delta;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;

/** Protocol-68 canonical player deltas. Non-networked words remain those of the baseline. */
public final class PlayerDeltaCodec {
  public static final int STATS_OFFSET = 184,
      PERSISTANT_OFFSET = 248,
      POWERUPS_OFFSET = 312,
      AMMO_OFFSET = 376;

  /** Canonical offset of {@code clientNum}. */
  public static final int CLIENT_NUM_OFFSET = 140;

  /** Wire order of the four arrays: stats, persistant, ammo, powerups. */
  private static final int[] ARRAYS = {
    STATS_OFFSET, PERSISTANT_OFFSET, AMMO_OFFSET, POWERUPS_OFFSET
  };

  private static final int[] WIDTHS = {-16, -16, -16, 32};

  private PlayerDeltaCodec() {}

  public static void write(MessageWriter writer, byte[] from, byte[] to) {
    if (from != null) StateBytes.check(from, Protocol68.PLAYER_STATE_BYTES);
    StateBytes.check(to, Protocol68.PLAYER_STATE_BYTES);
    byte[] baseline = from == null ? new byte[Protocol68.PLAYER_STATE_BYTES] : from;
    int checkpoint = writer.mark();
    try {
      DeltaFields.write(
          writer,
          baseline,
          to,
          StateFields.PLAYER_TABLE,
          DeltaFields.extent(baseline, to, StateFields.PLAYER_TABLE),
          false);
      boolean arraysChanged = false;
      for (int a = 0; a < ARRAYS.length; a++) arraysChanged |= mask(baseline, to, a) != 0;
      writer.bits(arraysChanged ? 1 : 0, 1);
      if (arraysChanged) {
        for (int a = 0; a < ARRAYS.length; a++) {
          int mask = mask(baseline, to, a);
          writer.bits(mask == 0 ? 0 : 1, 1);
          if (mask == 0) continue;
          writer.bits(mask, Protocol68.PLAYER_ARRAY_LENGTH);
          for (int i = 0; i < Protocol68.PLAYER_ARRAY_LENGTH; i++) {
            if ((mask & (1 << i)) != 0)
              writer.field(StateBytes.word(to, ARRAYS[a] + i * 4), WIDTHS[a]);
          }
        }
      }
    } catch (RuntimeException failure) {
      writer.rollback(checkpoint);
      throw failure;
    }
  }

  /** Returns an owned complete state and rolls the reader back on any truncated/malformed delta. */
  public static byte[] read(MessageReader reader, byte[] from) {
    byte[] result = StateBytes.baseline(from, Protocol68.PLAYER_STATE_BYTES);
    int checkpoint = reader.mark();
    try {
      readInPlace(reader, result, StateFields.PLAYER_TABLE);
      return result;
    } catch (RuntimeException failure) {
      reader.rewind(checkpoint);
      throw failure;
    }
  }

  /** Applies a delta to {@code state} in place; the caller handles rollback. */
  static void readInPlace(MessageReader reader, byte[] state, StateFields.Table fields) {
    DeltaFields.read(reader, state, fields, false);
    if (reader.bits(1) != 0) {
      for (int a = 0; a < ARRAYS.length; a++) {
        if (reader.bits(1) == 0) continue;
        int mask = reader.bits(Protocol68.PLAYER_ARRAY_LENGTH);
        for (int i = 0; i < Protocol68.PLAYER_ARRAY_LENGTH; i++) {
          if ((mask & (1 << i)) != 0)
            StateBytes.word(state, ARRAYS[a] + i * 4, reader.field(WIDTHS[a]));
        }
      }
    }
  }

  private static int mask(byte[] from, byte[] to, int array) {
    int mask = 0;
    for (int i = 0; i < Protocol68.PLAYER_ARRAY_LENGTH; i++) {
      int offset = ARRAYS[array] + i * 4;
      if (StateBytes.word(from, offset) != StateBytes.word(to, offset)) mask |= 1 << i;
    }
    return mask;
  }
}

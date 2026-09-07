package dev.bluevista.craftq3.core.net.delta;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;

/** Protocol-68 canonical player deltas. Non-networked words remain those of the baseline. */
public final class PlayerDeltaCodec {
  public static final int STATE_BYTES = 468;
  public static final int STATS_OFFSET = 184,
      PERSISTANT_OFFSET = 248,
      POWERUPS_OFFSET = 312,
      AMMO_OFFSET = 376;
  public static final int ARRAY_LENGTH = 16;
  private static final int[] ARRAYS = {
    STATS_OFFSET, PERSISTANT_OFFSET, AMMO_OFFSET, POWERUPS_OFFSET
  };
  private static final int[] WIDTHS = {-16, -16, -16, 32};

  private PlayerDeltaCodec() {}

  public static void write(MessageWriter writer, byte[] from, byte[] to) {
    byte[] baseline = StateBytes.baseline(from, STATE_BYTES),
        target = StateBytes.copy(to, STATE_BYTES);
    int[] masks = new int[ARRAYS.length];
    for (int a = 0; a < ARRAYS.length; a++) {
      for (int i = 0; i < ARRAY_LENGTH; i++) {
        int offset = ARRAYS[a] + i * 4;
        if (StateBytes.word(baseline, offset) != StateBytes.word(target, offset))
          masks[a] |= 1 << i;
      }
    }
    writer.transaction(
        output -> {
          DeltaFields.write(
              output,
              baseline,
              target,
              StateFields.PLAYER,
              DeltaFields.extent(baseline, target, StateFields.PLAYER),
              false);
          boolean arraysChanged = false;
          for (int mask : masks) arraysChanged |= mask != 0;
          output.bits(arraysChanged ? 1 : 0, 1);
          if (arraysChanged) {
            for (int a = 0; a < ARRAYS.length; a++) {
              output.bits(masks[a] == 0 ? 0 : 1, 1);
              if (masks[a] == 0) continue;
              output.bits(masks[a], ARRAY_LENGTH);
              for (int i = 0; i < ARRAY_LENGTH; i++) {
                if ((masks[a] & (1 << i)) != 0)
                  output.field(StateBytes.word(target, ARRAYS[a] + i * 4), WIDTHS[a]);
              }
            }
          }
          return null;
        });
  }

  /** Returns an owned complete state and rolls the reader back on any truncated/malformed delta. */
  public static byte[] read(MessageReader reader, byte[] from) {
    byte[] result = StateBytes.baseline(from, STATE_BYTES);
    return reader.transaction(
        input -> {
          DeltaFields.read(input, result, StateFields.PLAYER, false);
          if (input.bits(1) != 0) {
            for (int a = 0; a < ARRAYS.length; a++) {
              if (input.bits(1) == 0) continue;
              int mask = input.bits(ARRAY_LENGTH);
              for (int i = 0; i < ARRAY_LENGTH; i++) {
                if ((mask & (1 << i)) != 0)
                  StateBytes.word(result, ARRAYS[a] + i * 4, input.field(WIDTHS[a]));
              }
            }
          }
          return result;
        });
  }
}

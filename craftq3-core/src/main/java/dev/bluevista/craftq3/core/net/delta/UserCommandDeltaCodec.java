package dev.bluevista.craftq3.core.net.delta;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;

/** Keyed protocol-68 deltas over the published 24-byte canonical user-command layout. */
public final class UserCommandDeltaCodec {
  public static final int STATE_BYTES = 24;
  private static final int[] OFFSETS = {4, 8, 12, 21, 22, 23, 16, 20};
  private static final int[] WIDTHS = {16, 16, 16, 8, 8, 8, 16, 8};

  private UserCommandDeltaCodec() {}

  public static void write(MessageWriter writer, int key, byte[] from, byte[] to) {
    byte[] baseline = StateBytes.baseline(from, STATE_BYTES),
        target = StateBytes.copy(to, STATE_BYTES);
    int time = StateBytes.word(target, 0), delta = time - StateBytes.word(baseline, 0);
    boolean changed = false;
    for (int offset : OFFSETS) changed |= value(baseline, offset) != value(target, offset);
    final boolean fieldsChanged = changed;
    writer.transaction(
        output -> {
          boolean shortTime = delta < 256;
          output.bits(shortTime ? 1 : 0, 1);
          output.bits(shortTime ? delta : time, shortTime ? 8 : 32);
          output.bits(fieldsChanged ? 1 : 0, 1);
          if (fieldsChanged) {
            for (int i = 0; i < OFFSETS.length; i++) {
              int before = value(baseline, OFFSETS[i]), after = value(target, OFFSETS[i]);
              output.bits(before == after ? 0 : 1, 1);
              if (before != after) output.bits(after ^ key ^ time, WIDTHS[i]);
            }
          }
          return null;
        });
  }

  /** Returns an owned state; malformed/truncated input restores the reader cursor. */
  public static byte[] read(MessageReader reader, int key, byte[] from) {
    byte[] result = StateBytes.baseline(from, STATE_BYTES);
    return reader.transaction(
        input -> {
          int time =
              input.bits(1) != 0
                  ? StateBytes.word(result, 0) + input.byteValue()
                  : input.intValue();
          StateBytes.word(result, 0, time);
          if (input.bits(1) != 0) {
            for (int i = 0; i < OFFSETS.length; i++) {
              if (input.bits(1) == 0) continue;
              int decoded = (input.bits(WIDTHS[i]) ^ key ^ time) & ((1 << WIDTHS[i]) - 1);
              if (OFFSETS[i] >= 20) result[OFFSETS[i]] = (byte) decoded;
              else StateBytes.word(result, OFFSETS[i], decoded);
            }
            for (int offset = 21; offset < 24; offset++)
              if (result[offset] == Byte.MIN_VALUE) result[offset] = -127;
          }
          return result;
        });
  }

  private static int value(byte[] state, int offset) {
    return offset >= 20 ? Byte.toUnsignedInt(state[offset]) : StateBytes.word(state, offset);
  }
}

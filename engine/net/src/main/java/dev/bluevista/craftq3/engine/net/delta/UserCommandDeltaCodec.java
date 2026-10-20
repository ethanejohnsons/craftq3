package dev.bluevista.craftq3.engine.net.delta;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;

/** Keyed protocol-68 deltas over the published 24-byte canonical user-command layout. */
public final class UserCommandDeltaCodec {
  /** Wire order: angles[0..2], forwardmove, rightmove, upmove, buttons, weapon. */
  private static final int[] OFFSETS = {4, 8, 12, 21, 22, 23, 16, 20};

  private static final int[] WIDTHS = {16, 16, 16, 8, 8, 8, 16, 8};

  /** The first byte-sized field offset; fields at or after it are single bytes. */
  private static final int BYTE_FIELDS = 20;

  /** Movement bytes (forward, right, up) occupy offsets 21 to 23. */
  private static final int MOVE_FIRST = 21;

  /** A time delta below this is sent in 8 bits. */
  private static final int SHORT_TIME_LIMIT = 256;

  private UserCommandDeltaCodec() {}

  public static void write(MessageWriter writer, int key, byte[] from, byte[] to) {
    if (from != null) StateBytes.check(from, Protocol68.USERCMD_BYTES);
    StateBytes.check(to, Protocol68.USERCMD_BYTES);
    byte[] baseline = from == null ? new byte[Protocol68.USERCMD_BYTES] : from;
    int time = StateBytes.word(to, 0), delta = time - StateBytes.word(baseline, 0);
    boolean changed = false;
    for (int offset : OFFSETS) changed |= value(baseline, offset) != value(to, offset);
    int checkpoint = writer.mark();
    try {
      boolean shortTime = delta < SHORT_TIME_LIMIT;
      writer.bits(shortTime ? 1 : 0, 1);
      writer.bits(shortTime ? delta : time, shortTime ? 8 : 32);
      writer.bits(changed ? 1 : 0, 1);
      if (changed) {
        for (int i = 0; i < OFFSETS.length; i++) {
          int before = value(baseline, OFFSETS[i]), after = value(to, OFFSETS[i]);
          writer.bits(before == after ? 0 : 1, 1);
          if (before != after) writer.bits(after ^ key ^ time, WIDTHS[i]);
        }
      }
    } catch (RuntimeException failure) {
      writer.rollback(checkpoint);
      throw failure;
    }
  }

  /** Returns an owned state; malformed/truncated input restores the reader cursor. */
  public static byte[] read(MessageReader reader, int key, byte[] from) {
    byte[] result = StateBytes.baseline(from, Protocol68.USERCMD_BYTES);
    int checkpoint = reader.mark();
    try {
      int time =
          reader.bits(1) != 0 ? StateBytes.word(result, 0) + reader.byteValue() : reader.intValue();
      StateBytes.word(result, 0, time);
      if (reader.bits(1) != 0) {
        for (int i = 0; i < OFFSETS.length; i++) {
          if (reader.bits(1) == 0) continue;
          int decoded = (reader.bits(WIDTHS[i]) ^ key ^ time) & ((1 << WIDTHS[i]) - 1);
          if (OFFSETS[i] >= BYTE_FIELDS) result[OFFSETS[i]] = (byte) decoded;
          else StateBytes.word(result, OFFSETS[i], decoded);
        }
        // The native reader clamps -128 movement to -127 whenever the field group is present.
        for (int offset = MOVE_FIRST; offset < Protocol68.USERCMD_BYTES; offset++)
          if (result[offset] == Byte.MIN_VALUE) result[offset] = -127;
      }
      return result;
    } catch (RuntimeException failure) {
      reader.rewind(checkpoint);
      throw failure;
    }
  }

  private static int value(byte[] state, int offset) {
    return offset >= BYTE_FIELDS
        ? Byte.toUnsignedInt(state[offset])
        : StateBytes.word(state, offset);
  }
}

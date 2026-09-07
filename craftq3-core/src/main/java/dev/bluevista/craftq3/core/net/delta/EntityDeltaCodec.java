package dev.bluevista.craftq3.core.net.delta;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;

/** Protocol-68 deltas for owned canonical 208-byte little-endian entity states. */
public final class EntityDeltaCodec {
  public static final int STATE_BYTES = 208, NUMBER_BITS = 10, END_NUMBER = 1023;

  private EntityDeltaCodec() {}

  public record EntityUpdate(int number, boolean removed, byte[] state) {
    public EntityUpdate {
      if (number < 0 || number > END_NUMBER || (number == END_NUMBER && removed))
        throw new IllegalArgumentException("Invalid entity update number");
      state = StateBytes.copy(state, STATE_BYTES);
    }

    @Override
    public byte[] state() {
      return state.clone();
    }

    public boolean endOfList() {
      return number == END_NUMBER;
    }
  }

  /**
   * Null baselines are zero states. A null target removes an existing entity. Unchanged states emit
   * nothing unless forced. The complete write is atomic, including capacity failures.
   */
  public static boolean write(MessageWriter writer, byte[] from, byte[] to, boolean force) {
    if (from == null && to == null) return false;
    byte[] baseline = StateBytes.baseline(from, STATE_BYTES);
    byte[] target = to == null ? null : StateBytes.copy(to, STATE_BYTES);
    int number = StateBytes.word(target == null ? baseline : target, 0);
    checkNumber(number);
    int extent = target == null ? 0 : DeltaFields.extent(baseline, target, StateFields.ENTITY);
    if (target != null && extent == 0 && !force) return false;
    return writer.transaction(
        output -> {
          output.bits(number, NUMBER_BITS);
          output.bits(target == null ? 1 : 0, 1);
          if (target != null) {
            output.bits(extent == 0 ? 0 : 1, 1);
            if (extent != 0)
              DeltaFields.write(output, baseline, target, StateFields.ENTITY, extent, true);
          }
          return true;
        });
  }

  public static void writeEnd(MessageWriter writer) {
    writer.bits(END_NUMBER, NUMBER_BITS);
  }

  /** Reads an ID and its body, or the standalone entity-list terminator, atomically. */
  public static EntityUpdate read(MessageReader reader, byte[] from) {
    byte[] baseline = StateBytes.baseline(from, STATE_BYTES);
    return reader.transaction(
        input -> {
          int number = input.bits(NUMBER_BITS);
          return number == END_NUMBER
              ? new EntityUpdate(number, false, cleared())
              : body(input, baseline, number);
        });
  }

  /** For snapshot merge callers that already consumed the ID and selected the matching baseline. */
  public static EntityUpdate readBody(MessageReader reader, byte[] from, int number) {
    checkNumber(number);
    byte[] baseline = StateBytes.baseline(from, STATE_BYTES);
    return reader.transaction(input -> body(input, baseline, number));
  }

  private static EntityUpdate body(MessageReader reader, byte[] baseline, int number) {
    if (reader.bits(1) != 0) return new EntityUpdate(number, true, cleared());
    if (reader.bits(1) != 0) DeltaFields.read(reader, baseline, StateFields.ENTITY, true);
    StateBytes.word(baseline, 0, number);
    return new EntityUpdate(number, false, baseline);
  }

  private static byte[] cleared() {
    byte[] bytes = new byte[STATE_BYTES];
    StateBytes.word(bytes, 0, END_NUMBER);
    return bytes;
  }

  private static void checkNumber(int number) {
    if (number < 0 || number >= END_NUMBER)
      throw new IllegalArgumentException("Entity number must be 0..1022");
  }
}

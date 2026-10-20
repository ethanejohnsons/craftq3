package dev.bluevista.craftq3.engine.net.delta;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;

/** Protocol-68 deltas for owned canonical 208-byte little-endian entity states. */
public final class EntityDeltaCodec {
  private EntityDeltaCodec() {}

  public record EntityUpdate(int number, boolean removed, byte[] state) {
    public EntityUpdate {
      if (number < 0
          || number > Protocol68.ENTITYNUM_NONE
          || (number == Protocol68.ENTITYNUM_NONE && removed))
        throw new IllegalArgumentException("Invalid entity update number");
      state = StateBytes.copy(state, Protocol68.ENTITY_STATE_BYTES);
    }

    @Override
    public byte[] state() {
      return state.clone();
    }

    public boolean endOfList() {
      return number == Protocol68.ENTITYNUM_NONE;
    }
  }

  /**
   * Null baselines are zero states. A null target removes an existing entity. Unchanged states emit
   * nothing unless forced. The complete write is atomic, including capacity failures.
   */
  public static boolean write(MessageWriter writer, byte[] from, byte[] to, boolean force) {
    if (from == null && to == null) return false;
    if (from != null) StateBytes.check(from, Protocol68.ENTITY_STATE_BYTES);
    if (to != null) StateBytes.check(to, Protocol68.ENTITY_STATE_BYTES);
    byte[] baseline = from == null ? new byte[Protocol68.ENTITY_STATE_BYTES] : from;
    int number = StateBytes.word(to == null ? baseline : to, 0);
    checkNumber(number);
    int extent = to == null ? 0 : DeltaFields.extent(baseline, to, StateFields.ENTITY_TABLE);
    if (to != null && extent == 0 && !force) return false;
    int checkpoint = writer.mark();
    try {
      writer.bits(number, Protocol68.GENTITYNUM_BITS);
      writer.bits(to == null ? 1 : 0, 1);
      if (to != null) {
        writer.bits(extent == 0 ? 0 : 1, 1);
        if (extent != 0)
          DeltaFields.write(writer, baseline, to, StateFields.ENTITY_TABLE, extent, true);
      }
    } catch (RuntimeException failure) {
      writer.rollback(checkpoint);
      throw failure;
    }
    return true;
  }

  public static void writeEnd(MessageWriter writer) {
    writer.bits(Protocol68.ENTITYNUM_NONE, Protocol68.GENTITYNUM_BITS);
  }

  /** Reads an ID and its body, or the standalone entity-list terminator, atomically. */
  public static EntityUpdate read(MessageReader reader, byte[] from) {
    byte[] baseline = StateBytes.baseline(from, Protocol68.ENTITY_STATE_BYTES);
    int checkpoint = reader.mark();
    try {
      int number = reader.bits(Protocol68.GENTITYNUM_BITS);
      return number == Protocol68.ENTITYNUM_NONE
          ? new EntityUpdate(number, false, cleared())
          : body(reader, baseline, number);
    } catch (RuntimeException failure) {
      reader.rewind(checkpoint);
      throw failure;
    }
  }

  /** For snapshot merge callers that already consumed the ID and selected the matching baseline. */
  public static EntityUpdate readBody(MessageReader reader, byte[] from, int number) {
    checkNumber(number);
    byte[] baseline = StateBytes.baseline(from, Protocol68.ENTITY_STATE_BYTES);
    int checkpoint = reader.mark();
    try {
      return body(reader, baseline, number);
    } catch (RuntimeException failure) {
      reader.rewind(checkpoint);
      throw failure;
    }
  }

  /**
   * Applies one entity body in place: {@code state} holds the baseline on entry and the updated
   * state on return. Returns false when the entity was removed (the state is then unspecified).
   * Allocation-free form for snapshot parsing; the caller has consumed the entity number.
   */
  static boolean readBodyInPlace(
      MessageReader reader, byte[] state, int number, StateFields.Table fields) {
    if (reader.bits(1) != 0) return false;
    if (reader.bits(1) != 0) DeltaFields.read(reader, state, fields, true);
    StateBytes.word(state, 0, number);
    return true;
  }

  private static EntityUpdate body(MessageReader reader, byte[] baseline, int number) {
    if (!readBodyInPlace(reader, baseline, number, StateFields.ENTITY_TABLE))
      return new EntityUpdate(number, true, cleared());
    return new EntityUpdate(number, false, baseline);
  }

  private static byte[] cleared() {
    byte[] bytes = new byte[Protocol68.ENTITY_STATE_BYTES];
    StateBytes.word(bytes, 0, Protocol68.ENTITYNUM_NONE);
    return bytes;
  }

  private static void checkNumber(int number) {
    if (number < 0 || number >= Protocol68.ENTITYNUM_NONE)
      throw new IllegalArgumentException("Entity number must be 0..1022");
  }
}

package dev.bluevista.craftq3.engine.net.delta;

import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.IntFunction;

/** Protocol-68 snapshot bodies, independent of transport, history retention and presentation. */
public final class SnapshotDeltaCodec {
  /** Largest delta distance a snapshot header can express. */
  public static final int MAX_DELTA_DISTANCE = 255;

  private SnapshotDeltaCodec() {}

  /**
   * The protocol-specific parts of a snapshot body: the player-state delta and one entity body. The
   * header, history selection and the entity merge are shared by every protocol.
   */
  interface StateReader {
    /** Applies a player-state delta to {@code state} in place. */
    void player(MessageReader reader, byte[] state);

    /**
     * Applies one entity body (after its number) to {@code state} in place; false when the entity
     * is removed.
     */
    boolean entity(MessageReader reader, byte[] state, int number);
  }

  private static final StateReader PROTOCOL_68 =
      new StateReader() {
        @Override
        public void player(MessageReader reader, byte[] state) {
          PlayerDeltaCodec.readInPlace(reader, state, StateFields.PLAYER_TABLE);
        }

        @Override
        public boolean entity(MessageReader reader, byte[] state, int number) {
          return EntityDeltaCodec.readBodyInPlace(reader, state, number, StateFields.ENTITY_TABLE);
        }
      };

  /** The message sequence is supplied by netchan or demo framing, not stored in the body. */
  public record Snapshot(
      int sequence, int time, int flags, byte[] areaMask, byte[] player, List<byte[]> entities) {
    public Snapshot {
      if (sequence < 1 || flags < 0 || flags > 255)
        throw new IllegalArgumentException("Invalid snapshot sequence or flags");
      if (areaMask.length > Protocol68.MAX_MAP_AREA_BYTES
          || player.length != Protocol68.PLAYER_STATE_BYTES)
        throw new IllegalArgumentException("Invalid snapshot area mask or player state");
      areaMask = areaMask.clone();
      player = player.clone();
      entities = copyEntities(entities, Protocol68.MAX_ENTITIES_IN_SNAPSHOT);
    }

    @Override
    public byte[] areaMask() {
      return areaMask.clone();
    }

    @Override
    public byte[] player() {
      return player.clone();
    }

    @Override
    public List<byte[]> entities() {
      return entities.stream().map(byte[]::clone).toList();
    }
  }

  /** Owned level baselines. Missing entries use the zero entity state. */
  public static final class Baselines {
    public static final Baselines EMPTY = new Baselines(List.of());
    private final Map<Integer, byte[]> states;

    public Baselines(List<byte[]> entities) {
      var entries = new TreeMap<Integer, byte[]>();
      for (byte[] entity : copyEntities(entities, Protocol68.ENTITYNUM_NONE))
        entries.put(number(entity), entity);
      states = Map.copyOf(entries);
    }

    public byte[] state(int number) {
      checkNumber(number);
      var state = states.get(number);
      return state == null ? new byte[Protocol68.ENTITY_STATE_BYTES] : state.clone();
    }

    public List<byte[]> entities() {
      return states.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(entry -> entry.getValue().clone())
          .toList();
    }
  }

  /** Writes a complete body atomically. A null previous snapshot produces a full update. */
  public static void write(
      MessageWriter writer, Snapshot previous, Snapshot current, Baselines baselines) {
    Objects.requireNonNull(current);
    Objects.requireNonNull(baselines);
    int distance = previous == null ? 0 : current.sequence - previous.sequence;
    if (previous != null && (distance < 1 || distance > MAX_DELTA_DISTANCE))
      throw new IllegalArgumentException(
          "Snapshot delta must reference an earlier message within 255");
    int checkpoint = writer.mark();
    try {
      writer.intValue(current.time);
      writer.byteValue(distance);
      writer.byteValue(current.flags);
      writer.byteValue(current.areaMask.length);
      for (byte value : current.areaMask) writer.byteValue(value);
      PlayerDeltaCodec.write(writer, previous == null ? null : previous.player, current.player);
      var before = previous == null ? List.<byte[]>of() : previous.entities;
      int oldIndex = 0;
      for (byte[] entity : current.entities) {
        int id = number(entity);
        while (oldIndex < before.size() && number(before.get(oldIndex)) < id)
          EntityDeltaCodec.write(writer, before.get(oldIndex++), null, false);
        boolean existed = oldIndex < before.size() && number(before.get(oldIndex)) == id;
        byte[] baseline = existed ? before.get(oldIndex++) : baselines.states.get(id);
        EntityDeltaCodec.write(writer, baseline, entity, !existed);
      }
      while (oldIndex < before.size())
        EntityDeltaCodec.write(writer, before.get(oldIndex++), null, false);
      EntityDeltaCodec.writeEnd(writer);
    } catch (RuntimeException failure) {
      writer.rollback(checkpoint);
      throw failure;
    }
  }

  /**
   * Reads a body atomically. Delta updates require the exact referenced previous snapshot; a caller
   * retaining packet history must select it using the message sequence and delta distance. Missing
   * history is rejected instead of publishing a state reconstructed from the wrong base.
   */
  public static Snapshot read(
      MessageReader reader, int sequence, Snapshot previous, Baselines baselines) {
    return read(reader, sequence, baselines, ignored -> previous);
  }

  /** Looks up the exact referenced message once; full updates never consult history. */
  public static Snapshot read(
      MessageReader reader, int sequence, Baselines baselines, IntFunction<Snapshot> history) {
    return readBody(reader, sequence, baselines, history, false, PROTOCOL_68).orElseThrow();
  }

  /** Consumes missing-base updates without publishing their reconstructed state. */
  public static Optional<Snapshot> readAvailable(
      MessageReader reader, int sequence, Baselines baselines, IntFunction<Snapshot> history) {
    return readBody(reader, sequence, baselines, history, true, PROTOCOL_68);
  }

  static Optional<Snapshot> readBody(
      MessageReader reader,
      int sequence,
      Baselines baselines,
      IntFunction<Snapshot> history,
      boolean allowMissing,
      StateReader stateReader) {
    Objects.requireNonNull(baselines);
    Objects.requireNonNull(history);
    int checkpoint = reader.mark();
    try {
      int time = reader.intValue(), distance = reader.byteValue(), flags = reader.byteValue();
      Snapshot from = distance == 0 ? null : history.apply(sequence - distance);
      boolean missing = distance != 0 && (from == null || from.sequence != sequence - distance);
      if (missing && !allowMissing)
        throw new NetFormatException(
            "Missing snapshot baseline for message " + (sequence - distance));
      if (missing) from = null;
      int areaBytes = reader.byteValue();
      if (areaBytes > Protocol68.MAX_MAP_AREA_BYTES)
        throw new NetFormatException("Oversized snapshot area mask of " + areaBytes + " bytes");
      byte[] area = new byte[areaBytes];
      for (int i = 0; i < area.length; i++) area[i] = (byte) reader.byteValue();
      byte[] player =
          StateBytes.baseline(from == null ? null : from.player, Protocol68.PLAYER_STATE_BYTES);
      stateReader.player(reader, player);
      var before = from == null ? List.<byte[]>of() : from.entities;
      var entities = new ArrayList<byte[]>();
      int oldIndex = 0, lastNumber = -1;
      while (true) {
        int id = reader.bits(Protocol68.GENTITYNUM_BITS);
        if (id == Protocol68.ENTITYNUM_NONE) break;
        if (id <= lastNumber)
          throw new NetFormatException(
              "Snapshot entity " + id + " follows " + lastNumber + "; updates must be ordered");
        lastNumber = id;
        while (oldIndex < before.size() && number(before.get(oldIndex)) < id)
          append(entities, before.get(oldIndex++));
        boolean existed = oldIndex < before.size() && number(before.get(oldIndex)) == id;
        byte[] baseline = existed ? before.get(oldIndex++) : baselines.states.get(id);
        byte[] state = StateBytes.baseline(baseline, Protocol68.ENTITY_STATE_BYTES);
        if (stateReader.entity(reader, state, id)) append(entities, state);
      }
      while (oldIndex < before.size()) append(entities, before.get(oldIndex++));
      Snapshot result = new Snapshot(sequence, time, flags, area, player, entities);
      return missing ? Optional.empty() : Optional.of(result);
    } catch (RuntimeException failure) {
      reader.rewind(checkpoint);
      throw failure;
    }
  }

  private static void append(List<byte[]> entities, byte[] entity) {
    if (entities.size() == Protocol68.MAX_ENTITIES_IN_SNAPSHOT)
      throw new NetFormatException("Too many snapshot entities");
    entities.add(entity);
  }

  private static List<byte[]> copyEntities(List<byte[]> entities, int maximum) {
    if (entities.size() > maximum) throw new IllegalArgumentException("Too many entity states");
    var result = new ArrayList<byte[]>(entities.size());
    int last = -1;
    for (byte[] entity : entities) {
      if (entity.length != Protocol68.ENTITY_STATE_BYTES)
        throw new IllegalArgumentException("Invalid entity state size");
      byte[] copy = entity.clone();
      int id = number(copy);
      checkNumber(id);
      if (id <= last)
        throw new IllegalArgumentException("Entity states must have strictly increasing numbers");
      result.add(copy);
      last = id;
    }
    return List.copyOf(result);
  }

  private static int number(byte[] state) {
    return StateBytes.word(state, 0);
  }

  private static void checkNumber(int number) {
    if (number < 0 || number >= Protocol68.ENTITYNUM_NONE)
      throw new IllegalArgumentException("Entity number must be 0..1022");
  }
}

package dev.bluevista.craftq3.core.net;

import dev.bluevista.craftq3.core.net.delta.EntityDeltaCodec;
import dev.bluevista.craftq3.core.net.delta.PlayerDeltaCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntFunction;

/** Protocol-68 snapshot bodies, independent of transport, history retention and presentation. */
public final class SnapshotDeltaCodec {
  public static final int MAX_ENTITIES = 256, MAX_AREA_BYTES = 32;

  private SnapshotDeltaCodec() {}

  /** The message sequence is supplied by netchan or demo framing, not stored in the body. */
  public record Snapshot(
      int sequence, int time, int flags, byte[] areaMask, byte[] player, List<byte[]> entities) {
    public Snapshot {
      if (sequence < 1 || flags < 0 || flags > 255)
        throw new IllegalArgumentException("Invalid snapshot sequence or flags");
      if (areaMask.length > MAX_AREA_BYTES || player.length != PlayerDeltaCodec.STATE_BYTES)
        throw new IllegalArgumentException("Invalid snapshot area mask or player state");
      areaMask = areaMask.clone();
      player = player.clone();
      entities = copyEntities(entities, MAX_ENTITIES);
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
      for (byte[] entity : copyEntities(entities, EntityDeltaCodec.END_NUMBER))
        entries.put(number(entity), entity);
      states = Map.copyOf(entries);
    }

    public byte[] state(int number) {
      checkNumber(number);
      var state = states.get(number);
      return state == null ? new byte[EntityDeltaCodec.STATE_BYTES] : state.clone();
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
    if (previous != null && (distance < 1 || distance > 255))
      throw new IllegalArgumentException(
          "Snapshot delta must reference an earlier message within 255");
    writer.transaction(
        output -> {
          output.intValue(current.time);
          output.byteValue(distance);
          output.byteValue(current.flags);
          output.byteValue(current.areaMask.length);
          for (byte value : current.areaMask) output.byteValue(value);
          PlayerDeltaCodec.write(output, previous == null ? null : previous.player, current.player);
          var before = previous == null ? List.<byte[]>of() : previous.entities;
          int oldIndex = 0;
          for (byte[] entity : current.entities) {
            int id = number(entity);
            while (oldIndex < before.size() && number(before.get(oldIndex)) < id)
              EntityDeltaCodec.write(output, before.get(oldIndex++), null, false);
            boolean existed = oldIndex < before.size() && number(before.get(oldIndex)) == id;
            byte[] baseline = existed ? before.get(oldIndex++) : baselines.states.get(id);
            EntityDeltaCodec.write(output, baseline, entity, !existed);
          }
          while (oldIndex < before.size())
            EntityDeltaCodec.write(output, before.get(oldIndex++), null, false);
          EntityDeltaCodec.writeEnd(output);
          return null;
        });
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
    return readBody(reader, sequence, baselines, history, false).orElseThrow();
  }

  /** Consumes missing-base updates without publishing their reconstructed state. */
  public static java.util.Optional<Snapshot> readAvailable(
      MessageReader reader, int sequence, Baselines baselines, IntFunction<Snapshot> history) {
    return readBody(reader, sequence, baselines, history, true);
  }

  private static java.util.Optional<Snapshot> readBody(
      MessageReader reader,
      int sequence,
      Baselines baselines,
      IntFunction<Snapshot> history,
      boolean allowMissing) {
    Objects.requireNonNull(baselines);
    Objects.requireNonNull(history);
    return reader.transaction(
        input -> {
          int time = input.intValue(), distance = input.byteValue(), flags = input.byteValue();
          Snapshot from = distance == 0 ? null : history.apply(sequence - distance);
          boolean missing = distance != 0 && (from == null || from.sequence != sequence - distance);
          if (missing && !allowMissing)
            throw new IllegalArgumentException(
                "Missing snapshot baseline for message " + (sequence - distance));
          if (missing) from = null;
          int areaBytes = input.byteValue();
          if (areaBytes > MAX_AREA_BYTES)
            throw new IllegalArgumentException("Oversized snapshot area mask");
          byte[] area = new byte[areaBytes];
          for (int i = 0; i < area.length; i++) area[i] = (byte) input.byteValue();
          byte[] player = PlayerDeltaCodec.read(input, from == null ? null : from.player);
          var before = from == null ? List.<byte[]>of() : from.entities;
          var entities = new ArrayList<byte[]>();
          int oldIndex = 0, lastNumber = -1;
          while (true) {
            int id = input.bits(EntityDeltaCodec.NUMBER_BITS);
            if (id == EntityDeltaCodec.END_NUMBER) break;
            if (id <= lastNumber)
              throw new IllegalArgumentException("Snapshot updates are not strictly ordered");
            lastNumber = id;
            while (oldIndex < before.size() && number(before.get(oldIndex)) < id)
              append(entities, before.get(oldIndex++));
            boolean existed = oldIndex < before.size() && number(before.get(oldIndex)) == id;
            byte[] baseline = existed ? before.get(oldIndex++) : baselines.states.get(id);
            var update = EntityDeltaCodec.readBody(input, baseline, id);
            if (!update.removed()) append(entities, update.state());
          }
          while (oldIndex < before.size()) append(entities, before.get(oldIndex++));
          Snapshot result = new Snapshot(sequence, time, flags, area, player, entities);
          return missing ? java.util.Optional.empty() : java.util.Optional.of(result);
        });
  }

  private static void append(List<byte[]> entities, byte[] entity) {
    if (entities.size() == MAX_ENTITIES)
      throw new IllegalArgumentException("Too many snapshot entities");
    entities.add(entity);
  }

  private static List<byte[]> copyEntities(List<byte[]> entities, int maximum) {
    if (entities.size() > maximum) throw new IllegalArgumentException("Too many entity states");
    var result = new ArrayList<byte[]>(entities.size());
    int last = -1;
    for (byte[] entity : entities) {
      if (entity.length != EntityDeltaCodec.STATE_BYTES)
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
    return ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getInt();
  }

  private static void checkNumber(int number) {
    if (number < 0 || number >= EntityDeltaCodec.END_NUMBER)
      throw new IllegalArgumentException("Entity number must be 0..1022");
  }
}

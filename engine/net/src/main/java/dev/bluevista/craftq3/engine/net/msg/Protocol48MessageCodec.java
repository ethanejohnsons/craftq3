package dev.bluevista.craftq3.engine.net.msg;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.Protocol48Codec;
import dev.bluevista.craftq3.engine.net.delta.Protocol48Codec.Variant;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Command;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Message;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.NoOp;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Protocol-48 server messages (Quake III 1.25 and 1.27 demos), decoded into the same {@link
 * Message} operations as protocol 68. The reader must use {@link MessageReader.Coding#RAW}.
 *
 * <p>The envelope, opcodes, commands, configstrings and snapshot header are those of 68. A message
 * has no {@code svc_EOF}: it ends when fewer than 8 bits, all zero, remain. A gamestate's records
 * end with a zero byte, with no client number or checksum feed after it, so its {@link GameState}
 * reports client number {@link #UNKNOWN_CLIENT} and checksum feed 0; the recorded client is the
 * {@code clientNum} of the following snapshots' player state.
 */
public final class Protocol48MessageCodec {
  /** The client number reported by a protocol-48 gamestate, which does not carry one. */
  public static final int UNKNOWN_CLIENT = -1;

  private Protocol48MessageCodec() {}

  /** Reads one complete payload atomically; delta snapshots require their exact base. */
  public static Message read(
      MessageReader reader,
      int sequence,
      Baselines levelBaselines,
      IntFunction<Snapshot> history,
      Variant variant) {
    return readMessage(reader, sequence, levelBaselines, history, variant, false);
  }

  /** Native demo playback retains surrounding commands while ignoring a missing-base snapshot. */
  public static Message readForPlayback(
      MessageReader reader,
      int sequence,
      Baselines levelBaselines,
      IntFunction<Snapshot> history,
      Variant variant) {
    return readMessage(reader, sequence, levelBaselines, history, variant, true);
  }

  /** The player-state layout a gamestate selects through its server info {@code protocol}. */
  public static Variant variant(GameState game) {
    String info = game.configstrings().get(Protocol68.CS_SERVERINFO);
    if (info == null) return Variant.PROTOCOL_48;
    try {
      String protocol = InfoString.parse(info, Protocol68.BIG_INFO_STRING).get("protocol");
      return protocol == null
          ? Variant.PROTOCOL_48
          : Variant.forAnnouncedProtocol(Integer.parseInt(protocol.trim()));
    } catch (IllegalArgumentException malformed) {
      return Variant.PROTOCOL_48;
    }
  }

  private static Message readMessage(
      MessageReader reader,
      int sequence,
      Baselines levelBaselines,
      IntFunction<Snapshot> history,
      Variant initialVariant,
      boolean allowMissing) {
    Objects.requireNonNull(levelBaselines);
    Objects.requireNonNull(history);
    Objects.requireNonNull(initialVariant);
    if (reader.coding() != MessageReader.Coding.RAW)
      throw new IllegalArgumentException("Protocol-48 messages are read with raw bit coding");
    return reader.transaction(
        input -> {
          int acknowledge = input.intValue();
          var operations = new ArrayList<Operation>();
          Baselines baselines = levelBaselines;
          IntFunction<Snapshot> lookup = history;
          Variant variant = initialVariant;
          while (true) {
            int remaining = input.remainingBits();
            if (remaining < 8) {
              if (remaining > 0 && input.bits(remaining) != 0)
                throw new NetFormatException("Protocol-48 message ends in non-zero padding");
              return new Message(acknowledge, operations);
            }
            if (operations.size() == Protocol68.MAX_SERVER_OPERATIONS)
              throw new NetFormatException("Too many server operations");
            int code = input.byteValue();
            switch (code) {
              case Protocol68.SVC_NOP -> operations.add(NoOp.INSTANCE);
              case Protocol68.SVC_SERVERCOMMAND ->
                  operations.add(new Command(input.intValue(), input.stringValue()));
              case Protocol68.SVC_GAMESTATE -> {
                var game = readGameState(input);
                operations.add(game);
                baselines = game.baselines();
                lookup = ignored -> null;
                variant = variant(game);
              }
              case Protocol68.SVC_SNAPSHOT -> {
                Snapshot[] previous = new Snapshot[1];
                IntFunction<Snapshot> source = lookup;
                IntFunction<Snapshot> reference =
                    number -> {
                      previous[0] = source.apply(number);
                      return previous[0];
                    };
                if (allowMissing)
                  Protocol48Codec.readSnapshotAvailable(
                          input, sequence, baselines, reference, variant)
                      .ifPresent(current -> operations.add(new Frame(current, previous[0])));
                else {
                  var current =
                      Protocol48Codec.readSnapshot(input, sequence, baselines, reference, variant);
                  operations.add(new Frame(current, previous[0]));
                }
              }
              default ->
                  throw new NetFormatException("Unsupported protocol-48 server operation " + code);
            }
          }
        });
  }

  private static GameState readGameState(MessageReader input) {
    int commands = input.intValue(), bytes = 1, records = 0;
    var strings = new TreeMap<Integer, String>();
    var entities = new TreeMap<Integer, byte[]>();
    while (true) {
      int code = input.byteValue();
      if (code == Protocol68.SVC_BAD) break;
      if (++records > Protocol68.MAX_SERVER_OPERATIONS)
        throw new NetFormatException("Too many gamestate records");
      if (code == Protocol68.SVC_CONFIGSTRING) {
        int index = input.shortValue();
        if (index < 0 || index >= Protocol68.MAX_CONFIGSTRINGS)
          throw new NetFormatException("Invalid configstring index " + index);
        String text = input.bigStringValue();
        bytes += text.length() + 1;
        if (bytes > Protocol68.MAX_GAMESTATE_CHARS)
          throw new NetFormatException("Gamestate text budget exceeded");
        strings.put(index, text);
      } else if (code == Protocol68.SVC_BASELINE) {
        var update = Protocol48Codec.readBaseline(input);
        entities.put(update.number(), update.state());
      } else throw new NetFormatException("Invalid protocol-48 gamestate operation " + code);
    }
    return new GameState(
        commands, strings, new Baselines(List.copyOf(entities.values())), UNKNOWN_CLIENT, 0);
  }
}

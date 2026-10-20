package dev.bluevista.craftq3.engine.net.msg;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.EntityDeltaCodec;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Bounded protocol-68 server message payloads. Decoding produces data without executing commands.
 */
public final class ServerMessageCodec {
  private ServerMessageCodec() {}

  public sealed interface Operation permits NoOp, Command, GameState, Frame, Download {}

  public enum NoOp implements Operation {
    INSTANCE
  }

  public record Command(int sequence, String text) implements Operation {
    public Command {
      checkText(text, Protocol68.MAX_STRING_CHARS);
    }
  }

  /** The size/error header occurs only at the start of a transfer, not at block-number wrap. */
  public record Download(int block, Integer fileSize, byte[] data, String error)
      implements Operation {
    public Download {
      if (block < 0
          || block > 65535
          || fileSize != null && block != 0
          || data.length > Protocol68.MAX_MSGLEN
          || (fileSize != null && fileSize < 0) != (error != null)
          || error != null && data.length != 0)
        throw new IllegalArgumentException("Invalid download operation");
      if (error != null) checkText(error, Protocol68.MAX_STRING_CHARS);
      data = data.clone();
    }

    @Override
    public byte[] data() {
      return data.clone();
    }
  }

  public record GameState(
      int commandSequence,
      Map<Integer, String> configstrings,
      Baselines baselines,
      int clientNumber,
      int checksumFeed)
      implements Operation {
    public GameState {
      Objects.requireNonNull(baselines);
      var copy = new TreeMap<Integer, String>();
      int bytes = 1;
      for (var entry : configstrings.entrySet()) {
        checkIndex(entry.getKey());
        checkText(entry.getValue(), Protocol68.BIG_INFO_STRING);
        if (entry.getValue().isEmpty()) continue;
        bytes += entry.getValue().length() + 1;
        if (bytes > Protocol68.MAX_GAMESTATE_CHARS)
          throw new IllegalArgumentException("Gamestate text budget exceeded");
        copy.put(entry.getKey(), entry.getValue());
      }
      configstrings = Map.copyOf(copy);
    }
  }

  /** Both snapshots are immutable; null previous means an independent full snapshot. */
  public record Frame(Snapshot current, Snapshot previous) implements Operation {
    public Frame {
      Objects.requireNonNull(current);
    }
  }

  public record Message(int reliableAcknowledge, List<Operation> operations) {
    public Message {
      if (operations.size() > Protocol68.MAX_SERVER_OPERATIONS)
        throw new IllegalArgumentException("Too many server operations");
      operations = List.copyOf(operations);
    }
  }

  public static void write(MessageWriter writer, Message message, Baselines levelBaselines) {
    Objects.requireNonNull(levelBaselines);
    writer.transaction(
        output -> {
          output.intValue(message.reliableAcknowledge);
          Baselines baselines = levelBaselines;
          boolean reset = false;
          for (var operation : message.operations) {
            switch (operation) {
              case NoOp ignored -> output.byteValue(Protocol68.SVC_NOP);
              case Command command -> {
                output.byteValue(Protocol68.SVC_SERVERCOMMAND);
                output.intValue(command.sequence);
                output.stringValue(command.text);
              }
              case GameState game -> {
                output.byteValue(Protocol68.SVC_GAMESTATE);
                output.intValue(game.commandSequence);
                for (var entry : new TreeMap<>(game.configstrings).entrySet()) {
                  output.byteValue(Protocol68.SVC_CONFIGSTRING);
                  output.shortValue(entry.getKey());
                  output.bigStringValue(entry.getValue());
                }
                for (byte[] entity : game.baselines.entities()) {
                  output.byteValue(Protocol68.SVC_BASELINE);
                  EntityDeltaCodec.write(output, null, entity, true);
                }
                output.byteValue(Protocol68.SVC_EOF);
                output.intValue(game.clientNumber);
                output.intValue(game.checksumFeed);
                baselines = game.baselines;
                reset = true;
              }
              case Download download -> {
                output.byteValue(Protocol68.SVC_DOWNLOAD);
                DownloadMessageCodec.write(output, download);
              }
              case Frame frame -> {
                if (reset && frame.previous != null)
                  throw new IllegalArgumentException(
                      "Gamestate reset invalidates previous snapshots");
                output.byteValue(Protocol68.SVC_SNAPSHOT);
                SnapshotDeltaCodec.write(output, frame.previous, frame.current, baselines);
              }
            }
          }
          output.byteValue(Protocol68.SVC_EOF);
          return null;
        });
  }

  /**
   * Reads one complete payload, restoring the cursor on malformed/unsupported operations or missing
   * delta history. The caller owns reliable sequencing, history retention and applying level
   * changes.
   */
  public static Message read(
      MessageReader reader, int sequence, Baselines levelBaselines, IntFunction<Snapshot> history) {
    return read(reader, sequence, levelBaselines, history, 0);
  }

  public static Message read(
      MessageReader reader,
      int sequence,
      Baselines levelBaselines,
      IntFunction<Snapshot> history,
      int expectedDownloadBlock) {
    if (expectedDownloadBlock < 0) throw new IllegalArgumentException("Invalid download block");
    return readMessage(reader, sequence, levelBaselines, history, false, expectedDownloadBlock);
  }

  /** Native demo playback retains surrounding commands while ignoring a missing-base snapshot. */
  public static Message readForPlayback(
      MessageReader reader, int sequence, Baselines levelBaselines, IntFunction<Snapshot> history) {
    return readMessage(reader, sequence, levelBaselines, history, true, 0);
  }

  private static Message readMessage(
      MessageReader reader,
      int sequence,
      Baselines levelBaselines,
      IntFunction<Snapshot> history,
      boolean allowMissing,
      int expectedDownloadBlock) {
    Objects.requireNonNull(levelBaselines);
    Objects.requireNonNull(history);
    return reader.transaction(
        input -> {
          int acknowledge = input.intValue();
          int downloadBlock = expectedDownloadBlock;
          var operations = new ArrayList<Operation>();
          Baselines baselines = levelBaselines;
          IntFunction<Snapshot> lookup = history;
          while (true) {
            int code = input.byteValue();
            if (code == Protocol68.SVC_EOF) return new Message(acknowledge, operations);
            if (operations.size() == Protocol68.MAX_SERVER_OPERATIONS)
              throw new IllegalArgumentException("Too many server operations");
            switch (code) {
              case Protocol68.SVC_NOP -> operations.add(NoOp.INSTANCE);
              case Protocol68.SVC_SERVERCOMMAND ->
                  operations.add(new Command(input.intValue(), input.stringValue()));
              case Protocol68.SVC_GAMESTATE -> {
                var game = readGameState(input);
                operations.add(game);
                baselines = game.baselines;
                lookup = ignored -> null;
                downloadBlock = 0;
              }
              case Protocol68.SVC_DOWNLOAD -> {
                var download = DownloadMessageCodec.read(input, downloadBlock);
                operations.add(download);
                if (download.error() == null && download.block() == (downloadBlock & 65535))
                  downloadBlock++;
              }
              case Protocol68.SVC_SNAPSHOT -> {
                Snapshot[] previous = new Snapshot[1];
                IntFunction<Snapshot> source = lookup;
                IntFunction<Snapshot> reference =
                    number -> {
                      previous[0] = source.apply(number);
                      return previous[0];
                    };
                if (allowMissing) {
                  var current =
                      SnapshotDeltaCodec.readAvailable(input, sequence, baselines, reference);
                  current.ifPresent(value -> operations.add(new Frame(value, previous[0])));
                } else
                  operations.add(
                      new Frame(
                          SnapshotDeltaCodec.read(input, sequence, baselines, reference),
                          previous[0]));
              }
              default ->
                  throw new IllegalArgumentException(
                      "Unsupported protocol-68 server operation " + code);
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
      if (code == Protocol68.SVC_EOF) break;
      if (++records > Protocol68.MAX_SERVER_OPERATIONS)
        throw new IllegalArgumentException("Too many gamestate records");
      if (code == Protocol68.SVC_CONFIGSTRING) {
        int index = input.shortValue();
        checkIndex(index);
        String text = input.bigStringValue();
        bytes += text.length() + 1;
        if (bytes > Protocol68.MAX_GAMESTATE_CHARS)
          throw new IllegalArgumentException("Gamestate text budget exceeded");
        strings.put(index, text);
      } else if (code == Protocol68.SVC_BASELINE) {
        var update = EntityDeltaCodec.read(input, null);
        if (update.endOfList() || update.removed())
          throw new IllegalArgumentException("Invalid gamestate entity baseline");
        entities.put(update.number(), update.state());
      } else throw new IllegalArgumentException("Invalid gamestate operation " + code);
    }
    int clientNumber = input.intValue(), feed = input.intValue();
    return new GameState(
        commands, strings, new Baselines(List.copyOf(entities.values())), clientNumber, feed);
  }

  private static void checkText(String text, int capacity) {
    if (text.length() >= capacity || text.chars().anyMatch(c -> c == 0 || c > 255))
      throw new IllegalArgumentException("Invalid bounded server byte string");
  }

  private static void checkIndex(int index) {
    if (index < 0 || index >= Protocol68.MAX_CONFIGSTRINGS)
      throw new IllegalArgumentException("Invalid configstring index");
  }
}

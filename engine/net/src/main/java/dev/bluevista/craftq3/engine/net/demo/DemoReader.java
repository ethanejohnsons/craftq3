package dev.bluevista.craftq3.engine.net.demo;

import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.LegacyDeltaCodec;
import dev.bluevista.craftq3.engine.net.delta.PlayerDeltaCodec;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.msg.LegacyServerMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec.GameState;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Incremental decoding of a {@code .dm_NN} demo: container framing ({@link DemoRecordReader}) plus
 * the server-message stream of a {@link DemoProtocol} (43, 48, 66, 67 and 68). Each record yields
 * its decoded message; gamestates and snapshots update the level view, with snapshot deltas
 * resolved against the exact retained frame they name. Commands are returned as data, never
 * executed.
 *
 * <p>Protocols 43 and 48 decode into the same structures as 68. Their gamestates carry no client
 * number, so the reader decodes ahead to the level's first snapshot and reports that player state's
 * {@code clientNum} in the {@link GameState} it returns.
 */
public final class DemoReader implements AutoCloseable {
  public record DecodedRecord(int sequence, ServerMessageCodec.Message message) {
    public DecodedRecord {
      Objects.requireNonNull(message);
    }
  }

  private final DemoRecordReader records;
  private final DemoProtocol protocol;
  private final boolean playback;
  private final byte[] payload = new byte[Protocol68.MAX_MSGLEN];
  private final MessageReader reader = new MessageReader();
  private final TreeMap<Integer, Snapshot> history = new TreeMap<>();
  private Baselines baselines = Baselines.EMPTY;
  private GameState gameState;
  private Snapshot latest;
  private boolean failed, closed;
  private LegacyDeltaCodec.Variant variant;

  /** Records decoded ahead while resolving a legacy client number, and a deferred failure. */
  private final ArrayDeque<DecodedRecord> pending = new ArrayDeque<>();

  private IOException deferred;

  /** A strict protocol-68 reader. Owns the stream. */
  public DemoReader(InputStream input) {
    this(input, DemoRecordReader.Policy.STRICT);
  }

  /** A protocol-68 reader. Owns the stream. */
  public DemoReader(InputStream input, DemoRecordReader.Policy policy) {
    this(input, DemoProtocol.Q3_1_32, policy);
  }

  /**
   * Owns the stream.
   *
   * @throws UnsupportedDemoProtocolException for a protocol whose messages cannot be decoded
   */
  public DemoReader(InputStream input, DemoProtocol protocol, DemoRecordReader.Policy policy) {
    Objects.requireNonNull(input);
    this.protocol = Objects.requireNonNull(protocol);
    if (!protocol.supported())
      throw new UnsupportedDemoProtocolException(
          protocol.number(), "Demo protocol " + protocol.number() + " is not supported");
    records = new DemoRecordReader(input, policy);
    playback = policy == DemoRecordReader.Policy.PLAYBACK;
    reader.coding(protocol.coding());
    variant =
        switch (protocol) {
          case Q3_1_09 -> LegacyDeltaCodec.Variant.PROTOCOL_43;
          case Q3_1_27 -> LegacyDeltaCodec.Variant.PROTOCOL_48;
          default -> null;
        };
  }

  /**
   * Opens a demo by its file name's {@code .dm_NN} protocol. Owns the stream.
   *
   * @throws UnsupportedDemoProtocolException for an unknown or unsupported protocol number
   */
  public static DemoReader open(String fileName, InputStream input, DemoRecordReader.Policy policy)
      throws IOException {
    var protocol = DemoProtocol.fromFileName(fileName);
    if (protocol.isEmpty()) {
      input.close();
      throw new UnsupportedDemoProtocolException(-1, "Not a known .dm_NN demo name: " + fileName);
    }
    try {
      return new DemoReader(input, protocol.get(), policy);
    } catch (RuntimeException failure) {
      input.close();
      throw failure;
    }
  }

  public DemoProtocol protocol() {
    return protocol;
  }

  public long recordsRead() {
    return records.recordsRead();
  }

  public DemoRecordReader.End end() {
    return records.end();
  }

  /** Last transmitted gamestate; subsequent reliable configstring commands remain unapplied. */
  public Optional<GameState> gameState() {
    return Optional.ofNullable(gameState);
  }

  /**
   * The {@code protocol} key of the gamestate's server info, which may differ from the file's
   * protocol (1.25 servers announce 46 in demos named {@code .dm_48}).
   */
  public Optional<Integer> announcedProtocol() {
    if (gameState == null) return Optional.empty();
    String info = gameState.configstrings().get(Protocol68.CS_SERVERINFO);
    if (info == null) return Optional.empty();
    try {
      return Optional.ofNullable(InfoString.parse(info, Protocol68.BIG_INFO_STRING).get("protocol"))
          .map(Integer::valueOf);
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  public Optional<Snapshot> latestSnapshot() {
    return Optional.ofNullable(latest);
  }

  /** The retained snapshot of message {@code sequence}, if still within the history window. */
  public Optional<Snapshot> snapshot(int sequence) {
    return Optional.ofNullable(history.get(sequence));
  }

  /**
   * A malformed record fails the reader without partially publishing its level or snapshot state.
   */
  public Optional<DecodedRecord> next() throws IOException {
    if (closed) throw new IOException("Demo reader is closed or failed");
    if (!pending.isEmpty()) return Optional.of(pending.poll());
    if (deferred != null) {
      IOException failure = deferred;
      deferred = null;
      throw failure;
    }
    if (failed) throw new IOException("Demo reader is closed or failed");
    var record = decodeNext();
    if (record.isPresent() && variant != null)
      return Optional.of(resolveClientNumber(record.get()));
    return record;
  }

  /**
   * A legacy (43 or 48) gamestate has no client number: decode ahead (at most {@link
   * Protocol68#PACKET_BACKUP} records) to the level's first snapshot and report its player's {@code
   * clientNum}, or 0 when none follows. A failure while decoding ahead is reported after the
   * records before it.
   */
  private DecodedRecord resolveClientNumber(DecodedRecord record) {
    int index = gameStateIndex(record.message());
    if (index < 0) return record;
    var level = (GameState) record.message().operations().get(index);
    int client = clientNumber(record.message(), index + 1);
    while (client < 0 && pending.size() < Protocol68.PACKET_BACKUP) {
      Optional<DecodedRecord> next;
      try {
        next = decodeNext();
      } catch (IOException failure) {
        deferred = failure;
        break;
      }
      if (next.isEmpty()) break;
      pending.add(next.get());
      if (gameStateIndex(next.get().message()) >= 0) break;
      client = clientNumber(next.get().message(), 0);
    }
    var resolved =
        new GameState(
            level.commandSequence(),
            level.configstrings(),
            level.baselines(),
            Math.max(client, 0),
            level.checksumFeed());
    if (gameState == level) gameState = resolved;
    var operations = new ArrayList<>(record.message().operations());
    operations.set(index, resolved);
    return new DecodedRecord(
        record.sequence(),
        new ServerMessageCodec.Message(record.message().reliableAcknowledge(), operations));
  }

  private static int gameStateIndex(ServerMessageCodec.Message message) {
    var operations = message.operations();
    for (int i = operations.size() - 1; i >= 0; i--)
      if (operations.get(i) instanceof GameState) return i;
    return -1;
  }

  private static int clientNumber(ServerMessageCodec.Message message, int from) {
    var operations = message.operations();
    for (int i = from; i < operations.size(); i++)
      if (operations.get(i) instanceof Frame frame) {
        byte[] player = frame.current().player();
        int offset = PlayerDeltaCodec.CLIENT_NUM_OFFSET;
        return (player[offset] & 255)
            | (player[offset + 1] & 255) << 8
            | (player[offset + 2] & 255) << 16
            | player[offset + 3] << 24;
      }
    return -1;
  }

  private Optional<DecodedRecord> decodeNext() throws IOException {
    if (failed) throw new IOException("Demo reader is closed or failed");
    try {
      int length = records.readInto(payload);
      if (length < 0) return Optional.empty();
      int sequence = records.sequence();
      reader.reset(payload, 0, length);
      var decoded =
          variant != null
              ? playback
                  ? LegacyServerMessageCodec.readForPlayback(
                      reader, sequence, baselines, history::get, variant)
                  : LegacyServerMessageCodec.read(
                      reader, sequence, baselines, history::get, variant)
              : playback
                  ? ServerMessageCodec.readForPlayback(reader, sequence, baselines, history::get)
                  : ServerMessageCodec.read(reader, sequence, baselines, history::get);
      apply(decoded);
      return Optional.of(new DecodedRecord(sequence, decoded));
    } catch (IOException failure) {
      failed = true;
      throw failure;
    } catch (IllegalArgumentException failure) {
      failed = true;
      throw new IOException(
          "Invalid protocol-"
              + protocol.number()
              + " demo message at record "
              + records.recordsRead()
              + ": "
              + failure.getMessage(),
          failure);
    }
  }

  private void apply(ServerMessageCodec.Message decoded) {
    for (var operation : decoded.operations()) {
      if (operation instanceof GameState level) {
        gameState = level;
        if (protocol == DemoProtocol.Q3_1_27) variant = LegacyServerMessageCodec.variant(level);
        baselines = level.baselines();
        latest = null;
        history.clear();
      } else if (operation instanceof Frame frame) {
        latest = frame.current();
        history.headMap(latest.sequence() - Protocol68.PACKET_BACKUP, true).clear();
        history.put(latest.sequence(), latest);
        while (history.size() > Protocol68.PACKET_BACKUP) history.pollFirstEntry();
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      records.close();
    }
  }
}

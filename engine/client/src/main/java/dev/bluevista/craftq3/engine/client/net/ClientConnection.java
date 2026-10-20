package dev.bluevista.craftq3.engine.client.net;

import dev.bluevista.craftq3.engine.client.pipeline.RemoteServerClock;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.client.pipeline.UserCmdHistory;
import dev.bluevista.craftq3.engine.common.info.InfoString;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.chan.Protocol68ClientSession;
import dev.bluevista.craftq3.engine.net.chan.Protocol68Handshake;
import dev.bluevista.craftq3.engine.net.msg.CommandTokens;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.oob.ConnectionlessMessage;
import dev.bluevista.craftq3.engine.net.oob.Protocol68Datagram;
import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.vmabi.layout.UiClientStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The client side of one protocol-68 connection over a {@link PacketTransport}: a UDP socket for a
 * remote server, or a {@code LoopbackTransport} end for the local one. Caller-driven and
 * non-blocking: {@link #pump} receives and sends, nothing else touches the transport.
 *
 * <h2>States ({@code connstate_t})</h2>
 *
 * {@link State#CHALLENGING} ({@code getchallenge} every {@code retryMillis}) → {@link
 * State#CONNECTING} ({@code connect} with the userinfo) → {@link State#CONNECTED} (awaiting the
 * gamestate; a keepalive a second) → {@link State#LOADING} (gamestate received: plan downloads,
 * load the cgame) → {@link State#PRIMED} ({@link #cgameLoaded}; user commands flow) → {@link
 * State#ACTIVE} (the clock activated on a snapshot). {@link State#DISCONNECTED} is terminal and
 * {@link #diagnostic()} says why: a timeout (no handshake reply within {@code connectTimeout}, or
 * no valid packet for {@code cl_timeout} seconds), the server's {@code disconnect}, a download or
 * I/O failure, or {@link #disconnect}.
 *
 * <h2>Messages</h2>
 *
 * Every accepted message goes to the {@link SnapshotPipeline} with its clear payload (for demo
 * recording) and snapshot pings; snapshots also drive the {@link RemoteServerClock}. User commands
 * come from the {@link UserCmdHistory}: each packet carries every command since the packet {@code
 * cl_packetdup} packets back (at most 32), and packets are sent at most {@code cl_maxpackets} a
 * second (more often when a reliable command is waiting). Owned by the render thread.
 */
public final class ClientConnection implements AutoCloseable {
  /** {@code connstate_t} values the connection itself passes through. */
  public enum State {
    CHALLENGING(UiClientStateLayout.CA_CHALLENGING),
    CONNECTING(UiClientStateLayout.CA_CONNECTING),
    CONNECTED(UiClientStateLayout.CA_CONNECTED),
    LOADING(UiClientStateLayout.CA_LOADING),
    PRIMED(UiClientStateLayout.CA_PRIMED),
    ACTIVE(UiClientStateLayout.CA_ACTIVE),
    DISCONNECTED(UiClientStateLayout.CA_DISCONNECTED);

    private final int connState;

    State(int connState) {
      this.connState = connState;
    }

    /** The {@code connstate_t} value for {@code uiClientState_t}. */
    public int connState() {
      return connState;
    }
  }

  /** Scheduling policy. */
  public record Settings(long retryMillis, long connectTimeoutMillis, int maxReceive, int maxSend) {
    public static final Settings DEFAULT = new Settings(3000, 15000, 64, 16);

    public Settings {
      if (retryMillis < 1 || connectTimeoutMillis < retryMillis || maxReceive < 1 || maxSend < 1)
        throw new IllegalArgumentException("Invalid connection settings");
    }
  }

  /** Session callbacks. */
  public interface Listener {
    /** A gamestate arrived and all its content is present: load the cgame for it. */
    default void levelReady(ServerMessageCodec.GameState gameState) {}

    /** Text the server printed ({@code print} packets). */
    default void print(String text) {}

    /** The connection reached {@link State#DISCONNECTED}. */
    default void disconnected(String reason) {}
  }

  private static final Log LOG = Log.get(ClientConnection.class);
  private static final int KEEPALIVE_MILLIS = 1000;
  private static final int MAX_SENDS_TRACKED = 8;

  private final PacketTransport transport;
  private final SocketAddress peer;
  private final String serverName;
  private final Protocol68Handshake handshake;
  private final SnapshotPipeline pipeline;
  private final UserCmdHistory commands;
  private final CvarTable cvars;
  private final Settings settings;
  private final Listener listener;
  private final RemoteServerClock clock = new RemoteServerClock();
  private final SnapshotPingHistory pings = new SnapshotPingHistory();
  private final byte[] buffer = new byte[Protocol68.MAX_MSGLEN + 1];
  private final ArrayDeque<Integer> sentCommandNumbers = new ArrayDeque<>();
  private DownloadManager downloads;
  private Protocol68ClientSession session;
  private State state = State.CHALLENGING;
  private String diagnostic = "";
  private byte[] pending;
  private long started, now, lastReceive, lastSend = Long.MIN_VALUE / 2;
  private boolean urgent = true;
  private int handshakePackets;
  private int levelSequence = -1;
  private int queuedCommandTime;
  private long messageFirstSend = -1;

  /**
   * @param serverName the address as the user typed it (shown by the ui)
   * @param userInfo the {@code connect} userinfo (the userinfo cvars)
   * @param nonce the client challenge nonce; {@code qport} the client's port id
   */
  public ClientConnection(
      PacketTransport transport,
      SocketAddress peer,
      String serverName,
      Map<String, String> userInfo,
      int nonce,
      int qport,
      SnapshotPipeline pipeline,
      UserCmdHistory commands,
      CvarTable cvars,
      Settings settings,
      Listener listener,
      long startMillis) {
    this.transport = Objects.requireNonNull(transport);
    this.peer = Objects.requireNonNull(peer);
    this.serverName = Objects.requireNonNull(serverName);
    this.handshake = new Protocol68Handshake(nonce, qport, userInfo);
    this.pipeline = Objects.requireNonNull(pipeline);
    this.commands = Objects.requireNonNull(commands);
    this.cvars = Objects.requireNonNull(cvars);
    this.settings = Objects.requireNonNull(settings);
    this.listener = Objects.requireNonNull(listener);
    started = now = lastReceive = startMillis;
    pipeline.reset();
    commands.reset();
  }

  /** Enables PK3 downloads for this connection. */
  public void setDownloads(DownloadManager manager) {
    downloads = manager;
  }

  public State state() {
    return state;
  }

  public String diagnostic() {
    return diagnostic;
  }

  public SocketAddress peer() {
    return peer;
  }

  public RemoteServerClock clock() {
    return clock;
  }

  /** The established session, once connected. */
  public java.util.Optional<Protocol68ClientSession> session() {
    return java.util.Optional.ofNullable(session);
  }

  /** {@code uiClientState_t} for the ui's connect screen. */
  public UiClientStateLayout.UiClientState uiState() {
    int clientNum = pipeline.gameState().map(ServerMessageCodec.GameState::clientNumber).orElse(-1);
    String message = state == State.DISCONNECTED ? diagnostic : handshake.print();
    return new UiClientStateLayout.UiClientState(
        state.connState(),
        handshakePackets,
        clientNum,
        serverName,
        pipeline.configString(Protocol68.CS_SERVERINFO),
        message);
  }

  // ---------------------------------------------------------------- session control

  /** Queues a reliable client command ({@code CL_AddReliableCommand}). */
  public void command(String text) {
    if (session == null || state == State.DISCONNECTED) return;
    try {
      session.command(text);
      urgent = true;
    } catch (IllegalStateException full) {
      terminate("Client command overflow: " + full.getMessage());
    }
  }

  /** The cgame consumed a systeminfo: present its {@code sv_serverid} from now on. */
  public void presentServerId(String systemInfo) {
    if (session == null || session.gameState().isEmpty()) return;
    try {
      String id = InfoString.parse(systemInfo, Protocol68.BIG_INFO_STRING).get("sv_serverid");
      if (id != null) session.usePresentedServerId(Integer.parseInt(id.strip()));
    } catch (IllegalArgumentException malformed) {
      LOG.warnOnce("serverid", "Malformed sv_serverid in systeminfo");
    }
  }

  /** {@code CL_SendPureChecksums}: {@code cp <serverId> <checksums>}. */
  public void sendPureChecksums(String referencedPureChecksums) {
    if (session == null) return;
    command("cp " + session.serverId() + " " + referencedPureChecksums);
  }

  /** The cgame finished {@code CG_INIT}: user commands start flowing. */
  public void cgameLoaded() {
    if (state == State.LOADING) {
      state = State.PRIMED;
      urgent = true;
    }
  }

  /** Records keep requesting independent snapshots (a demo recorder is waiting for one). */
  public void requestFullSnapshots(boolean value) {
    if (session != null) session.fullSnapshots(value);
  }

  /**
   * The cgame time for this frame, adjusting the server-time offset ({@code CL_SetCGameTime}), or
   * empty while no snapshot has activated the clock. Activation moves a primed connection to {@link
   * State#ACTIVE}.
   */
  public OptionalInt serverTime(int realtime) {
    if (state != State.PRIMED && state != State.ACTIVE) return OptionalInt.empty();
    float timescale = cvars.value("timescale");
    OptionalInt time = clock.frame(realtime, cvars.integer("cl_timeNudge"), timescale);
    if (time.isPresent() && state == State.PRIMED) state = State.ACTIVE;
    return time;
  }

  /** Sends {@code disconnect} (three times, as the original does) and closes. */
  public void disconnect(long nowMillis) {
    if (state == State.DISCONNECTED) return;
    if (session != null) {
      try {
        session.command("disconnect");
        for (int i = 0; i < 3 && !session.hasPendingPacket(); i++) {
          session.queue(List.of());
          drain();
        }
      } catch (IOException | RuntimeException failure) {
        LOG.debug("Disconnect send failed: " + failure);
      }
    }
    terminate("Disconnected");
  }

  // ---------------------------------------------------------------- pump

  /** Receives and sends for one frame. */
  public void pump(long nowMillis) {
    if (state == State.DISCONNECTED) return;
    now = Math.max(now, nowMillis);
    try {
      receive();
      if (state == State.DISCONNECTED) return;
      expire();
      if (state == State.DISCONNECTED) return;
      if (pending != null || (session != null && session.hasPendingPacket())) drain();
      if (pending == null && (session == null || !session.hasPendingPacket()) && due()) send();
    } catch (IOException failure) {
      terminate("Network error: " + failure.getMessage());
    }
  }

  private void receive() throws IOException {
    for (int i = 0; i < settings.maxReceive() && state != State.DISCONNECTED; i++) {
      int length = transport.poll(buffer);
      if (length == PacketTransport.NONE) return;
      if (length < 0) continue;
      if (!peer.equals(transport.lastSource())) continue;
      byte[] datagram = java.util.Arrays.copyOf(buffer, length);
      Protocol68Datagram.Kind kind = Protocol68Datagram.classify(datagram);
      if (kind == Protocol68Datagram.Kind.MALFORMED) continue;
      if (kind == Protocol68Datagram.Kind.CONNECTIONLESS) {
        connectionless(datagram);
        continue;
      }
      if (session == null) continue;
      Protocol68ClientSession.Received received = session.receive(datagram);
      switch (received.status()) {
        case ACCEPTED -> accepted(received);
        case PARTIAL -> lastReceive = now;
        case REJECTED -> LOG.debug("Rejected server packet: " + received.diagnostic());
        case DROPPED, IGNORED -> {}
      }
    }
  }

  private void connectionless(byte[] datagram) {
    if (session == null) {
      Protocol68Handshake.Result result;
      try {
        result = handshake.receive(datagram);
      } catch (IllegalArgumentException unencodable) {
        terminate("Cannot send the connect request: " + unencodable.getMessage());
        return;
      }
      switch (result) {
        case CHALLENGE_ACCEPTED -> {
          lastReceive = now;
          state = State.CONNECTING;
          urgent = true;
        }
        case CONNECTED -> {
          lastReceive = now;
          session = new Protocol68ClientSession(handshake.challenge(), handshake.qport());
          state = State.CONNECTED;
          urgent = true;
        }
        case PRINT -> {
          lastReceive = now;
          listener.print(handshake.print());
        }
        case IGNORED -> {}
      }
      return;
    }
    try {
      ConnectionlessMessage message = ConnectionlessMessage.parse(datagram);
      String command = CommandTokens.tokenize(message.line()).argument(0);
      if (command.equals("print")) {
        listener.print(new String(message.body(), StandardCharsets.ISO_8859_1));
      } else if (command.equals("disconnect")) {
        terminate("Server disconnected");
      }
    } catch (IllegalArgumentException malformed) {
      // Untrusted out-of-band text is never executed.
    }
  }

  private void accepted(Protocol68ClientSession.Received received) throws IOException {
    lastReceive = now;
    int sequence =
        received.demoRecord().map(r -> r.sequence()).orElse(session.messageAcknowledge());
    byte[] payload = received.demoRecord().map(r -> r.payload()).orElse(null);
    for (ServerMessageCodec.Operation operation : received.message().operations()) {
      if (operation instanceof ServerMessageCodec.GameState) {
        pings.clearLevel();
        clock.reset();
        sentCommandNumbers.clear();
      } else if (operation instanceof ServerMessageCodec.Frame frame) {
        int commandTime =
            ByteBuffer.wrap(frame.current().player()).order(ByteOrder.LITTLE_ENDIAN).getInt();
        pings.received(frame.current().sequence(), commandTime, (int) (now - started));
      }
    }
    pipeline.accept(sequence, received.message(), payload, pings::snapshotPing);
    for (ServerMessageCodec.Operation operation : received.message().operations()) {
      switch (operation) {
        case ServerMessageCodec.Frame frame ->
            clock.snapshot(frame.current().time(), frame.current().flags());
        case ServerMessageCodec.Download download -> {
          if (downloads != null) {
            try {
              downloads.accept(download);
            } catch (IOException failure) {
              terminate("Download failed: " + failure.getMessage());
              return;
            }
          }
        }
        default -> {}
      }
    }
    int generation = session.gameStateMessageSequence();
    if (generation != levelSequence) {
      levelSequence = generation;
      state = State.LOADING;
      urgent = true;
      level();
    }
  }

  private void level() {
    ServerMessageCodec.GameState gameState = pipeline.gameState().orElseThrow();
    if (downloads != null) {
      try {
        if (downloads.plan(gameState, (int) (now - started)) == DownloadManager.Outcome.DOWNLOADING)
          return;
      } catch (IOException failure) {
        terminate(failure.getMessage());
        return;
      }
    }
    listener.levelReady(gameState);
  }

  private void expire() {
    if (session == null) {
      if (now - started >= settings.connectTimeoutMillis()) terminate("Server did not respond");
      return;
    }
    long timeout = Math.max(1, (long) (cvars.value("cl_timeout") * 1000));
    if (now - lastReceive >= timeout) terminate("Server connection timed out");
  }

  private boolean due() {
    long interval;
    if (session == null) interval = settings.retryMillis();
    else if (state == State.PRIMED || state == State.ACTIVE) {
      int maxPackets = Math.clamp(cvars.integer("cl_maxpackets"), 15, 125);
      interval = 1000 / maxPackets;
    } else interval = KEEPALIVE_MILLIS;
    return urgent || now - lastSend >= interval;
  }

  private void send() throws IOException {
    urgent = false;
    if (session == null) {
      pending = handshake.request();
      handshakePackets++;
      drain();
      return;
    }
    List<byte[]> movement = List.of();
    queuedCommandTime = 0;
    if ((state == State.PRIMED || state == State.ACTIVE) && session.gameState().isPresent()) {
      int newest = commands.current();
      int dup = Math.clamp(cvars.integer("cl_packetdup"), 0, 5);
      int oldest = newest;
      List<Integer> sent = new ArrayList<>(sentCommandNumbers);
      oldest = sent.size() > dup ? sent.get(sent.size() - 1 - dup) : 0;
      int count = Math.min(newest - oldest, Protocol68.MAX_PACKET_USERCMDS);
      List<byte[]> batch = new ArrayList<>(Math.max(0, count));
      for (int n = newest - count + 1; n <= newest; n++)
        commands.get(n).map(UserCmd::toCanonical).ifPresent(batch::add);
      if (!batch.isEmpty()) {
        movement = batch;
        queuedCommandTime = UserCmd.fromCanonical(batch.getLast()).serverTime();
      }
      sentCommandNumbers.addLast(newest);
      while (sentCommandNumbers.size() > MAX_SENDS_TRACKED) sentCommandNumbers.removeFirst();
    }
    session.queue(movement);
    messageFirstSend = -1;
    drain();
  }

  private void drain() throws IOException {
    for (int i = 0; i < settings.maxSend(); i++) {
      if (pending == null && session != null) pending = session.pollPacket().orElse(null);
      if (pending == null) return;
      if (!transport.send(peer, pending, 0, pending.length)) return;
      if (session != null) {
        if (messageFirstSend < 0) messageFirstSend = now;
        if (!session.hasPendingPacket()) {
          int sequence =
              ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN).getInt() & Integer.MAX_VALUE;
          pings.sent(sequence, queuedCommandTime, (int) (messageFirstSend - started));
        }
      }
      pending = null;
      lastSend = now;
    }
  }

  private void terminate(String reason) {
    if (state == State.DISCONNECTED) return;
    diagnostic = reason;
    state = State.DISCONNECTED;
    pending = null;
    if (downloads != null) {
      try {
        downloads.close();
      } catch (IOException ignored) {
        LOG.debug("Download cleanup failed");
      }
    }
    listener.disconnected(reason);
  }

  /** Closes the transport (the connection is unusable afterwards). */
  @Override
  public void close() throws IOException {
    terminate("Disconnected");
    transport.close();
  }

  /** Text printed to the console when a connect attempt starts. */
  public static Consumer<String> printer(Consumer<String> output) {
    return text -> {
      for (String line : text.split("\n")) if (!line.isEmpty()) output.accept(line);
    };
  }
}

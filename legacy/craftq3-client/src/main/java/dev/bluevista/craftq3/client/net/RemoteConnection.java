package dev.bluevista.craftq3.client.net;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.net.ConnectionlessMessage;
import dev.bluevista.craftq3.core.net.Protocol68ClientSession;
import dev.bluevista.craftq3.core.net.Protocol68Datagram;
import dev.bluevista.craftq3.core.net.Protocol68Handshake;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.platform.net.UdpTransport;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-driven protocol-68 networking. Confined to one caller thread; no waits or worker threads.
 */
public final class RemoteConnection implements AutoCloseable {
  public enum State {
    CHALLENGING,
    CONNECTING,
    CONNECTED,
    TIMED_OUT,
    FAILED,
    CLOSED
  }

  public enum EventKind {
    STATE,
    PRINT,
    MESSAGE,
    LEVEL,
    REJECTED,
    TIMEOUT,
    IO_ERROR
  }

  /**
   * A MESSAGE contains the accepted wire operations, including each Frame, at this arrival time.
   */
  public record Event(
      EventKind kind,
      long arrivalMillis,
      String diagnostic,
      Protocol68ClientSession.Received received) {
    public Event {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(diagnostic);
      if ((kind == EventKind.MESSAGE) != (received != null))
        throw new IllegalArgumentException("Only message events carry wire operations");
    }
  }

  public record PumpResult(
      State state, int receivedDatagrams, int sentDatagrams, List<Event> events) {
    public PumpResult {
      events = List.copyOf(events);
    }
  }

  /** Scheduling policy for this adapter, not a claim of native engine frame cadence. */
  public record Settings(
      long retryMillis,
      long connectTimeoutMillis,
      long inactivityTimeoutMillis,
      long sendIntervalMillis,
      int maxReceiveDatagrams,
      int maxSendDatagrams) {
    public static final Settings DEFAULT = new Settings(3000, 15000, 30000, 50, 32, 16);

    public Settings {
      if (retryMillis < 1
          || retryMillis > 60000
          || connectTimeoutMillis < retryMillis
          || connectTimeoutMillis > 3600000
          || inactivityTimeoutMillis < 1
          || inactivityTimeoutMillis > 3600000
          || sendIntervalMillis < 1
          || sendIntervalMillis > 60000
          || maxReceiveDatagrams < 1
          || maxReceiveDatagrams > 256
          || maxSendDatagrams < 1
          || maxSendDatagrams > 256)
        throw new IllegalArgumentException("Invalid remote connection limits");
    }
  }

  /**
   * Injected endpoints must be nonblocking and already filter to exactly peer(). Successful sends
   * consume the whole datagram; false sends consume nothing and retain no caller buffer.
   * Construction transfers endpoint ownership to this connection only on success.
   */
  public interface Endpoint extends AutoCloseable {
    InetSocketAddress peer();

    InetSocketAddress localAddress() throws IOException;

    UdpTransport.Poll poll() throws IOException;

    boolean send(byte[] payload) throws IOException;

    @Override
    void close() throws IOException;
  }

  private final Endpoint endpoint;
  private final Protocol68Handshake handshake;
  private final Settings settings;
  private final long started;
  private final ArrayDeque<byte[]> inputs = new ArrayDeque<>();
  private final SnapshotPingHistory pings = new SnapshotPingHistory();
  private Protocol68ClientSession session;
  private State state = State.CHALLENGING;
  private byte[] pending;
  private long now, lastActivity, lastSend;
  private boolean sentBefore, urgent = true, disconnecting, endpointClosed;
  private int inputTime, levelSequence, lastSnapshotSequence;
  private int queuedCommandTime;
  private long messageFirstSend = -1;
  private long lastSnapshotArrivalMillis = -1;
  private String diagnostic = "";

  public static RemoteConnection open(
      InetSocketAddress peer, Map<String, String> userInfo, int nonce, int qport, long startMillis)
      throws IOException {
    return open(peer, userInfo, nonce, qport, startMillis, Settings.DEFAULT);
  }

  public static RemoteConnection open(
      InetSocketAddress peer,
      Map<String, String> userInfo,
      int nonce,
      int qport,
      long startMillis,
      Settings settings)
      throws IOException {
    // Validate all caller settings before acquiring a socket.
    Objects.requireNonNull(settings);
    if (startMillis < 0) throw new IllegalArgumentException("Negative monotonic start time");
    new Protocol68Handshake(nonce, qport, userInfo);
    var udp = UdpTransport.open(peer);
    try {
      return new RemoteConnection(
          new Endpoint() {
            public InetSocketAddress peer() {
              return udp.peer();
            }

            public InetSocketAddress localAddress() throws IOException {
              return udp.localAddress();
            }

            public UdpTransport.Poll poll() throws IOException {
              return udp.poll();
            }

            public boolean send(byte[] bytes) throws IOException {
              return udp.send(bytes);
            }

            public void close() throws IOException {
              udp.close();
            }
          },
          userInfo,
          nonce,
          qport,
          startMillis,
          settings);
    } catch (RuntimeException | Error failure) {
      try {
        udp.close();
      } catch (IOException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  public RemoteConnection(
      Endpoint endpoint,
      Map<String, String> userInfo,
      int nonce,
      int qport,
      long startMillis,
      Settings settings) {
    this.endpoint = Objects.requireNonNull(endpoint);
    InetSocketAddress peer = Objects.requireNonNull(endpoint.peer());
    if (peer.isUnresolved()
        || peer.getPort() == 0
        || peer.getAddress().isAnyLocalAddress()
        || peer.getAddress().isMulticastAddress())
      throw new IllegalArgumentException("Remote peer must be a resolved unicast address and port");
    if (startMillis < 0) throw new IllegalArgumentException("Negative monotonic start time");
    this.settings = Objects.requireNonNull(settings);
    handshake = new Protocol68Handshake(nonce, qport, userInfo);
    started = now = lastActivity = startMillis;
  }

  public State state() {
    return state;
  }

  public String diagnostic() {
    return diagnostic;
  }

  public InetSocketAddress peer() {
    return endpoint.peer();
  }

  public InetSocketAddress localAddress() throws IOException {
    return endpoint.localAddress();
  }

  /**
   * Borrow for cgame state and reliable commands. This pump exclusively owns queue/poll/receive.
   */
  public Optional<Protocol68ClientSession> session() {
    return Optional.ofNullable(session);
  }

  public int lastSnapshotSequence() {
    return lastSnapshotSequence;
  }

  /** -1 until a Frame has arrived in the current gamestate generation. */
  public long lastSnapshotArrivalMillis() {
    return lastSnapshotArrivalMillis;
  }

  /**
   * Ping retained for this snapshot message; absent/expired metadata returns the native default999.
   */
  public int snapshotPing(int messageNumber) {
    return pings.snapshotPing(messageNumber);
  }

  /**
   * Keeps the newest 32 inputs. Equal timestamps replace the last sample. A backward clock
   * correction clears this redundancy deque; the separate cgame user-command history is untouched.
   */
  public void userCommand(UserCommand command) {
    Objects.requireNonNull(command);
    if (state != State.CONNECTED || disconnecting || session.gameState().isEmpty())
      throw new IllegalStateException("Remote movement requires an active gamestate");
    if (!inputs.isEmpty()) {
      if (command.serverTime() < inputTime) clearInput();
      else if (command.serverTime() == inputTime) inputs.removeLast();
    }
    inputTime = command.serverTime();
    ByteBuffer bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    bytes
        .putInt(command.serverTime())
        .putInt(command.pitch() & 65535)
        .putInt(command.yaw() & 65535)
        .putInt(command.roll() & 65535)
        .putInt(command.buttons() & 65535)
        .put((byte) command.weapon())
        .put((byte) Math.max(-127, command.forward()))
        .put((byte) Math.max(-127, command.right()))
        .put((byte) Math.max(-127, command.up()));
    inputs.addLast(bytes.array());
    while (inputs.size() > 32) inputs.removeFirst();
  }

  /** Queues the reliable disconnect once. Further pumps retransmit it; this method never waits. */
  public boolean disconnect() {
    if (disconnecting) return true;
    if (state != State.CONNECTED) return false;
    session.command("disconnect");
    disconnecting = true;
    clearInput();
    urgent = true;
    return true;
  }

  /** One bounded attempt to flush a reliable disconnect, then immediate close, even on failure. */
  public PumpResult disconnectAndClose(long nowMillis) throws IOException {
    var events = new ArrayList<Event>();
    try {
      try {
        disconnect();
      } catch (IllegalStateException full) {
        events.add(new Event(EventKind.REJECTED, nowMillis, full.getMessage(), null));
      }
      var result = pump(nowMillis);
      events.addAll(result.events());
      return new PumpResult(
          State.CLOSED, result.receivedDatagrams(), result.sentDatagrams(), events);
    } finally {
      close();
    }
  }

  public PumpResult pump(long nowMillis) {
    if (nowMillis < now) throw new IllegalArgumentException("Remote clock moved backward");
    now = nowMillis;
    var work = new Work();
    if (!active()) return work.result();
    expire(work);
    if (!active()) return work.result();
    try {
      // A polled final fragment advances the session's acknowledgement ceiling. Do not receive
      // its acknowledgement until the socket has accepted it and all prior fragments in order.
      flush(work);
      if (!outgoing()) {
        receive(work);
        expire(work);
        if (active() && work.sent < settings.maxSendDatagrams() && due()) {
          if (session == null) pending = handshake.request();
          else {
            var movement = disconnecting ? List.<byte[]>of() : List.copyOf(inputs);
            session.queue(movement);
            queuedCommandTime =
                movement.isEmpty()
                    ? 0
                    : ByteBuffer.wrap(movement.getLast()).order(ByteOrder.LITTLE_ENDIAN).getInt();
            messageFirstSend = -1;
          }
          urgent = false;
          flush(work);
        }
      } else expire(work);
    } catch (IOException failure) {
      terminate(State.FAILED, EventKind.IO_ERROR, failure.toString(), work);
    }
    return work.result();
  }

  private boolean active() {
    return state == State.CHALLENGING || state == State.CONNECTING || state == State.CONNECTED;
  }

  private boolean outgoing() {
    return pending != null || session != null && session.hasPendingPacket();
  }

  private boolean due() {
    return urgent
        || !sentBefore
        || now - lastSend
            >= (session == null ? settings.retryMillis() : settings.sendIntervalMillis());
  }

  private void flush(Work work) throws IOException {
    while (work.sent < settings.maxSendDatagrams()) {
      if (pending == null && session != null) pending = session.pollPacket().orElse(null);
      if (pending == null) return;
      if (!endpoint.send(pending)) return;
      if (session != null) {
        if (messageFirstSend < 0) messageFirstSend = now;
        if (!session.hasPendingPacket()) {
          int sequence =
              ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN).getInt() & Integer.MAX_VALUE;
          pings.sent(sequence, queuedCommandTime, (int) (messageFirstSend - started));
        }
      }
      pending = null;
      work.sent++;
      lastSend = now;
      sentBefore = true;
    }
  }

  private void receive(Work work) throws IOException {
    for (int count = 0; count < settings.maxReceiveDatagrams(); count++) {
      var packet = endpoint.poll();
      if (packet.status() == UdpTransport.Status.EMPTY) break;
      work.received++;
      if (packet.status() != UdpTransport.Status.PACKET) {
        work.event(EventKind.REJECTED, packet.status().name(), null);
        continue;
      }
      byte[] payload = packet.payload();
      var kind = Protocol68Datagram.classify(payload);
      if (kind == Protocol68Datagram.Kind.MALFORMED) {
        work.event(EventKind.REJECTED, "Malformed datagram envelope", null);
      } else if (session == null) {
        var result = handshake.receive(payload);
        if (result != Protocol68Handshake.Result.IGNORED) lastActivity = now;
        switch (result) {
          case CHALLENGE_ACCEPTED -> {
            state = State.CONNECTING;
            urgent = true;
            work.event(EventKind.STATE, "Challenge accepted", null);
          }
          case CONNECTED -> {
            session = new Protocol68ClientSession(handshake.challenge(), handshake.qport());
            state = State.CONNECTED;
            urgent = true;
            work.event(EventKind.STATE, "Connected; awaiting gamestate", null);
          }
          case PRINT -> work.event(EventKind.PRINT, handshake.print(), null);
          case IGNORED -> {}
        }
      } else if (kind == Protocol68Datagram.Kind.CONNECTIONLESS) {
        print(payload, work);
      } else {
        var received = session.receive(payload);
        switch (received.status()) {
          case ACCEPTED -> {
            lastActivity = now;
            int generation = session.gameStateMessageSequence();
            if (generation != levelSequence) {
              levelSequence = generation;
              clearInput();
              lastSnapshotSequence = 0;
              lastSnapshotArrivalMillis = -1;
              urgent = true;
              work.event(EventKind.LEVEL, "Gamestate " + generation, null);
            }
            for (var operation : received.message().operations()) {
              if (operation instanceof ServerMessageCodec.GameState) {
                pings.clearLevel();
                lastSnapshotSequence = 0;
                lastSnapshotArrivalMillis = -1;
              } else if (operation instanceof ServerMessageCodec.Frame frame) {
                int commandTime =
                    ByteBuffer.wrap(frame.current().player())
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();
                pings.received(frame.current().sequence(), commandTime, (int) (now - started));
                lastSnapshotSequence = frame.current().sequence();
                lastSnapshotArrivalMillis = now;
              }
            }
            work.event(EventKind.MESSAGE, "", received);
          }
          case PARTIAL -> lastActivity = now;
          case REJECTED -> work.event(EventKind.REJECTED, received.diagnostic(), null);
          case IGNORED -> {}
        }
      }
    }
  }

  private void print(byte[] payload, Work work) {
    try {
      var envelope = ConnectionlessMessage.parse(payload);
      if (CommandParser.tokenize(envelope.line()).argument(0).equals("print")) {
        lastActivity = now;
        work.event(EventKind.PRINT, new String(envelope.body(), StandardCharsets.ISO_8859_1), null);
      }
    } catch (IllegalArgumentException malformed) {
      work.event(EventKind.REJECTED, malformed.getMessage(), null);
    }
  }

  private void clearInput() {
    inputs.clear();
    inputTime = 0;
  }

  private void expire(Work work) {
    if (session == null && now - started >= settings.connectTimeoutMillis())
      terminate(State.TIMED_OUT, EventKind.TIMEOUT, "Challenge/connect deadline exceeded", work);
    else if (session != null && now - lastActivity >= settings.inactivityTimeoutMillis())
      terminate(
          State.TIMED_OUT, EventKind.TIMEOUT, "No valid server activity before timeout", work);
  }

  private void terminate(State terminal, EventKind kind, String reason, Work work) {
    diagnostic = reason;
    state = terminal;
    clearInput();
    pending = null;
    work.event(kind, reason, null);
    try {
      releaseEndpoint();
    } catch (IOException closeFailure) {
      work.event(EventKind.IO_ERROR, closeFailure.toString(), null);
    }
  }

  private void releaseEndpoint() throws IOException {
    if (!endpointClosed) {
      endpointClosed = true;
      endpoint.close();
    }
  }

  @Override
  public void close() throws IOException {
    state = State.CLOSED;
    clearInput();
    pending = null;
    releaseEndpoint();
  }

  private final class Work {
    private int received, sent;
    private final List<Event> events = new ArrayList<>();

    void event(EventKind kind, String text, Protocol68ClientSession.Received value) {
      events.add(new Event(kind, now, text, value));
    }

    PumpResult result() {
      return new PumpResult(state, received, sent, events);
    }
  }
}

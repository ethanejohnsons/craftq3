package dev.bluevista.craftq3.engine.session;

import dev.bluevista.craftq3.engine.client.demo.DemoPlayback;
import dev.bluevista.craftq3.engine.client.demo.DemoRecorder;
import dev.bluevista.craftq3.engine.client.demo.Protocol68DemoRecorder;
import dev.bluevista.craftq3.engine.client.host.CgameHost;
import dev.bluevista.craftq3.engine.client.host.HostServices;
import dev.bluevista.craftq3.engine.client.net.ClientConnection;
import dev.bluevista.craftq3.engine.client.pipeline.ClientSnapshot;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.client.pipeline.UserCmdHistory;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.vmabi.layout.UiClientStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One client game: a connection (to the local server over loopback or to a remote server over UDP)
 * or a demo, feeding the {@link SnapshotPipeline} the cgame reads, plus the cgame itself and an
 * optional demo recording. The session drives it once per frame; failures surface as {@link Drop}
 * exceptions the session turns into {@code ERR_DROP}.
 *
 * <p>Owned by the client thread.
 */
final class GameClient implements AutoCloseable {
  private static final dev.bluevista.craftq3.engine.common.log.Log LOG =
      dev.bluevista.craftq3.engine.common.log.Log.get(GameClient.class);

  /** Where the snapshots come from. */
  enum Kind {
    LOCAL,
    REMOTE,
    DEMO
  }

  /** An error that ends the game ({@code ERR_DROP} or a server disconnect). */
  static final class Drop extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Drop(String message) {
      super(message);
    }

    Drop(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** What the game needs from the session. */
  interface Session {
    HostServices services();

    void print(String line);

    /**
     * A new level's gamestate arrived; prepare content (pure packs) before the cgame loads, and
     * start any work that can run ahead of it (the world preload).
     */
    void levelStarting(GameClient game, ServerMessageCodec.GameState gameState);

    /** True while the prepared level should wait before its cgame starts (a preload running). */
    default boolean levelWaiting(GameClient game) {
      return false;
    }

    /** The cgame consumed a new systeminfo (cheats, server id). */
    void systemInfo(GameClient game, String info);

    /** The pure checksums to answer with after the cgame loaded, or null for none. */
    String pureChecksums();
  }

  /** {@code playerState_t.delta_angles[PITCH]} in the canonical player state. */
  private static final int DELTA_PITCH_OFFSET = 56;

  final Kind kind;
  final String serverName;
  final SnapshotPipeline pipeline = new SnapshotPipeline();
  final UserCmdHistory commands = new UserCmdHistory();
  private final Session session;
  private CgameHost cgame;
  private ClientConnection connection;
  private DemoPlayback demo;
  private DemoRecorder recorder;
  private SessionFiles.Recording recordingFile;
  private String recordingName = "";
  private boolean levelPending;
  private boolean levelPrepared;
  private String serverDisconnect;
  private String connectionLost;
  private int lastServerTime;
  private boolean drawnSnapshot;
  private int levels;
  private long retiredDegradations;

  private GameClient(Kind kind, String serverName, Session session) {
    this.kind = kind;
    this.serverName = serverName;
    this.session = session;
  }

  /** A game over {@code connection} (built by {@link #connect}). */
  static GameClient network(
      Kind kind, String serverName, Session session, ConnectionFactory factory) {
    GameClient game = new GameClient(kind, serverName, session);
    game.connection = factory.open(game.pipeline, game.commands, game.listener());
    return game;
  }

  /** Builds the connection for a game; the game supplies its pipeline and listener. */
  @FunctionalInterface
  interface ConnectionFactory {
    ClientConnection open(
        SnapshotPipeline pipeline, UserCmdHistory commands, ClientConnection.Listener listener);
  }

  /** A game playing {@code playback} (already opened on {@link #pipeline}). */
  static GameClient demo(
      String name,
      Session session,
      java.util.function.Function<SnapshotPipeline, DemoPlayback> open)
      throws IOException {
    GameClient game = new GameClient(Kind.DEMO, name, session);
    game.demo = open.apply(game.pipeline);
    try {
      game.demo.prime();
    } catch (IOException | RuntimeException failure) {
      game.demo.close();
      throw failure;
    }
    game.levelPending = true;
    return game;
  }

  private ClientConnection.Listener listener() {
    return new ClientConnection.Listener() {
      @Override
      public void levelReady(ServerMessageCodec.GameState gameState) {
        levelPending = true;
        levelPrepared = false;
      }

      @Override
      public void print(String text) {
        for (String line : text.split("\n")) if (!line.isBlank()) session.print(line);
      }

      @Override
      public void disconnected(String reason) {
        connectionLost = reason;
      }
    };
  }

  private CgameHost.Link cgameLink() {
    return new CgameHost.Link() {
      @Override
      public void clientCommand(String text) {
        if (connection != null) connection.command(text);
      }

      @Override
      public boolean demoPlayback() {
        return kind == Kind.DEMO;
      }

      @Override
      public void serverDisconnected(String reason) {
        serverDisconnect = reason;
      }

      @Override
      public void systemInfo(String info) {
        if (connection != null) connection.presentServerId(info);
        if (kind != Kind.DEMO) session.systemInfo(GameClient.this, info);
      }
    };
  }

  // ---------------------------------------------------------------- state

  Optional<ClientConnection> connection() {
    return Optional.ofNullable(connection);
  }

  Optional<DemoPlayback> demo() {
    return Optional.ofNullable(demo);
  }

  Optional<CgameHost> cgame() {
    return Optional.ofNullable(cgame);
  }

  boolean cgameRunning() {
    return cgame != null && cgame.state() == CgameHost.State.RUNNING;
  }

  /** True once the cgame drew a snapshot of the current level. */
  boolean active() {
    return cgameRunning() && drawnSnapshot;
  }

  int levels() {
    return levels;
  }

  boolean recording() {
    return recorder != null;
  }

  String recordingName() {
    return recordingName;
  }

  /** Syscall degradations of every cgame this game ran (each level, {@code vid_restart}). */
  long degradations() {
    return retiredDegradations + (cgame == null ? 0 : cgame.degradationTotal());
  }

  /** {@code uiClientState_t} for the ui's connect screen. */
  UiClientStateLayout.UiClientState uiState() {
    if (connection != null) return connection.uiState();
    int clientNum = pipeline.gameState().map(ServerMessageCodec.GameState::clientNumber).orElse(-1);
    return new UiClientStateLayout.UiClientState(
        UiClientStateLayout.CA_ACTIVE,
        0,
        clientNum,
        serverName,
        pipeline.configString(Protocol68.CS_SERVERINFO),
        "");
  }

  /** The configstring the ui asks for: the cgame's view once it runs, else the wire view. */
  String configString(int index) {
    if (cgameRunning()) return cgame.configStrings().getOrDefault(index, "");
    return pipeline.configString(index);
  }

  // ---------------------------------------------------------------- frame

  /**
   * Network games: receives and sends, then starts the cgame for a new level. Throws {@link Drop}
   * when the connection ended.
   */
  void pump(long nowMillis) {
    if (connection == null) return;
    connection.pump(nowMillis);
    if (connectionLost != null) throw new Drop(connectionLost);
    if (levelPending) loadLevel();
  }

  /**
   * Loads the pending level in two steps: the first call ends the old cgame and prepares the level
   * ({@link Session#levelStarting}); the cgame starts on the first call (that frame or a later one)
   * once {@link Session#levelWaiting} no longer holds it back.
   */
  private void loadLevel() {
    ServerMessageCodec.GameState gameState =
        pipeline.gameState().orElseThrow(() -> new Drop("No gamestate to load"));
    if (!levelPrepared) {
      if (cgame != null) {
        cgame.shutdown();
        retiredDegradations += cgame.degradationTotal();
        cgame = null;
        session
            .services()
            .input()
            .setCgameTarget(dev.bluevista.craftq3.engine.client.input.KeyTarget.NONE);
      }
      commands.reset();
      drawnSnapshot = false;
      lastServerTime = 0;
      session.levelStarting(this, gameState);
      levelPrepared = true;
    }
    if (session.levelWaiting(this)) return;
    levelPending = false;
    levelPrepared = false;
    HostServices services = session.services();
    cgame = new CgameHost(services, pipeline, commands, cgameLink());
    services.input().setCgameTarget(cgame);
    long started = System.nanoTime();
    try {
      cgame.start();
    } catch (IOException missing) {
      throw new Drop("Couldn't load the cgame: " + missing.getMessage(), missing);
    } catch (RuntimeException failure) {
      throw failureDrop(failure);
    }
    LOG.info("cgame initialised in " + (System.nanoTime() - started) / 1_000_000 + " ms");
    levels++;
    if (connection != null) {
      connection.cgameLoaded();
      String pure = session.pureChecksums();
      if (pure != null) connection.sendPureChecksums(pure);
    }
  }

  /** The cgame time of this frame, or empty while none is known. */
  OptionalInt serverTime(int realtime, float timescale, int frameMillis, int wallMillis, Demo d)
      throws IOException {
    if (demo != null) {
      if (levelPending) loadLevel();
      OptionalInt time =
          demo.frame(frameMillis, d.timeNudge(), timescale, d.freeze(), d.timedemo(), wallMillis);
      if (!demo.ended() && cgame != null && cgame.stale() && pipeline.gameState().isPresent())
        loadLevel();
      return time;
    }
    if (connection == null || !cgameRunning()) return OptionalInt.empty();
    return connection.serverTime(realtime);
  }

  /** Demo-clock settings for {@link #serverTime}. */
  record Demo(int timeNudge, boolean freeze, boolean timedemo) {}

  /** Appends this frame's user command. */
  void addCommand(UserCmd command) {
    commands.add(command);
  }

  /** {@code delta_angles[PITCH]} of the newest snapshot, for pitch clamping. */
  int deltaPitch() {
    Optional<ClientSnapshot> latest = pipeline.latestSnapshot();
    if (latest.isEmpty()) return 0;
    byte[] player = latest.get().snapshot().player();
    if (player.length < DELTA_PITCH_OFFSET + 4) return 0;
    return ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN).getInt(DELTA_PITCH_OFFSET);
  }

  /** Draws the cgame at {@code serverTime} (or its information screen while primed). */
  void draw(OptionalInt serverTime) {
    if (!cgameRunning()) return;
    if (serverDisconnect != null) throw new Drop(disconnectMessage(serverDisconnect));
    int time = serverTime.orElse(lastServerTime);
    try {
      cgame.drawActiveFrame(time);
    } catch (RuntimeException failure) {
      if (serverDisconnect != null) throw new Drop(disconnectMessage(serverDisconnect), failure);
      throw failureDrop(failure);
    }
    if (serverTime.isPresent()) {
      lastServerTime = time;
      if (pipeline.latestSnapshot().isPresent()) drawnSnapshot = true;
    }
  }

  private static String disconnectMessage(String reason) {
    return reason.isEmpty() ? "Server disconnected" : "Server disconnected - " + reason;
  }

  static Drop failureDrop(RuntimeException failure) {
    if (failure instanceof Drop drop) return drop;
    String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
    return new Drop(message, failure);
  }

  /** The demo reached its end. */
  boolean demoEnded() {
    return demo != null && demo.ended();
  }

  /** Restarts the cgame on the same level ({@code vid_restart}). */
  void restartCgame() throws IOException {
    if (cgame == null || pipeline.gameState().isEmpty()) return;
    HostServices services = session.services();
    cgame.shutdown();
    retiredDegradations += cgame.degradationTotal();
    cgame = new CgameHost(services, pipeline, commands, cgameLink());
    services.input().setCgameTarget(cgame);
    cgame.start();
    drawnSnapshot = false;
  }

  // ---------------------------------------------------------------- recording

  /**
   * {@code record}: starts recording into {@code file}.
   *
   * @throws IllegalStateException when this game cannot be recorded
   */
  void startRecording(String name, SessionFiles.Recording file) throws IOException {
    if (recorder != null) throw new IllegalStateException("Already recording " + recordingName);
    if (connection == null || connection.session().isEmpty() || !active())
      throw new IllegalStateException("You must be in a level to record");
    try {
      recorder =
          DemoRecorder.start(
              pipeline,
              file,
              connection.session().get().reliableSequence(),
              connection::requestFullSnapshots);
    } catch (IOException | RuntimeException failure) {
      file.discard();
      throw failure;
    }
    recordingFile = file;
    recordingName = name;
  }

  /** The recorder's write failure, if it stopped by itself. */
  Optional<IOException> recordingFailure() {
    return recorder == null ? Optional.empty() : recorder.failure();
  }

  /**
   * {@code stoprecord}: finishes the file. Returns a line describing the result, or empty when not
   * recording.
   */
  Optional<String> stopRecording() {
    if (recorder == null) return Optional.empty();
    DemoRecorder current = recorder;
    SessionFiles.Recording file = recordingFile;
    String name = recordingName;
    recorder = null;
    recordingFile = null;
    recordingName = "";
    try {
      boolean usable =
          current.failure().isEmpty()
              && current.state() != Protocol68DemoRecorder.State.FAILED
              && current.state() != Protocol68DemoRecorder.State.WAITING;
      if (!usable) file.discard();
      current.close();
      file.close();
      return Optional.of(
          usable
              ? "Stopped demo " + name + " (" + current.recordsWritten() + " records)"
              : "Demo " + name + " was not saved");
    } catch (IOException failure) {
      return Optional.of("Couldn't save demo " + name + ": " + failure.getMessage());
    }
  }

  // ---------------------------------------------------------------- teardown

  /** Ends the game: the recording, the connection ({@code disconnect} is sent) and the cgame. */
  void disconnect(long nowMillis) {
    stopRecording().ifPresent(session::print);
    if (connection != null) {
      try {
        connection.disconnect(nowMillis);
      } catch (RuntimeException failure) {
        session.print("Disconnect failed: " + failure.getMessage());
      }
    }
    close();
  }

  @Override
  public void close() {
    stopRecording().ifPresent(session::print);
    if (cgame != null) {
      cgame.shutdown();
      retiredDegradations += cgame.degradationTotal();
      session
          .services()
          .input()
          .setCgameTarget(dev.bluevista.craftq3.engine.client.input.KeyTarget.NONE);
      cgame = null;
    }
    if (connection != null) {
      try {
        connection.close();
      } catch (IOException failure) {
        session.print("Connection close failed: " + failure.getMessage());
      }
    }
    if (demo != null) {
      try {
        demo.close();
      } catch (IOException failure) {
        session.print("Demo close failed: " + failure.getMessage());
      }
    }
    pipeline.reset();
    commands.reset();
  }

  @Override
  public String toString() {
    return kind + " " + Objects.requireNonNullElse(serverName, "");
  }
}

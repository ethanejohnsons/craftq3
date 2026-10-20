package dev.bluevista.craftq3.engine.client.host;

import static dev.bluevista.craftq3.engine.vmabi.CgameImport.*;

import dev.bluevista.craftq3.engine.client.input.KeyTarget;
import dev.bluevista.craftq3.engine.client.pipeline.ClientSnapshot;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.client.pipeline.UserCmdHistory;
import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.msg.ConfigstringCommands;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.VmExports;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.VmTrapError;
import dev.bluevista.craftq3.engine.vmabi.layout.GameStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.SnapshotLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;
import dev.bluevista.craftq3.engine.vmabi.service.CommandServices;
import dev.bluevista.craftq3.engine.vmabi.service.GuestText;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The cgame VM host: binds every {@code cgameImport_t} host import and wraps the {@code
 * cgameExport_t} entry points. Snapshots, the gamestate and reliable server commands come from the
 * {@link SnapshotPipeline} (the same for local, remote and demo play), user commands from a {@link
 * UserCmdHistory}, collision from the loaded {@link ClientWorld}.
 *
 * <p>Server commands follow {@code CL_GetServerCommand}: configstring commands (including {@code
 * bcs} fragments) update the cgame's own gamestate when the cgame executes them, {@code
 * map_restart} clears the user commands, {@code disconnect} ends the cgame, and the executed
 * command is what {@code trap_Argv} reports until the next one.
 */
public final class CgameHost extends GuestVm implements KeyTarget {
  /** {@code cgame_event_t} for {@code CG_EVENT_HANDLING}. */
  public static final int CGAME_EVENT_NONE = 0;

  /** What the cgame sends to the server and learns about the session. */
  public interface Link {
    /** {@code trap_SendClientCommand}, and console commands the cgame declined. */
    void clientCommand(String text);

    /** True while playing a demo (commands go nowhere, cycled-out commands are tolerated). */
    default boolean demoPlayback() {
      return false;
    }

    /** The server sent {@code disconnect}; the cgame is about to stop. */
    default void serverDisconnected(String reason) {}

    /** The cgame consumed a new systeminfo configstring (it carries the server id). */
    default void systemInfo(String info) {}

    /**
     * Draws what the platform shows over the cgame's loading screen (the ui's connect screen as an
     * overlay, the console) into the frame being built; called inside {@code CG_INIT}'s {@code
     * trap_UpdateScreen} after the cgame drew its information screen.
     */
    default void loadingOverlay() {}
  }

  private final SnapshotPipeline pipeline;
  private final UserCmdHistory commands;
  private final Link link;
  private ConfigstringCommands config;
  private CommandServices.ArgsScope serverArgs;
  private int generation;
  private int lastExecutedCommand;
  private int selectedWeapon;
  private float sensitivityScale = 1;
  private ClientWorld world;
  private boolean initializing;
  private boolean drawingLoadingScreen;
  private long loadingScreens;

  public CgameHost(
      HostServices services, SnapshotPipeline pipeline, UserCmdHistory commands, Link link) {
    super(services, VmModule.CGAME);
    this.pipeline = Objects.requireNonNull(pipeline);
    this.commands = Objects.requireNonNull(commands);
    this.link = Objects.requireNonNull(link);
  }

  /** The weapon the cgame selected ({@code trap_SetUserCmdValue}). */
  public int selectedWeapon() {
    return selectedWeapon;
  }

  public float sensitivityScale() {
    return sensitivityScale;
  }

  /** The configstrings as the cgame currently sees them. */
  public java.util.Map<Integer, String> configStrings() {
    return config == null ? java.util.Map.of() : config.strings();
  }

  // ---------------------------------------------------------------- lifecycle and exports

  /**
   * Loads {@code vm/cgame.qvm} for the pipeline's current gamestate and runs {@code CG_INIT} (which
   * loads the map and every asset).
   *
   * @throws IOException when the module is missing
   * @throws IllegalStateException when the pipeline has no gamestate
   */
  public void start() throws IOException {
    ServerMessageCodec.GameState state =
        pipeline
            .gameState()
            .orElseThrow(() -> new IllegalStateException("The cgame needs a gamestate"));
    generation = pipeline.generation();
    config = new ConfigstringCommands(state.configstrings());
    lastExecutedCommand = state.commandSequence();
    selectedWeapon = 0;
    sensitivityScale = 1;
    instantiate();
    link.systemInfo(config.strings().getOrDefault(Protocol68.CS_SYSTEMINFO, ""));
    initializing = true;
    try {
      exported(
          VmExports.Cgame.CG_INIT.command(),
          pipeline.lastMessage(),
          lastExecutedCommand,
          state.clientNumber());
    } finally {
      initializing = false;
    }
  }

  /** Loading screens drawn inside {@code CG_INIT} so far. */
  public long loadingScreens() {
    return loadingScreens;
  }

  /**
   * {@code trap_UpdateScreen}. Inside {@code CG_INIT} it does what the original's {@code
   * SCR_UpdateScreen} does while loading: a fresh frame, the cgame's own {@code
   * CG_DRAW_ACTIVE_FRAME} (re-entered; while {@code cg.infoScreenText} is set it only draws the
   * information screen with the current loading step), the platform's overlay, and the frame goes
   * to {@code RenderSink.submitImmediate} so the host can present it before {@code CG_INIT}
   * returns. A nested update, and any update outside {@code CG_INIT}, hands over what was drawn so
   * far.
   */
  @Override
  protected int updateScreen(QvmInstance vm, int[] a) {
    if (!initializing || drawingLoadingScreen) return super.updateScreen(vm, a);
    drawingLoadingScreen = true;
    try {
      renderer.beginFrame(services.milliseconds());
      vm.vmMain(VmExports.Cgame.CG_DRAW_ACTIVE_FRAME.command(), 0, 0, link.demoPlayback() ? 1 : 0);
      link.loadingOverlay();
      renderer.updateScreen();
      loadingScreens++;
    } finally {
      drawingLoadingScreen = false;
    }
    return 0;
  }

  /** Whether the pipeline moved to another level since {@link #start()} (restart the cgame). */
  public boolean stale() {
    return pipeline.generation() != generation;
  }

  /** {@code CG_DRAW_ACTIVE_FRAME(serverTime, stereoView, demoPlayback)}. */
  public void drawActiveFrame(int serverTime) {
    exported(
        VmExports.Cgame.CG_DRAW_ACTIVE_FRAME.command(), serverTime, 0, link.demoPlayback() ? 1 : 0);
  }

  /** {@code CG_CONSOLE_COMMAND}: true when the cgame handled the command. */
  public boolean consoleCommand(CommandArgs args) {
    var scope = shared().commands().withArgs(args);
    try {
      return exported(VmExports.Cgame.CG_CONSOLE_COMMAND.command()) != 0;
    } finally {
      scope.close();
    }
  }

  /** {@code CG_CROSSHAIR_PLAYER}. */
  public int crosshairPlayer() {
    return exported(VmExports.Cgame.CG_CROSSHAIR_PLAYER.command());
  }

  /** {@code CG_LAST_ATTACKER}. */
  public int lastAttacker() {
    return exported(VmExports.Cgame.CG_LAST_ATTACKER.command());
  }

  /** {@code CG_EVENT_HANDLING(type)}. */
  public void eventHandling(int type) {
    exported(VmExports.Cgame.CG_EVENT_HANDLING.command(), type);
  }

  @Override
  public void keyEvent(int key, boolean down, int time) {
    if (state() == State.RUNNING)
      exported(VmExports.Cgame.CG_KEY_EVENT.command(), key, down ? 1 : 0);
  }

  @Override
  public void mouseEvent(int dx, int dy, int time) {
    if (state() == State.RUNNING) exported(VmExports.Cgame.CG_MOUSE_EVENT.command(), dx, dy);
  }

  /** Restarts the cgame on the same level (video restart). */
  public void restart() throws IOException {
    shutdown();
    start();
  }

  @Override
  protected int shutdownCommand() {
    return VmExports.Cgame.CG_SHUTDOWN.command();
  }

  @Override
  public void shutdown() {
    super.shutdown();
    services.sound().clearLoopingSounds(true);
  }

  /** Runs an export; the server-command arguments a call exposed end with it. */
  private int exported(int command, int... args) {
    try {
      return call(command, args);
    } finally {
      closeServerArgs();
    }
  }

  private void closeServerArgs() {
    if (serverArgs != null) {
      serverArgs.close();
      serverArgs = null;
    }
  }

  @Override
  protected void unhandledCommand(CommandArgs args) {
    if (!link.demoPlayback()) link.clientCommand(args.text().strip());
  }

  // ---------------------------------------------------------------- imports

  @Override
  protected void bind(Bindings b) {
    b.bind(
        CG_SENDCLIENTCOMMAND,
        (vm, a) -> {
          if (!link.demoPlayback()) link.clientCommand(text(vm, a[0]));
          return 0;
        });
    b.bind(CG_UPDATESCREEN, this::updateScreen);
    b.bind(CG_CM_LOADMAP, this::loadMap);
    b.bind(CG_CM_NUMINLINEMODELS, (vm, a) -> world().inlineModelCount());
    b.bind(CG_CM_INLINEMODEL, (vm, a) -> world().inlineModel(a[0]));
    b.bind(CG_CM_LOADMODEL, (vm, a) -> 0);
    b.bind(CG_CM_TEMPBOXMODEL, (vm, a) -> world().tempModel(vm.memory(), a[0], a[1], false));
    b.bind(CG_CM_TEMPCAPSULEMODEL, (vm, a) -> world().tempModel(vm.memory(), a[0], a[1], true));
    b.bind(CG_CM_POINTCONTENTS, (vm, a) -> world().pointContents(vm.memory(), a[0], a[1], 0, 0));
    b.bind(
        CG_CM_TRANSFORMEDPOINTCONTENTS,
        (vm, a) -> world().pointContents(vm.memory(), a[0], a[1], a[2], a[3]));
    b.bind(CG_CM_BOXTRACE, (vm, a) -> trace(vm, a, false, false));
    b.bind(CG_CM_CAPSULETRACE, (vm, a) -> trace(vm, a, false, true));
    b.bind(CG_CM_TRANSFORMEDBOXTRACE, (vm, a) -> trace(vm, a, true, false));
    b.bind(CG_CM_TRANSFORMEDCAPSULETRACE, (vm, a) -> trace(vm, a, true, true));
    b.bind(CG_CM_MARKFRAGMENTS, (vm, a) -> world().markFragments(vm.memory(), a));
    b.bind(CG_S_STARTSOUND, this::startSound);
    b.bind(CG_S_STARTLOCALSOUND, this::startLocalSound);
    b.bind(
        CG_S_CLEARLOOPINGSOUNDS,
        (vm, a) -> {
          services.sound().clearLoopingSounds(profile() == AbiProfile.Q3_132 && a[0] != 0);
          return 0;
        });
    b.bind(CG_S_ADDLOOPINGSOUND, (vm, a) -> loop(vm, a, false));
    b.bind(CG_S_ADDREALLOOPINGSOUND, (vm, a) -> loop(vm, a, true));
    b.bind(
        CG_S_STOPLOOPINGSOUND,
        (vm, a) -> {
          services.sound().stopLoopingSound(a[0]);
          return 0;
        });
    b.bind(
        CG_S_UPDATEENTITYPOSITION,
        (vm, a) -> {
          services.sound().updateEntityPosition(a[0], vec(vm.memory(), a[1]));
          return 0;
        });
    b.bind(CG_S_RESPATIALIZE, this::respatialize);
    b.bind(CG_S_REGISTERSOUND, this::registerSound);
    b.bind(CG_S_STARTBACKGROUNDTRACK, this::startBackgroundTrack);
    b.bind(CG_S_STOPBACKGROUNDTRACK, this::stopBackgroundTrack);
    b.bind(CG_R_LOADWORLDMAP, this::loadWorldMap);
    b.bind(CG_R_REGISTERMODEL, this::registerModel);
    b.bind(CG_R_REGISTERSKIN, this::registerSkin);
    b.bind(CG_R_REGISTERSHADER, this::registerShader);
    b.bind(CG_R_REGISTERSHADERNOMIP, this::registerShader);
    b.bind(CG_R_REGISTERFONT, this::registerFont);
    b.bind(CG_R_CLEARSCENE, this::clearScene);
    b.bind(CG_R_ADDREFENTITYTOSCENE, this::addRefEntity);
    b.bind(CG_R_ADDPOLYTOSCENE, this::addPoly);
    b.bind(CG_R_ADDPOLYSTOSCENE, this::addPolys);
    b.bind(CG_R_ADDLIGHTTOSCENE, this::addLight);
    b.bind(CG_R_ADDADDITIVELIGHTTOSCENE, this::addLight);
    b.bind(CG_R_RENDERSCENE, this::renderScene);
    b.bind(CG_R_SETCOLOR, this::setColor);
    b.bind(CG_R_DRAWSTRETCHPIC, this::drawStretchPic);
    b.bind(CG_R_MODELBOUNDS, this::modelBounds);
    b.bind(CG_R_LERPTAG, this::lerpTag);
    b.bind(CG_R_REMAP_SHADER, this::remapShader);
    b.bind(
        CG_R_LIGHTFORPOINT, (vm, a) -> renderer.lightForPoint(vm.memory(), a[0], a[1], a[2], a[3]));
    b.bind(
        CG_R_INPVS,
        (vm, a) -> renderer.inPvs(vec(vm.memory(), a[0]), vec(vm.memory(), a[1])) ? 1 : 0);
    b.bind(CG_GETGLCONFIG, this::glconfig);
    b.bind(CG_GETGAMESTATE, this::getGameState);
    b.bind(CG_GETCURRENTSNAPSHOTNUMBER, this::currentSnapshotNumber);
    b.bind(CG_GETSNAPSHOT, this::getSnapshot);
    b.bind(CG_GETSERVERCOMMAND, this::getServerCommand);
    b.bind(CG_GETCURRENTCMDNUMBER, (vm, a) -> commands.current());
    b.bind(CG_GETUSERCMD, this::getUserCmd);
    b.bind(
        CG_SETUSERCMDVALUE,
        (vm, a) -> {
          selectedWeapon = a[0] & 255;
          float scale = f(a[1]);
          sensitivityScale = Float.isFinite(scale) ? scale : 1;
          return 0;
        });
    b.bind(CG_KEY_ISDOWN, this::keyIsDown);
    b.bind(CG_KEY_GETCATCHER, this::keyGetCatcher);
    b.bind(CG_KEY_SETCATCHER, this::keySetCatcher);
    b.bind(CG_KEY_GETKEY, this::keyGetKey);
    b.bind(CG_PC_ADD_GLOBAL_DEFINE, this::pcAddGlobalDefine);
    b.bind(CG_PC_LOAD_SOURCE, this::pcLoadSource);
    b.bind(CG_PC_FREE_SOURCE, this::pcFreeSource);
    b.bind(CG_PC_READ_TOKEN, this::pcReadToken);
    b.bind(CG_PC_SOURCE_FILE_AND_LINE, this::pcSourceFileAndLine);
    b.bind(CG_CIN_PLAYCINEMATIC, this::cinPlay);
    b.bind(CG_CIN_STOPCINEMATIC, this::cinStop);
    b.bind(CG_CIN_RUNCINEMATIC, this::cinRun);
    b.bind(CG_CIN_DRAWCINEMATIC, this::cinDraw);
    b.bind(CG_CIN_SETEXTENTS, this::cinSetExtents);
    b.bind(CG_GET_ENTITY_TOKEN, this::entityToken);
  }

  @Override
  protected int registerShader(QvmInstance vm, int[] a) {
    return assets.shader(GuestText.readPath(vm.memory(), a[0]), ClientAssets.Usage.TWO_D);
  }

  // ---------------------------------------------------------------- map and collision

  private ClientWorld world() {
    if (world == null) throw new IllegalStateException("No collision map loaded");
    return world;
  }

  /** {@code trap_CM_LoadMap}: an unreadable map ends the cgame (critical import). */
  private int loadMap(QvmInstance vm, int[] a) {
    String name = GuestText.readPath(vm.memory(), a[0]);
    try {
      world = services.loadWorld(name);
    } catch (IOException | RuntimeException failure) {
      throw new VmTrapError(VmModule.CGAME, "Couldn't load " + name + ": " + failure.getMessage());
    }
    world.resetEntityTokens();
    return 0;
  }

  private int loadWorldMap(QvmInstance vm, int[] a) {
    String name = GuestText.readPath(vm.memory(), a[0]);
    try {
      world = services.loadWorld(name);
    } catch (IOException failure) {
      throw new IllegalStateException("Cannot load world map " + name, failure);
    }
    return 0;
  }

  private int trace(QvmInstance vm, int[] a, boolean transformed, boolean capsule) {
    world()
        .trace(
            vm.memory(),
            a[0],
            a[1],
            a[2],
            a[3],
            a[4],
            a[5],
            a[6],
            transformed ? a[7] : 0,
            transformed ? a[8] : 0,
            transformed,
            capsule);
    return 0;
  }

  private int entityToken(QvmInstance vm, int[] a) {
    String token = world == null ? null : world.nextEntityToken();
    GuestText.write(vm.memory(), a[0], token == null ? "" : token, a[1]);
    return token == null ? 0 : 1;
  }

  // ---------------------------------------------------------------- sound

  private int startSound(QvmInstance vm, int[] a) {
    if (a[3] == 0) return 0;
    Vec3f origin = a[0] == 0 ? null : vec(vm.memory(), a[0]);
    services.sound().startSound(origin, a[1], a[2], a[3]);
    return 0;
  }

  private int loop(QvmInstance vm, int[] a, boolean real) {
    if (a[3] == 0) return 0;
    QvmMemory memory = vm.memory();
    Vec3f origin = vec(memory, a[1]);
    Vec3f velocity = a[2] == 0 ? Vec3f.ZERO : vec(memory, a[2]);
    if (real) services.sound().addRealLoopingSound(a[0], origin, velocity, a[3]);
    else services.sound().addLoopingSound(a[0], origin, velocity, a[3]);
    return 0;
  }

  private int respatialize(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    Vec3f[] axis = VmStructs.readAxis(memory, a[2]);
    services.sound().respatialize(a[0], vec(memory, a[1]), axis[0], axis[1], axis[2], a[3] != 0);
    return 0;
  }

  // ---------------------------------------------------------------- gamestate and snapshots

  private int getGameState(QvmInstance vm, int[] a) {
    GameStateLayout.write(vm.memory(), a[0], config.strings());
    return 0;
  }

  private int currentSnapshotNumber(QvmInstance vm, int[] a) {
    Optional<ClientSnapshot> latest = pipeline.latestSnapshot();
    vm.memory().writeInt(a[0], latest.map(ClientSnapshot::messageNum).orElse(0));
    vm.memory().writeInt(a[1], latest.map(ClientSnapshot::serverTime).orElse(0));
    return 0;
  }

  /** {@code trap_GetSnapshot(number, snapshot_t *)}: 1 with the snapshot, 0 when it is gone. */
  private int getSnapshot(QvmInstance vm, int[] a) {
    int latest = pipeline.latestSnapshot().map(ClientSnapshot::messageNum).orElse(0);
    if (a[0] > latest)
      throw new VmTrapError(
          VmModule.CGAME, "CL_GetSnapshot: snapshotNumber > cl.snapshot.messageNum");
    if (latest - a[0] >= Protocol68.PACKET_BACKUP) return 0;
    Optional<ClientSnapshot> found = pipeline.snapshot(a[0]);
    if (found.isEmpty()) return 0;
    ClientSnapshot snapshot = found.get();
    List<byte[]> entities = snapshot.snapshot().entities();
    if (entities.size() > SnapshotLayout.MAX_ENTITIES)
      entities = entities.subList(0, SnapshotLayout.MAX_ENTITIES);
    SnapshotLayout.of(profile())
        .write(
            vm.memory(),
            a[1],
            snapshot.flags(),
            snapshot.ping(),
            snapshot.serverTime(),
            snapshot.snapshot().areaMask(),
            snapshot.snapshot().player(),
            entities,
            snapshot.serverCommandSequence(),
            snapshot.serverCommandSequence());
    return 1;
  }

  /** {@code trap_GetServerCommand(number)} ({@code CL_GetServerCommand}). */
  private int getServerCommand(QvmInstance vm, int[] a) {
    int number = a[0];
    int newest = pipeline.serverCommandSequence();
    if (number <= newest - Protocol68.MAX_RELIABLE_COMMANDS) {
      if (link.demoPlayback()) return 0;
      throw new VmTrapError(
          VmModule.CGAME, "CL_GetServerCommand: a reliable command was cycled out");
    }
    if (number > newest)
      throw new VmTrapError(
          VmModule.CGAME, "CL_GetServerCommand: requested a command not received");
    String text = pipeline.serverCommand(number).orElse("");
    lastExecutedCommand = number;
    closeServerArgs();
    String previousSystemInfo = config.strings().getOrDefault(Protocol68.CS_SYSTEMINFO, "");
    Optional<String> visible = config.consume(text);
    if (visible.isEmpty()) return 0;
    String systemInfo = config.strings().getOrDefault(Protocol68.CS_SYSTEMINFO, "");
    if (!systemInfo.equals(previousSystemInfo)) link.systemInfo(systemInfo);
    // Cmd_TokenizeString: one command, semicolons included, unlike console text.
    var tokens = dev.bluevista.craftq3.engine.net.msg.CommandTokens.tokenize(visible.get());
    CommandArgs args = new CommandArgs(visible.get(), tokens.arguments());
    switch (args.argc() == 0 ? "" : args.argv(0)) {
      case "disconnect" -> {
        String reason = args.argc() > 1 ? args.argv(1) : "";
        link.serverDisconnected(reason);
        throw new VmTrapError(
            VmModule.CGAME,
            reason.isEmpty() ? "Server disconnected" : "Server disconnected - " + reason);
      }
      case "map_restart" -> commands.clearCommands();
      case "clientLevelShot" -> {
        return 0;
      }
      default -> {}
    }
    serverArgs = shared().commands().withArgs(args);
    return 1;
  }

  private int getUserCmd(QvmInstance vm, int[] a) {
    if (a[0] > commands.current())
      throw new VmTrapError(VmModule.CGAME, "CL_GetUserCmd: " + a[0] + " >= " + commands.current());
    Optional<UserCmdLayout.UserCmd> command = commands.get(a[0]);
    if (command.isEmpty()) return 0;
    UserCmdLayout.of(profile()).write(vm.memory(), a[1], command.get());
    return 1;
  }
}

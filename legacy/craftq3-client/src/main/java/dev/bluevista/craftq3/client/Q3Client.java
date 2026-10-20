package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.GameFileHandles;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import dev.bluevista.craftq3.server.VmAbi;
import dev.bluevista.craftq3.server.VmIntrinsics;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import dev.bluevista.craftq3.vm.QvmReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Original cgame presentation/prediction with a borrowed engine source and injected CPU sinks. */
public final class Q3Client implements AutoCloseable {
  public enum State {
    LOADED,
    INITIALIZING,
    RUNNING,
    FAILED,
    CLOSED
  }

  private static final Vec3 ZERO = new Vec3(0, 0, 0), X = new Vec3(1, 0, 0), Z = new Vec3(0, 0, 1);
  private final VirtualFileSystem fs;
  private final CgameSource source;
  private final Consumer<CgameFrame> frameSink;
  private final AudioBackend audio;
  private final Consumer<String> output;
  private final CvarSystem cvars;
  private final CommandSystem commands;
  private GameFileHandles files;
  private final ClientAssets assets;
  private final ClientScene scene;
  private final dev.bluevista.craftq3.client.video.Cinematics cinematics;
  private final ClientSoundCommands soundCommands;
  private final ClientSoundCommands serverSoundCommands;
  private final QvmInterpreter vm;
  private final ClientAbi abi;
  private final Map<Integer, UserCommand> userCommands = new LinkedHashMap<>();
  private final Map<Integer, Long> syscallCounts = new LinkedHashMap<>();
  private final java.util.Set<String> registeredCommands = new java.util.LinkedHashSet<>();
  private ClientCollision collision;
  private CommandParser.Command serverCommand;
  private CommandParser.Command consoleContext;
  private AudioBackend.Listener listener = new AudioBackend.Listener(ZERO, X, Z);
  private int clientNum, width, height, time, commandNumber, selectedWeapon, keyCatcher;
  private float sensitivityScale = 1;
  private State state = State.LOADED;
  private int musicLoop;
  private long musicVoice;
  private double musicNext;

  public Q3Client(
      VirtualFileSystem fs,
      Q3Server server,
      Consumer<CgameFrame> frameSink,
      AudioBackend audio,
      Consumer<String> output)
      throws IOException {
    this(fs, server, frameSink, audio, output, null);
  }

  public Q3Client(
      VirtualFileSystem fs,
      Q3Server server,
      Consumer<CgameFrame> frameSink,
      AudioBackend audio,
      Consumer<String> output,
      ClientAbi profile)
      throws IOException {
    this(fs, server, frameSink, audio, output, profile, null);
  }

  /** The application may retain its command buffer across client VM and map lifetimes. */
  public Q3Client(
      VirtualFileSystem fs,
      Q3Server server,
      Consumer<CgameFrame> frameSink,
      AudioBackend audio,
      Consumer<String> output,
      ClientAbi profile,
      CommandSystem sharedCommands)
      throws IOException {
    this(
        fs,
        new LocalCgameSource(server),
        localCvars(server),
        sharedCommands == null ? new CommandSystem(server.cvars(), fs, output) : sharedCommands,
        frameSink,
        audio,
        output,
        profile);
  }

  /** Explicit borrowed state/commands permit presentation without a local game VM. */
  public Q3Client(
      VirtualFileSystem fs,
      CgameSource source,
      CvarSystem cvars,
      CommandSystem commands,
      Consumer<CgameFrame> frameSink,
      AudioBackend audio,
      Consumer<String> output)
      throws IOException {
    this(fs, source, cvars, commands, frameSink, audio, output, null);
  }

  public Q3Client(
      VirtualFileSystem fs,
      CgameSource source,
      CvarSystem cvars,
      CommandSystem commands,
      Consumer<CgameFrame> frameSink,
      AudioBackend audio,
      Consumer<String> output,
      ClientAbi profile)
      throws IOException {
    this.fs = fs;
    this.source = java.util.Objects.requireNonNull(source);
    this.cvars = java.util.Objects.requireNonNull(cvars);
    this.commands = java.util.Objects.requireNonNull(commands);
    this.frameSink = frameSink;
    this.audio = audio;
    this.output = output;
    cvars.register("sv_cheats", "0", CvarSystem.ROM);
    cvars.cheatsEnabled(cvars.integer("sv_cheats") != 0);
    cvars.register("s_volume", "0.8", CvarSystem.ARCHIVE);
    cvars.register("s_musicvolume", "0.25", CvarSystem.ARCHIVE);
    cvars.register("cl_paused", "0", CvarSystem.ROM);
    cvars.register("com_blood", "1", CvarSystem.ARCHIVE);
    cvars.register("cg_predictItems", "1", CvarSystem.ARCHIVE);
    commands.unknownHandler(this::consoleCommand);
    files = new GameFileHandles(fs, null);
    assets = new ClientAssets(fs, audio, output);
    source.externalWorld().ifPresent(world -> assets.externalWorld(world.metadata()));
    scene = new ClientScene(assets);
    cinematics =
        new dev.bluevista.craftq3.client.video.Cinematics(fs, audio, System::nanoTime, output);
    byte[] module = fs.read(new VirtualPath("vm/cgame.qvm"));
    abi = profile == null ? ClientAbi.detect(module) : profile;
    vm =
        new QvmInterpreter(
            QvmReader.read("cgame", module),
            this::syscall,
            new QvmInterpreter.Limits(
                100_000_000, 500_000, 30_000_000_000L, 4096, 1024, 65536, 16));
    soundCommands = new ClientSoundCommands(commands, assets, audio, () -> clientNum, output);
    var additionalCommands = source.additionalEngineCommands();
    serverSoundCommands =
        additionalCommands.isEmpty() || additionalCommands.orElseThrow() == commands
            ? null
            : new ClientSoundCommands(
                additionalCommands.orElseThrow(), assets, audio, () -> clientNum, output);
  }

  private static CvarSystem localCvars(Q3Server server) {
    // Preserve the local constructor default; an explicit source never creates sv_running.
    var cvars = server.cvars();
    cvars.register("sv_running", "1", CvarSystem.ROM);
    return cvars;
  }

  public CvarSystem cvars() {
    return cvars;
  }

  public CommandSystem commands() {
    return commands;
  }

  /** Called within the application's scoped command dispatch after UI commands are offered. */
  public void consoleCommand(CommandParser.Command command) {
    var previousConsole = consoleContext;
    var previousServer = serverCommand;
    consoleContext = java.util.Objects.requireNonNull(command);
    serverCommand = null;
    try {
      if (state != State.RUNNING) {
        output.accept("Client is not running: " + command.argument(0) + "\n");
        return;
      }
      if (invoke(2) == 0 && !source.consoleCommand(command)) source.clientCommand(command.text());
    } finally {
      consoleContext = previousConsole;
      serverCommand = previousServer;
    }
  }

  public int selectedWeapon() {
    return selectedWeapon;
  }

  public float sensitivityScale() {
    return sensitivityScale;
  }

  public int keyCatcher() {
    return keyCatcher;
  }

  public void keyCatcher(int value) {
    if (value < 0 || value > 15) throw new IllegalArgumentException("Invalid key catcher mask");
    keyCatcher = value;
  }

  public State state() {
    return state;
  }

  public ClientAbi abi() {
    return abi;
  }

  public QvmInterpreter.Stats vmStats() {
    return vm.stats();
  }

  public Map<Integer, Long> syscallCounts() {
    return Map.copyOf(syscallCounts);
  }

  public int registeredModels() {
    return assets.models();
  }

  public int registeredShaders() {
    return assets.shaders();
  }

  public int registeredSounds() {
    return assets.sounds();
  }

  public void initialize(int clientNum, int width, int height) {
    if (state != State.LOADED) throw new IllegalStateException("cgame already initialized");
    dimensions(width, height);
    var initial = source.initialize(clientNum);
    if (initial.clientNumber() != clientNum)
      throw new IllegalArgumentException("Cgame source selected a different client");
    this.clientNum = initial.clientNumber();
    time = initial.milliseconds();
    selectedWeapon = initial.selectedWeapon();
    userCommands.put(0, UserCommand.idle(time));
    state = State.INITIALIZING;
    output.accept("CraftQ3 cgame: " + abi + " ABI\n");
    cinematics.beginFrame();
    scene.beginFrame();
    invoke(0, initial.serverMessageSequence(), initial.serverCommandSequence(), clientNum);
    state = State.RUNNING;
  }

  /**
   * Records actual presentation input once. The caller schedules completed server simulation ticks.
   */
  public void userCommand(UserCommand command) {
    running();
    userCommands.put(++commandNumber, command);
    if (userCommands.size() > 64) userCommands.remove(userCommands.keySet().iterator().next());
    source.userCommand(command);
  }

  /**
   * Captures completed server frames, then asks original cgame to predict and submit presentation.
   */
  public CgameFrame frame(int milliseconds, int width, int height) {
    running();
    time = milliseconds;
    if (this.width != width || this.height != height) restart(width, height);
    source.refresh();
    serverCommand = null;
    cinematics.beginFrame();
    scene.beginFrame();
    audio.beginFrame();
    audio.volume(Math.clamp(cvars.number("s_volume"), 0, 1));
    invoke(3, time, 0, source.demoPlayback() ? 1 : 0);
    updateMusic();
    audio.endFrame(listener);
    CgameFrame result = scene.frame(time);
    frameSink.accept(result);
    return result;
  }

  private int syscall(QvmMemory memory, int call, int[] a) throws IOException {
    syscallCounts.merge(call, 1L, Long::sum);
    if (call >= 100 && call <= 106) return VmIntrinsics.invoke(memory, call, a).orElseThrow();
    return switch (call) {
      case 0 -> {
        output.accept(text(memory, a[0]));
        yield 0;
      }
      case 1 -> throw new IllegalStateException("cgame error: " + text(memory, a[0]));
      case 2 -> time;
      case 3 -> {
        var variable = cvars.register(text(memory, a[1]), text(memory, a[2]), a[3]);
        VmAbi.cvar(memory, a[0], variable);
        yield 0;
      }
      case 4 -> {
        if (a[0] != 0) VmAbi.cvar(memory, a[0], cvars.byHandle(memory.readInt(a[0])));
        yield 0;
      }
      case 5 -> {
        cvars.set(text(memory, a[0]), text(memory, a[1]), CvarSystem.Source.VM);
        yield 0;
      }
      case 6 -> {
        VmAbi.string(memory, a[1], cvars.string(text(memory, a[0])), a[2]);
        yield 0;
      }
      case 7 -> context().arguments().size();
      case 8 -> {
        VmAbi.string(memory, a[1], context().argument(a[0]), a[2]);
        yield 0;
      }
      case 9 -> {
        VmAbi.string(memory, a[0], context().argumentsFrom(1), a[1]);
        yield 0;
      }
      case 10 -> openFile(memory, a);
      case 11 -> {
        memory.checkRange(a[0], a[1]);
        byte[] bytes = files.read(a[2], a[1]);
        memory.writeBytes(a[0], bytes);
        yield bytes.length;
      }
      case 12 -> throw new UnsupportedOperationException("cgame has no file write capability");
      case 13 -> {
        files.close(a[0]);
        yield 0;
      }
      case 14 -> {
        commands.submit(text(memory, a[0]), CommandSystem.Execution.APPEND);
        yield 0;
      }
      case 15 -> {
        String name = text(memory, a[0]).toLowerCase(java.util.Locale.ROOT);
        if (!commands.complete(name).contains(name)) {
          registeredCommands.add(name);
          commands.register(name, this::consoleCommand);
        }
        yield 0;
      }
      case 16 -> {
        source.clientCommand(text(memory, a[0]));
        yield 0;
      }
      case 17 -> {
        if (!scene.frame(time).commands().isEmpty()) frameSink.accept(scene.frame(time));
        cinematics.beginFrame();
        scene.beginFrame();
        yield 0;
      }
      case 18 -> {
        collision =
            new ClientCollision(
                assets.loadWorld(text(memory, a[0])),
                source
                    .externalWorld()
                    .map(dev.bluevista.craftq3.server.ExternalWorld::collision)
                    .orElse(null));
        yield 0;
      }
      case 19 -> collision().modelCount();
      case 20 -> collision().inline(a[0]);
      case 22 -> collision().temporary(VmAbi.vector(memory, a[0]), VmAbi.vector(memory, a[1]));
      case 23 -> collision().contents(memory, a, false);
      case 24 -> collision().contents(memory, a, true);
      case 25 -> {
        collision().trace(memory, a, false);
        yield 0;
      }
      case 26 -> {
        collision().trace(memory, a, true);
        yield 0;
      }
      case 27 -> collision().marks(memory, a);
      case 28 -> {
        if (a[3] != 0)
          audio.play(
              new AudioBackend.Playback(
                  a[3],
                  a[1],
                  a[2],
                  a[0] == 0 ? AudioBackend.Spatial.ENTITY : AudioBackend.Spatial.POSITION,
                  a[0] == 0 ? null : VmAbi.vector(memory, a[0]),
                  1,
                  1));
        yield 0;
      }
      case 29 -> {
        if (a[0] != 0)
          audio.play(
              new AudioBackend.Playback(
                  a[0], clientNum, a[1], AudioBackend.Spatial.LOCAL, null, 1, 1));
        yield 0;
      }
      case 30 -> {
        if (abi == ClientAbi.Q3_132 && a[0] != 0) audio.clearLoops();
        yield 0;
      }
      case 31, 80 -> {
        if (a[3] != 0)
          audio.submitLoop(new AudioBackend.Loop(a[0], a[3], VmAbi.vector(memory, a[1]), 1, 1));
        yield 0;
      }
      case 32 -> {
        audio.updateEntity(a[0], VmAbi.vector(memory, a[1]));
        yield 0;
      }
      case 33 -> {
        listener =
            new AudioBackend.Listener(
                a[0],
                VmAbi.vector(memory, a[1]),
                VmAbi.vector(memory, a[2]),
                VmAbi.vector(memory, a[2] + 24),
                a[3] != 0);
        yield 0;
      }
      case 34 -> assets.sound(text(memory, a[0]));
      case 35 -> {
        startMusic(text(memory, a[0]), text(memory, a[1]));
        yield 0;
      }
      case 36 -> {
        assets.loadWorld(text(memory, a[0]));
        yield 0;
      }
      case 37 -> assets.model(text(memory, a[0]));
      case 38 -> assets.skin(text(memory, a[0]));
      case 39, 57 -> assets.shader(text(memory, a[0]), false, false);
      case 40 -> {
        scene.clearScene();
        yield 0;
      }
      case 41 -> {
        scene.entity(memory, a[0]);
        yield 0;
      }
      case 42 -> {
        scene.poly(memory, a[0], a[1], a[2]);
        yield 0;
      }
      case 43 -> {
        scene.light(memory, a);
        yield 0;
      }
      case 44 -> {
        scene.render(memory, a[0]);
        yield 0;
      }
      case 45 -> {
        scene.color(memory, a[0]);
        yield 0;
      }
      case 46 -> {
        scene.quad(a);
        yield 0;
      }
      case 47 -> {
        scene.modelBounds(memory, a);
        yield 0;
      }
      case 48 -> scene.lerpTag(memory, a, text(memory, a[5]));
      case 49 -> {
        abi.glconfig(memory, a[0], width, height);
        yield 0;
      }
      case 50 -> {
        ClientAbi.gamestate(memory, a[0], source.configStrings());
        yield 0;
      }
      case 51 -> {
        var current = source.currentSnapshot();
        memory.writeInt(a[0], current.number());
        memory.writeInt(a[1], current.time());
        yield 0;
      }
      case 52 -> {
        var snapshot = source.snapshot(a[0]);
        if (snapshot.isEmpty()) yield 0;
        CgameSnapshotWriter.write(memory, abi, snapshot.orElseThrow(), a[1]);
        yield 1;
      }
      case 53 -> {
        var text = source.serverCommand(a[0]);
        if (text.isEmpty()) yield 0;
        serverCommand = CommandParser.tokenize(text.orElseThrow());
        if (serverCommand.argument(0).equals("map_restart"))
          userCommands.replaceAll((number, command) -> UserCommand.idle(0));
        yield 1;
      }
      case 54 -> commandNumber;
      case 55 -> {
        if (a[0] > commandNumber) throw new IllegalArgumentException("Future cgame user command");
        var command = userCommands.get(a[0]);
        if (command == null) yield 0;
        command.write(memory, a[1], abi.game());
        yield 1;
      }
      case 56 -> {
        if (a[0] < 0 || a[0] > 255 || !Float.isFinite(f(a[1])) || f(a[1]) < 0 || f(a[1]) > 100)
          throw new IllegalArgumentException("Invalid cgame input scaling");
        selectedWeapon = a[0];
        sensitivityScale = f(a[1]);
        yield 0;
      }
      case 58 -> 64 * 1024 * 1024;
      case 61 -> keyCatcher;
      case 62 -> {
        keyCatcher = a[0];
        yield 0;
      }
      case 69 -> {
        stopMusic();
        yield 0;
      }
      case 71 -> {
        for (int i = 0; i < 3; i++)
          memory.writeFloat(a[0] + i * 4, (float) Math.rint(memory.readFloat(a[0] + i * 4)));
        yield 0;
      }
      case 72 -> {
        String name = text(memory, a[0]);
        if (registeredCommands.remove(name.toLowerCase(java.util.Locale.ROOT)))
          commands.unregister(name);
        yield 0;
      }
      case 74 -> cinematics.play(text(memory, a[0]), a[1], a[2], a[3], a[4], a[5], time);
      case 75 -> cinematics.stop(a[0]);
      case 76 -> cinematics.run(a[0], time);
      case 77 -> {
        cinematics.draw(a[0], width, height).ifPresent(scene::image);
        yield 0;
      }
      case 78 -> {
        cinematics.setExtents(a[0], a[1], a[2], a[3], a[4]);
        yield 0;
      }
      case 107 -> bits(StrictMath.floor(f(a[0])));
      case 108 -> bits(StrictMath.ceil(f(a[0])));
      case 109 -> {
        output.accept(text(memory, a[0]) + a[1]);
        yield 0;
      }
      case 110 -> {
        output.accept(text(memory, a[0]) + f(a[1]));
        yield 0;
      }
      case 111 -> bits(StrictMath.acos(f(a[0])));
      default ->
          throw new UnsupportedOperationException("cgame syscall " + call + " is not implemented");
    };
  }

  /**
   * Renderer restarts preserve local game state and input bindings, but reset guest presentation.
   */
  private void restart(int width, int height) {
    dimensions(width, height);
    invoke(1);
    registeredCommands.forEach(commands::unregister);
    registeredCommands.clear();
    try {
      cinematics.clear();
      files.close();
    } catch (IOException failure) {
      state = State.FAILED;
      throw new IllegalStateException("Cannot close cgame files for renderer restart", failure);
    }
    files = new GameFileHandles(fs, null);
    stopMusic();
    audio.clearLoops();
    vm.reset();
    serverCommand = null;
    source.refresh();
    cinematics.beginFrame();
    scene.beginFrame();
    state = State.INITIALIZING;
    var restart = source.restart();
    if (restart.clientNumber() != clientNum)
      throw new IllegalArgumentException("Cgame source changed client during renderer restart");
    invoke(0, restart.serverMessageSequence(), restart.serverCommandSequence(), clientNum);
    state = State.RUNNING;
  }

  private int openFile(QvmMemory memory, int[] a) throws IOException {
    if (a[2] != 0) throw new UnsupportedOperationException("cgame has read-only filesystem access");
    var path = new VirtualPath(text(memory, a[0]));
    if (a[1] != 0) memory.writeInt(a[1], 0);
    try {
      var opened = files.open(path, GameFileHandles.Mode.READ);
      if (a[1] == 0) files.close(opened.handle());
      else memory.writeInt(a[1], opened.handle());
      return opened.length();
    } catch (NoSuchFileException | FileNotFoundException missing) {
      return -1;
    }
  }

  private void startMusic(String intro, String loop) throws IOException {
    stopMusic();
    if (intro.isEmpty()) return;
    int first = assets.sound(intro);
    musicLoop = assets.sound(loop.isEmpty() ? intro : loop);
    if (first != 0) {
      musicVoice =
          audio.play(
              new AudioBackend.Playback(
                  first,
                  clientNum,
                  255,
                  AudioBackend.Spatial.LOCAL,
                  null,
                  Math.clamp(cvars.number("s_musicvolume"), 0, 1),
                  1));
      musicNext = time + assets.soundDuration(first) * 1000;
    }
  }

  private void updateMusic() {
    if (musicLoop != 0 && time >= musicNext) {
      musicVoice =
          audio.play(
              new AudioBackend.Playback(
                  musicLoop,
                  clientNum,
                  255,
                  AudioBackend.Spatial.LOCAL,
                  null,
                  Math.clamp(cvars.number("s_musicvolume"), 0, 1),
                  1));
      musicNext = time + assets.soundDuration(musicLoop) * 1000;
    }
  }

  private void stopMusic() {
    if (musicVoice != 0) audio.stop(musicVoice);
    musicVoice = 0;
    musicLoop = 0;
  }

  private ClientCollision collision() {
    if (collision == null) throw new IllegalStateException("No cgame collision map loaded");
    return collision;
  }

  private CommandParser.Command context() {
    if (serverCommand != null) return serverCommand;
    return consoleContext != null ? consoleContext : commands.current();
  }

  private int invoke(int call, int... args) {
    try {
      return vm.invoke(call, args);
    } catch (RuntimeException failure) {
      state = State.FAILED;
      throw failure;
    }
  }

  private void dimensions(int width, int height) {
    if (width < 1 || height < 1 || width > 32768 || height > 32768)
      throw new IllegalArgumentException("Invalid cgame framebuffer size");
    this.width = width;
    this.height = height;
  }

  private void running() {
    if (state != State.RUNNING) throw new IllegalStateException("cgame is " + state);
  }

  private static float f(int value) {
    return Float.intBitsToFloat(value);
  }

  private static int bits(double value) {
    return Float.floatToRawIntBits((float) value);
  }

  private static String text(QvmMemory memory, int address) {
    return address == 0 ? "" : memory.readCString(address, 8192);
  }

  @Override
  public void close() throws IOException {
    if (state == State.CLOSED) return;
    try {
      if (state == State.RUNNING) invoke(1);
    } finally {
      state = State.CLOSED;
      registeredCommands.forEach(commands::unregister);
      registeredCommands.clear();
      soundCommands.close();
      if (serverSoundCommands != null) serverSoundCommands.close();
      stopMusic();
      audio.clearLoops();
      cinematics.close();
      files.close();
    }
  }
}

package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.client.demo.DemoCatalog;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.GameFileHandles;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.VmAbi;
import dev.bluevista.craftq3.server.VmIntrinsics;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import dev.bluevista.craftq3.vm.QvmReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Original menu VM using borrowed engine services and copied renderer requests. */
public final class Q3Ui implements AutoCloseable {
  public enum State {
    LOADED,
    INITIALIZING,
    RUNNING,
    FAILED,
    CLOSED
  }

  public enum Menu {
    NONE,
    MAIN,
    INGAME,
    NEED_CD,
    BAD_CD_KEY,
    TEAM,
    POSTGAME
  }

  private final VirtualFileSystem fs;
  private final CvarSystem cvars;
  private final CommandSystem commands;
  private final KeyBindings bindings;
  private final AudioBackend audio;
  private final Consumer<CgameFrame> sink;
  private final Consumer<String> output;
  private final UiHost host;
  private final UiAbi abi;
  private final ClientAssets assets;
  private final ClientScene scene;
  private final dev.bluevista.craftq3.client.video.Cinematics cinematics;
  private final QvmInterpreter vm;
  private GameFileHandles files;
  private final Set<Integer> down = new HashSet<>();
  private final Map<Integer, Long> calls = new LinkedHashMap<>();
  private CommandParser.Command command;
  private State state = State.LOADED;
  private Menu menu = Menu.NONE;
  private int width, height, time, apiVersion;
  private boolean overstrike;
  private int musicLoop;
  private long musicVoice;
  private double musicNext;

  public Q3Ui(
      VirtualFileSystem fs,
      CvarSystem cvars,
      CommandSystem commands,
      KeyBindings bindings,
      AudioBackend audio,
      Consumer<CgameFrame> sink,
      Consumer<String> output,
      UiHost host)
      throws IOException {
    this(fs, cvars, commands, bindings, audio, sink, output, host, null);
  }

  public Q3Ui(
      VirtualFileSystem fs,
      CvarSystem cvars,
      CommandSystem commands,
      KeyBindings bindings,
      AudioBackend audio,
      Consumer<CgameFrame> sink,
      Consumer<String> output,
      UiHost host,
      UiAbi profile)
      throws IOException {
    this.fs = fs;
    this.cvars = cvars;
    this.commands = commands;
    this.bindings = bindings;
    this.audio = audio;
    this.sink = sink;
    this.output = output;
    this.host = host;
    if (cvars.all().isEmpty())
      cvars.register("sv_cheats", "0", CvarSystem.SYSTEMINFO | CvarSystem.ROM);
    if (!cvars.byHandle(0).name().equalsIgnoreCase("sv_cheats"))
      throw new IllegalArgumentException("Engine cvars must reserve handle zero for sv_cheats");
    cvars.register("sv_running", "0", CvarSystem.ROM);
    cvars.register("s_volume", "0.8", CvarSystem.ARCHIVE);
    cvars.register("s_musicvolume", "0.25", CvarSystem.ARCHIVE);
    cvars.register("version", "CraftQ3", CvarSystem.ROM);
    cvars.register("cl_paused", "0", CvarSystem.ROM);
    cvars.register(
        "fs_game",
        fs.searchOrder().isEmpty() ? "baseq3" : fs.searchOrder().getFirst().game(),
        CvarSystem.LATCH);
    files = new GameFileHandles(fs, null);
    assets = new ClientAssets(fs, audio, output);
    scene = new ClientScene(assets);
    cinematics =
        new dev.bluevista.craftq3.client.video.Cinematics(fs, audio, System::nanoTime, output);
    byte[] bytes = fs.read(new VirtualPath("vm/ui.qvm"));
    abi = profile == null ? UiAbi.detect(bytes) : profile;
    vm =
        new QvmInterpreter(
            QvmReader.read("ui", bytes),
            this::syscall,
            new QvmInterpreter.Limits(
                100_000_000, 500_000, 30_000_000_000L, 4096, 1024, 65536, 16));
  }

  public State state() {
    return state;
  }

  public UiAbi abi() {
    return abi;
  }

  public int apiVersion() {
    return apiVersion;
  }

  public QvmInterpreter.Stats vmStats() {
    return vm.stats();
  }

  public Map<Integer, Long> syscallCounts() {
    return Map.copyOf(calls);
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

  public void initialize(int width, int height) {
    if (state != State.LOADED) throw new IllegalStateException("UI is already initialized");
    dimensions(width, height);
    apiVersion = invoke(0);
    if (apiVersion != 3 && apiVersion != 4 && apiVersion != 6) {
      state = State.FAILED;
      throw new UnsupportedOperationException("Unsupported UI API " + apiVersion);
    }
    state = State.INITIALIZING;
    cinematics.beginFrame();
    scene.beginFrame();
    invoke(1, host.clientState().connectionState() >= 5 ? 1 : 0);
    state = State.RUNNING;
    output.accept("CraftQ3 UI: " + abi + " ABI; API " + apiVersion + "\n");
  }

  public void setMenu(Menu menu) {
    running();
    this.menu = java.util.Objects.requireNonNull(menu);
    invoke(7, menu.ordinal());
  }

  public boolean fullscreen() {
    running();
    return invoke(6) != 0;
  }

  public void key(int key, boolean pressed, int milliseconds) {
    running();
    if (key < 0 || key > 1279 || (key > 255 && key < 1024))
      throw new IllegalArgumentException("Invalid UI key");
    time = Math.max(time, milliseconds);
    if (key < 256) {
      if (pressed) down.add(key);
      else down.remove(key);
    }
    // The retail vmMain forwards only the key argument; key-up events are engine-filtered.
    if (abi != UiAbi.RETAIL_1999 || pressed) invoke(3, key, pressed ? 1 : 0);
  }

  public void mouse(int dx, int dy, int milliseconds) {
    running();
    if (Math.abs((long) dx) > 65536 || Math.abs((long) dy) > 65536)
      throw new IllegalArgumentException("UI mouse delta too large");
    time = Math.max(time, milliseconds);
    invoke(4, dx, dy);
  }

  public boolean consoleCommand(CommandParser.Command value) {
    return consoleCommand(value, time);
  }

  /** Engine commands carry current real time even while no menu frame is visible. */
  public boolean consoleCommand(CommandParser.Command value, int milliseconds) {
    running();
    time = milliseconds;
    var previous = command;
    command = value;
    try {
      return invoke(8, time) != 0;
    } finally {
      command = previous;
    }
  }

  /** The caller advances engine command buffers and owns the enclosing audio frame lifecycle. */
  public CgameFrame frame(int milliseconds, int width, int height) {
    running();
    time = milliseconds;
    if (this.width != width || this.height != height) restart(width, height);
    cinematics.beginFrame();
    scene.beginFrame();
    invoke(5, time);
    updateMusic();
    var result = scene.frame(time);
    sink.accept(result);
    return result;
  }

  public CgameFrame drawConnectScreen(boolean overlay) {
    running();
    cinematics.beginFrame();
    scene.beginFrame();
    invoke(9, overlay ? 1 : 0);
    var result = scene.frame(time);
    sink.accept(result);
    return result;
  }

  private void browserServerText(
      QvmMemory memory, int source, int index, int destination, int capacity, boolean info) {
    boolean present = index >= 0 && index < host.browser().capacity(source);
    checkBrowserText(memory, destination, capacity, present);
    String value =
        present
            ? (info ? host.browser().info(source, index) : host.browser().address(source, index))
            : "";
    browserText(memory, destination, capacity, present, value);
  }

  private static void checkBrowserText(
      QvmMemory memory, int destination, int capacity, boolean present) {
    if (present && capacity < 1)
      throw new IllegalArgumentException("Browser output buffer must be nonempty");
    memory.checkRange(destination, present ? capacity : 1);
  }

  /** Native valid slots pad the entire destination; invalid slots write only the first NUL. */
  private static void browserText(
      QvmMemory memory, int destination, int capacity, boolean present, String value) {
    if (present) {
      memory.fill(destination, capacity, 0);
      VmAbi.string(memory, destination, value, capacity);
    } else memory.writeByte(destination, 0);
  }

  private int syscall(QvmMemory memory, int call, int[] a) throws IOException {
    calls.merge(call, 1L, Long::sum);
    if (abi == UiAbi.RETAIL_1999) {
      if (call >= 57 && call < 100)
        throw new UnsupportedOperationException("Unverified retail UI extension syscall " + call);
      if (call == 46 || call == 48) return host.browser().count(call == 46 ? 0 : 2);
      if (call == 47 || call == 49) {
        browserServerText(memory, call == 47 ? 0 : 2, a[0], a[1], a[2], false);
        return 0;
      }
      if (call >= 50 && call <= 56) call -= 4;
    }
    if (call >= 100 && call <= 106) return VmIntrinsics.invoke(memory, call, a).orElseThrow();
    return switch (call) {
      case 0 -> throw new IllegalStateException("UI error: " + text(memory, a[0]));
      case 1 -> {
        output.accept(text(memory, a[0]));
        yield 0;
      }
      case 2 -> time;
      case 3 -> {
        cvars.set(text(memory, a[0]), text(memory, a[1]), CvarSystem.Source.VM);
        yield 0;
      }
      case 4 -> Float.floatToRawIntBits(cvars.number(text(memory, a[0])));
      case 5 -> {
        VmAbi.string(memory, a[1], cvars.string(text(memory, a[0])), a[2]);
        yield 0;
      }
      case 6 -> {
        float value = f(a[1]);
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite UI cvar value");
        cvars.set(
            text(memory, a[0]),
            value == (int) value ? Integer.toString((int) value) : Float.toString(value),
            CvarSystem.Source.VM);
        yield 0;
      }
      case 7 -> {
        cvars.reset(text(memory, a[0]), CvarSystem.Source.VM);
        yield 0;
      }
      case 8 -> {
        cvars.register(text(memory, a[0]), text(memory, a[1]), a[2]);
        yield 0;
      }
      case 9 -> {
        VmAbi.string(memory, a[1], cvars.infoString(a[0], Math.min(a[2], 8192)), a[2]);
        yield 0;
      }
      case 10 -> context().arguments().size();
      case 11 -> {
        VmAbi.string(memory, a[1], context().argument(a[0]), a[2]);
        yield 0;
      }
      case 12 -> {
        if (a[0] < 0 || a[0] > 2)
          throw new IllegalArgumentException("Invalid UI command execution mode");
        commands.submit(text(memory, a[1]), CommandSystem.Execution.values()[a[0]]);
        yield 0;
      }
      case 13 -> open(memory, a);
      case 14 -> {
        memory.checkRange(a[0], a[1]);
        var bytes = files.read(a[2], a[1]);
        memory.writeBytes(a[0], bytes);
        yield bytes.length;
      }
      case 15 ->
          throw new UnsupportedOperationException(
              "UI file writing requires an explicit saved-file capability");
      case 16 -> {
        files.close(a[0]);
        yield 0;
      }
      case 17 -> fileList(memory, a);
      case 18 -> assets.model(text(memory, a[0]));
      case 19 -> assets.skin(text(memory, a[0]));
      case 20 -> assets.shader(text(memory, a[0]), false, false);
      case 21 -> {
        scene.clearScene();
        yield 0;
      }
      case 22 -> {
        scene.entity(memory, a[0]);
        yield 0;
      }
      case 23 -> {
        scene.poly(memory, a[0], a[1], a[2]);
        yield 0;
      }
      case 24 -> {
        scene.light(memory, a);
        yield 0;
      }
      case 25 -> {
        scene.render(memory, a[0]);
        yield 0;
      }
      case 26 -> {
        scene.color(memory, a[0]);
        yield 0;
      }
      case 27 -> {
        scene.quad(a);
        yield 0;
      }
      case 28 -> {
        if (!scene.frame(time).commands().isEmpty()) sink.accept(scene.frame(time));
        cinematics.beginFrame();
        scene.beginFrame();
        yield 0;
      }
      case 29 -> scene.lerpTag(memory, a, text(memory, a[5]));
      case 31 -> assets.sound(text(memory, a[0]));
      case 32 -> {
        if (a[0] != 0)
          audio.play(
              new AudioBackend.Playback(a[0], -1, a[1], AudioBackend.Spatial.LOCAL, null, 1, 1));
        yield 0;
      }
      case 33 -> {
        VmAbi.string(memory, a[1], keyName(a[0]), a[2]);
        yield 0;
      }
      case 34 -> {
        VmAbi.string(memory, a[1], bindings.binding(a[0]), a[2]);
        yield 0;
      }
      case 35 -> {
        bindings.bind(a[0], text(memory, a[1]));
        yield 0;
      }
      case 36 -> down.contains(a[0]) || host.keyDown(a[0]) ? 1 : 0;
      case 37 -> overstrike ? 1 : 0;
      case 38 -> {
        overstrike = a[0] != 0;
        yield 0;
      }
      case 39 -> {
        down.clear();
        host.clearKeys();
        yield 0;
      }
      case 40 -> host.keyCatcher();
      case 41 -> {
        host.keyCatcher(a[0]);
        yield 0;
      }
      case 42 -> {
        VmAbi.string(memory, a[0], host.clipboard(), a[1]);
        yield 0;
      }
      case 43 -> {
        abi.renderer().glconfig(memory, a[0], width, height);
        yield 0;
      }
      case 44 -> {
        clientState(memory, a[0]);
        yield 0;
      }
      case 45 -> {
        if (a[0] < 0 || a[0] >= 1024)
          throw new IllegalArgumentException("Invalid UI configstring index");
        String value = host.configString(a[0]);
        VmAbi.string(memory, a[1], value, a[2]);
        yield value.isEmpty() ? 0 : 1;
      }
      case 46 -> host.browser().pingCount();
      case 47 -> {
        host.browser().clearPing(a[0]);
        yield 0;
      }
      case 48 -> {
        boolean occupied = host.browser().pingOccupied(a[0]);
        checkBrowserText(memory, a[1], a[2], occupied);
        memory.checkRange(a[3], 4);
        var ping = host.browser().ping(a[0]);
        browserText(memory, a[1], a[2], occupied, ping.address());
        memory.writeInt(a[3], ping.milliseconds());
        yield 0;
      }
      case 49 -> {
        boolean occupied = host.browser().pingOccupied(a[0]);
        checkBrowserText(memory, a[1], a[2], occupied);
        browserText(memory, a[1], a[2], occupied, host.browser().pingInfo(a[0]));
        yield 0;
      }
      case 65 -> host.browser().count(a[0]);
      case 66, 67 -> {
        browserServerText(memory, a[0], a[1], a[2], a[3], call == 67);
        yield 0;
      }
      case 68 -> {
        host.browser().markVisible(a[0], a[1], a[2]);
        yield 0;
      }
      case 70 -> {
        host.browser().resetPings(a[0]);
        yield 0;
      }
      case 71 -> {
        host.browser().loadCache();
        yield 0;
      }
      case 72 -> {
        host.browser().saveCache();
        yield 0;
      }
      case 50 -> {
        VmAbi.cvar(memory, a[0], cvars.register(text(memory, a[1]), text(memory, a[2]), a[3]));
        yield 0;
      }
      case 51 -> {
        if (a[0] != 0) VmAbi.cvar(memory, a[0], cvars.byHandle(memory.readInt(a[0])));
        yield 0;
      }
      case 52 -> 64 * 1024 * 1024;
      case 53 -> {
        VmAbi.string(memory, a[0], host.cdKey(), a[1]);
        yield 0;
      }
      case 54 -> {
        host.cdKey(text(memory, a[0]));
        yield 0;
      }
      case 56 -> {
        scene.modelBounds(memory, a);
        yield 0;
      }
      case 62 -> {
        stopMusic();
        yield 0;
      }
      case 63 -> {
        startMusic(text(memory, a[0]), text(memory, a[1]));
        yield 0;
      }
      case 69 -> host.browser().updatePings(a[0]) ? 1 : 0;
      case 73 -> host.browser().add(a[0], text(memory, a[1]), text(memory, a[2]));
      case 74 -> {
        host.browser().remove(a[0], text(memory, a[1]));
        yield 0;
      }
      case 82 -> {
        String address = a[0] == 0 ? null : text(memory, a[0]);
        if (address == null || a[1] == 0) {
          host.browser().resetStatus(address);
          yield 0;
        }
        memory.checkRange(a[1], a[2]);
        if (a[2] < 1) throw new IllegalArgumentException("Server status buffer must be nonempty");
        var status = host.browser().status(address, a[2]);
        if (status.isPresent()) browserText(memory, a[1], a[2], true, status.orElseThrow());
        yield status.isPresent() ? 1 : 0;
      }
      case 83 -> host.browser().serverPing(a[0], a[1]);
      case 84 -> host.browser().visible(a[0], a[1]);
      case 81 -> host.verifyCdKey(text(memory, a[0]), text(memory, a[1])) ? 1 : 0;
      case 85 -> host.browser().compare(a[0], a[1], a[2], a[3], a[4]);
      case 86 -> {
        if (a[2] < 0 || a[2] > 2) throw new IllegalArgumentException("Invalid UI seek mode");
        files.seek(a[0], a[1], GameFileHandles.Seek.values()[a[2]]);
        yield 0;
      }
      case 75 -> cinematics.play(text(memory, a[0]), a[1], a[2], a[3], a[4], a[5], time);
      case 76 -> cinematics.stop(a[0]);
      case 77 -> cinematics.run(a[0], time);
      case 78 -> {
        cinematics.draw(a[0], width, height).ifPresent(scene::image);
        yield 0;
      }
      case 79 -> {
        cinematics.setExtents(a[0], a[1], a[2], a[3], a[4]);
        yield 0;
      }
      case 107 -> bits(StrictMath.floor(f(a[0])));
      case 108 -> bits(StrictMath.ceil(f(a[0])));
      default ->
          throw new UnsupportedOperationException("UI syscall " + call + " is not implemented");
    };
  }

  private int open(QvmMemory memory, int[] a) throws IOException {
    if (a[2] != 0) throw new UnsupportedOperationException("UI filesystem is read-only");
    if (a[1] != 0) memory.writeInt(a[1], 0);
    try {
      var result = files.open(new VirtualPath(text(memory, a[0])), GameFileHandles.Mode.READ);
      if (a[1] == 0) files.close(result.handle());
      else memory.writeInt(a[1], result.handle());
      return result.length();
    } catch (NoSuchFileException | FileNotFoundException missing) {
      return -1;
    }
  }

  private int fileList(QvmMemory memory, int[] a) {
    String directory = text(memory, a[0]), extension = text(memory, a[1]).toLowerCase(Locale.ROOT);
    if (a[3] < 0 || a[3] > 1_048_576)
      throw new IllegalArgumentException("Invalid UI file-list capacity");
    memory.checkRange(a[2], a[3]);
    List<String> names;
    if (directory.equals("$modlist"))
      names =
          fs.searchOrder().stream()
              .map(VirtualFileSystem.Origin::game)
              .distinct()
              .sorted()
              .map(game -> game + '\0' + game)
              .toList();
    else {
      String prefix = directory.isEmpty() ? "" : new VirtualPath(directory).value() + "/";
      var paths =
          fs.list(directory).stream()
              .map(VirtualPath::value)
              .filter(path -> path.startsWith(prefix))
              .map(path -> path.substring(prefix.length()))
              .toList();
      if (prefix.equals("demos/") && !extension.equals("/")) {
        names = DemoCatalog.files(paths, host.demoFiles(), extension, abi == UiAbi.RETAIL_1999);
      } else {
        names =
            extension.equals("/")
                ? paths.stream()
                    .filter(path -> path.indexOf('/') >= 0)
                    .map(path -> path.substring(0, path.indexOf('/') + 1))
                    .distinct()
                    .sorted()
                    .toList()
                : paths.stream()
                    .filter(path -> !path.contains("/") && path.endsWith(extension))
                    .sorted()
                    .toList();
      }
    }
    if (names.size() > 65536) throw new IllegalStateException("UI directory entry limit exceeded");
    if (a[3] > 0) memory.writeByte(a[2], 0);
    int used = 0, count = 0;
    for (String name : names) {
      byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
      if ((long) used + bytes.length + 1 > a[3]) break;
      memory.writeBytes(a[2] + used, bytes);
      memory.writeByte(a[2] + used + bytes.length, 0);
      used += bytes.length + 1;
      count++;
    }
    return count;
  }

  private void clientState(QvmMemory memory, int pointer) {
    memory.fill(pointer, 3084, 0);
    var value = host.clientState();
    memory.writeInt(pointer, value.connectionState());
    memory.writeInt(pointer + 4, value.connectPacketCount());
    memory.writeInt(pointer + 8, value.clientNum());
    VmAbi.string(memory, pointer + 12, value.serverName(), 1024);
    VmAbi.string(memory, pointer + 1036, value.updateInfo(), 1024);
    VmAbi.string(memory, pointer + 2060, value.message(), 1024);
  }

  private void restart(int width, int height) {
    dimensions(width, height);
    invoke(2);
    try {
      cinematics.clear();
      files.close();
    } catch (IOException failure) {
      state = State.FAILED;
      throw new IllegalStateException("UI file cleanup failed", failure);
    }
    files = new GameFileHandles(fs, null);
    stopMusic();
    vm.reset();
    cinematics.beginFrame();
    scene.beginFrame();
    state = State.INITIALIZING;
    invoke(1, host.clientState().connectionState() >= 5 ? 1 : 0);
    state = State.RUNNING;
    invoke(7, menu.ordinal());
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
                  -1,
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
                  -1,
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

  private CommandParser.Command context() {
    return command == null ? commands.current() : command;
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
      throw new IllegalArgumentException("Invalid UI viewport");
    this.width = width;
    this.height = height;
  }

  private void running() {
    if (state != State.RUNNING) throw new IllegalStateException("UI is " + state);
  }

  private static String text(QvmMemory memory, int address) {
    return address == 0 ? "" : memory.readCString(address, 8192);
  }

  private static float f(int value) {
    return Float.intBitsToFloat(value);
  }

  private static int bits(double value) {
    return Float.floatToRawIntBits((float) value);
  }

  private static String keyName(int key) {
    if (key < 0 || key > 255) throw new IllegalArgumentException("Invalid UI key number");
    if (key > 32 && key < 127) return Character.toString((char) key);
    return switch (key) {
      case 9 -> "TAB";
      case 13 -> "ENTER";
      case 27 -> "ESCAPE";
      case 32 -> "SPACE";
      case 127 -> "BACKSPACE";
      case 132 -> "UPARROW";
      case 133 -> "DOWNARROW";
      case 134 -> "LEFTARROW";
      case 135 -> "RIGHTARROW";
      case 136 -> "ALT";
      case 137 -> "CTRL";
      case 138 -> "SHIFT";
      case 139 -> "INS";
      case 140 -> "DEL";
      case 141 -> "PGDN";
      case 142 -> "PGUP";
      case 143 -> "HOME";
      case 144 -> "END";
      case 178 -> "MOUSE1";
      case 179 -> "MOUSE2";
      case 180 -> "MOUSE3";
      case 181 -> "MOUSE4";
      case 182 -> "MOUSE5";
      case 183 -> "MWHEELDOWN";
      case 184 -> "MWHEELUP";
      default ->
          key >= 145 && key <= 159 ? "F" + (key - 144) : String.format(Locale.ROOT, "0x%02x", key);
    };
  }

  @Override
  public void close() throws IOException {
    if (state == State.CLOSED) return;
    try {
      if (state == State.RUNNING) invoke(2);
    } finally {
      state = State.CLOSED;
      stopMusic();
      cinematics.close();
      files.close();
    }
  }
}

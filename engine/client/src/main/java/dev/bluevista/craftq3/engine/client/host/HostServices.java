package dev.bluevista.craftq3.engine.client.host;

import dev.bluevista.craftq3.engine.audio.SoundSystem;
import dev.bluevista.craftq3.engine.client.ClientHostServices;
import dev.bluevista.craftq3.engine.client.console.ClientConsole;
import dev.bluevista.craftq3.engine.client.input.ClientInput;
import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.client.render.ClientRenderer;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandHandler;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.VmHost;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.service.CvarServices;
import dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles;
import dev.bluevista.craftq3.engine.vmabi.service.ModList;
import dev.bluevista.craftq3.engine.vmabi.service.SharedServices;
import dev.bluevista.craftq3.engine.vmabi.service.TimeServices;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Everything the cgame and ui hosts share, implemented once: the client console (cvars, commands,
 * command buffer, key bindings), the file view and home store behind each VM's {@code trap_FS_*},
 * the renderer registration tables and frame recorder, the sound system (with the background
 * track), input and key catchers, the CD key, VM loading with ABI detection and the {@code
 * vm_strictSyscalls}/{@code vm_abi} settings.
 *
 * <p>Owned by the render thread.
 */
public final class HostServices implements AutoCloseable {
  /**
   * The CD key file: {@code baseq3/q3key} of the installation (where the original keeps it) or
   * {@code q3key} at the root of the game's home store. Its contents are never logged.
   */
  public static final String CD_KEY_FILE = "q3key";

  /** CD key characters {@code trap_GetCDKey} reports. */
  public static final int CD_KEY_LENGTH = 16;

  /** VM limits for the client modules (loading a map runs long CG_INIT calls). */
  public static final QvmInstance.Limits VM_LIMITS =
      new QvmInstance.Limits(4_000_000_000L, 4_000_000L, 120_000L, 4096, 1024, 16);

  private static final Log LOG = Log.get(HostServices.class);

  /** A module ready to instantiate. */
  public record LoadedModule(VmModule module, QvmImage image, AbiProfile profile, String source) {}

  private final Console console;
  private final ClientConsole output;
  private final FileView view;
  private final HomeStore home;
  private final Path installRoot;
  private final ClientHostServices platform;
  private final TimeServices time;
  private final ClientAssets assets;
  private final ClientRenderer renderer;
  private final SoundSystem sound;
  private final ClientInput input;
  private final List<String> ownCommands = new ArrayList<>();
  private String cdKey;

  /**
   * @param console the client console (must have key bindings)
   * @param output where the console prints (also the VM print sink)
   * @param view the file view VMs and assets read (pure-restricted on a pure server)
   * @param home the writable home store, or null when nothing may be written
   * @param installRoot the installation for {@code $modlist} and the CD key, or null
   * @param decodeImages decode images for the frames' assets (false for headless runs)
   */
  public HostServices(
      Console console,
      ClientConsole output,
      FileView view,
      HomeStore home,
      Path installRoot,
      ClientHostServices platform,
      TimeServices time,
      boolean decodeImages) {
    this.console = Objects.requireNonNull(console);
    this.output = Objects.requireNonNull(output);
    this.view = Objects.requireNonNull(view);
    this.home = home;
    this.installRoot = installRoot;
    this.platform = Objects.requireNonNull(platform);
    this.time = Objects.requireNonNull(time);
    FileViewAssets lookup = new FileViewAssets(view);
    this.assets = new ClientAssets(lookup, decodeImages);
    this.renderer = new ClientRenderer(assets, platform.renderSink());
    renderer.setVideoSize(platform.windowWidth(), platform.windowHeight());
    this.sound = new SoundSystem(lookup, platform.audioDevice(), SoundSystem.Limits.DEFAULT);
    sound.claimThread();
    this.input =
        new ClientInput(
            console.cvars(),
            console.buffer(),
            console
                .bindings()
                .orElseThrow(() -> new IllegalArgumentException("A client console is required")));
    output.attachKeepingPredicate(console.buffer());
    CvarTable cvars = console.cvars();
    EngineCvars.register(cvars);
    registerSoundCommands();
    cvars.register(VmHost.Options.STRICT_CVAR, "0", 0);
    cvars.register(AbiProfile.OVERRIDE_CVAR, "", 0);
    cvars.register("s_volume", "0.8", CvarFlags.ARCHIVE);
    cvars.register("s_musicvolume", "0.25", CvarFlags.ARCHIVE);
    cvars.register("cl_paused", "0", CvarFlags.ROM);
    cvars.register("sv_running", "0", CvarFlags.ROM);
    cvars.register("sv_cheats", "0", CvarFlags.ROM | CvarFlags.SYSTEMINFO);
    cvars.register("version", "CraftQ3 1.32 ABI", CvarFlags.ROM | CvarFlags.SERVERINFO);
    cvars.register("fs_game", gameDirectory(), CvarFlags.INIT | CvarFlags.SYSTEMINFO);
    cvars.register("com_blood", "1", CvarFlags.ARCHIVE);
    cvars.register("cg_predictItems", "1", CvarFlags.ARCHIVE | CvarFlags.USERINFO);
  }

  /** {@code CHAN_LOCAL_SOUND}: the channel the {@code play} command uses. */
  private static final int CHAN_LOCAL_SOUND = 6;

  /**
   * The sound system's console commands: {@code play}, {@code music}, {@code stopsound}. Each name
   * this instance registers is unregistered again by {@link #close()}, so a rebuilt instance (a
   * content restart) binds its own sound system.
   */
  private void registerSoundCommands() {
    register(
        "play",
        args -> {
          if (args.argc() < 2) {
            output.print("Usage: play <sound filename> [sound filename] ...");
            return;
          }
          for (int i = 1; i < args.argc(); i++) {
            int handle = sound.registerSound(args.argv(i), false);
            if (handle != 0) sound.startLocalSound(handle, CHAN_LOCAL_SOUND);
          }
        });
    register(
        "music",
        args -> {
          if (args.argc() < 2 || args.argc() > 3) {
            output.print("music <musicfile> [loopfile]");
            return;
          }
          startBackgroundTrack(args.argv(1), args.argc() == 3 ? args.argv(2) : args.argv(1));
        });
    register("stopsound", args -> sound.stopAllSounds());
  }

  private void register(String name, CommandHandler handler) {
    if (console.commands().register(name, handler)) ownCommands.add(name);
  }

  /** The console commands this instance registered (and {@link #close()} removes). */
  public List<String> ownCommands() {
    return List.copyOf(ownCommands);
  }

  private String gameDirectory() {
    String game = view.searchPath() == null ? "" : view.searchPath().game();
    return game == null || game.equalsIgnoreCase("baseq3") ? "" : game;
  }

  public Console console() {
    return console;
  }

  public CvarTable cvars() {
    return console.cvars();
  }

  public ClientConsole output() {
    return output;
  }

  public FileView view() {
    return view;
  }

  public Optional<HomeStore> home() {
    return Optional.ofNullable(home);
  }

  public ClientHostServices platform() {
    return platform;
  }

  public TimeServices time() {
    return time;
  }

  public ClientAssets assets() {
    return assets;
  }

  public ClientRenderer renderer() {
    return renderer;
  }

  public SoundSystem sound() {
    return sound;
  }

  public ClientInput input() {
    return input;
  }

  /** Milliseconds since startup ({@code Sys_Milliseconds}). */
  public int milliseconds() {
    return time.milliseconds();
  }

  /** The dispatcher options from {@code vm_strictSyscalls}. */
  public VmHost.Options vmOptions() {
    return VmHost.Options.fromCvars(console.cvars());
  }

  // ---------------------------------------------------------------- VM services

  /** A fresh file-handle table for one VM. */
  public FsVmFiles newVmFiles() {
    List<Path> roots = new ArrayList<>();
    if (home != null) roots.add(home.root());
    if (installRoot != null) roots.add(installRoot);
    return new FsVmFiles(view, home, roots.isEmpty() ? null : new ModList(roots));
  }

  /** The shared imports of {@code module} over {@code files}. */
  public SharedServices shared(
      VmModule module, FsVmFiles files, Consumer<CommandArgs> unhandledCommand) {
    return SharedServices.create(
        module,
        output,
        time,
        console.buffer(),
        CvarServices.WritePolicy.PROTECT_ROM_INIT,
        files,
        unhandledCommand);
  }

  /**
   * Reads {@code vm/<module>.qvm} through the file view and detects its ABI ({@code vm_abi}
   * overrides).
   *
   * @throws IOException when the module is missing or cannot be read
   */
  public LoadedModule loadModule(VmModule module) throws IOException {
    VirtualPath path = VirtualPath.of(module.path());
    byte[] bytes = view.read(path);
    QvmImage image = QvmImage.parse(module.moduleName(), bytes, QvmImage.StackPolicy.NATIVE_EXTENT);
    AbiProfile profile =
        AbiProfile.resolve(console.cvars().string(AbiProfile.OVERRIDE_CVAR), image, bytes);
    String source = view.find(path).map(Object::toString).orElse(module.path());
    return new LoadedModule(module, image, profile, source);
  }

  private ClientWorld world;
  private Preload preload;

  /** A world being parsed and built on a worker for {@link #loadWorld}. */
  private record Preload(String name, CompletableFuture<ClientWorld> world) {}

  /**
   * The map {@code name} for {@code trap_CM_LoadMap} and {@code trap_R_LoadWorldMap}, read once and
   * shared by collision and the renderer until another map is loaded. The host's {@link
   * ClientHostServices#mapOverride} is consulted first; its {@link ClientHostServices#worldSources}
   * join the world's collision. A {@link #preloadWorld preloaded} world is taken over (waiting for
   * the rest of its build). The host hears {@link ClientHostServices#worldLoaded}.
   *
   * @throws IOException when the map is missing or malformed
   */
  public ClientWorld loadWorld(String name) throws IOException {
    String normalized = dev.bluevista.craftq3.engine.formats.QPath.normalize(name);
    if (world != null && world.name().equals(normalized)) return world;
    ClientWorld loaded;
    if (preload != null && preload.name().equals(normalized)) {
      CompletableFuture<ClientWorld> pending = preload.world();
      preload = null;
      try {
        loaded = pending.join();
      } catch (CompletionException failed) {
        Throwable cause = failed.getCause() == null ? failed : failed.getCause();
        if (cause instanceof IOException io) throw io;
        if (cause instanceof RuntimeException runtime) throw runtime;
        throw new IOException("Couldn't build " + normalized, cause);
      }
    } else {
      loaded = build(normalized, readMap(normalized), sourcesFor(normalized));
    }
    world = loaded;
    renderer.loadWorld(normalized, loaded.map());
    platform.worldLoaded(normalized, loaded.map(), assets, loaded.sceneOptions());
    return world;
  }

  /**
   * Starts building the map {@code name} off this thread ({@code executor}): the file is read here
   * (so pure reference tracking stays on the client thread), parsed and given its collision model
   * on the worker. The next {@link #loadWorld} of the same name takes it over; the cgame's own map
   * load is then shorter. Does nothing for the loaded map or one already preloading.
   */
  public void preloadWorld(String name, Executor executor) {
    String normalized = dev.bluevista.craftq3.engine.formats.QPath.normalize(name);
    if (world != null && world.name().equals(normalized)) return;
    if (preload != null && preload.name().equals(normalized)) return;
    CompletableFuture<ClientWorld> pending;
    try {
      Object source = mapSource(normalized);
      List<CollisionSource> sources = sourcesFor(normalized);
      ShaderLibrary library = assets.library();
      RenderScene.Options options = sceneOptions();
      pending =
          CompletableFuture.supplyAsync(
              () -> {
                BspMap map =
                    source instanceof BspMap given
                        ? given
                        : BspReader.read((byte[]) source, normalized);
                ClientWorld built = new ClientWorld(normalized, map, library, sources, options);
                built.collision();
                return built;
              },
              executor);
    } catch (IOException | RuntimeException failure) {
      pending = CompletableFuture.failedFuture(failure);
    }
    preload = new Preload(normalized, pending);
  }

  /** True while a {@link #preloadWorld preloaded} world is still being built. */
  public boolean preloading() {
    return preload != null && !preload.world().isDone();
  }

  private BspMap readMap(String normalized) throws IOException {
    Object source = mapSource(normalized);
    return source instanceof BspMap given ? given : BspReader.read((byte[]) source, normalized);
  }

  /** The host's map, else the file's bytes. */
  private Object mapSource(String normalized) throws IOException {
    Optional<BspMap> override = platform.mapOverride(normalized);
    if (override.isPresent()) return override.get();
    return view.read(VirtualPath.of(normalized));
  }

  private List<CollisionSource> sourcesFor(String normalized) {
    return List.copyOf(platform.worldSources(mapName(normalized)));
  }

  private ClientWorld build(String normalized, BspMap map, List<CollisionSource> sources) {
    return new ClientWorld(normalized, map, assets.library(), sources, sceneOptions());
  }

  /** Patch tessellation for the next world, from {@code r_subdivisions}. */
  private RenderScene.Options sceneOptions() {
    return RenderScene.Options.subdivisions(console.cvars().value("r_subdivisions"));
  }

  /** {@code maps/q3dm17.bsp} → {@code q3dm17}. */
  static String mapName(String normalizedPath) {
    String name = normalizedPath;
    int slash = name.lastIndexOf('/');
    if (slash >= 0) name = name.substring(slash + 1);
    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".bsp"))
      name = name.substring(0, name.length() - 4);
    return name;
  }

  /** The loaded map, if any. */
  public Optional<ClientWorld> world() {
    return Optional.ofNullable(world);
  }

  /** Forgets the loaded map (disconnect). */
  public void unloadWorld() {
    if (preload != null) preload.world().cancel(false);
    preload = null;
    boolean had = world != null;
    world = null;
    renderer.loadWorld("", null);
    if (had) platform.worldUnloaded();
  }

  /** A script or text file, ISO-8859-1, for the precompiler. */
  public Optional<String> readText(String path) {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty() || !view.exists(parsed.get())) return Optional.empty();
    try {
      return Optional.of(new String(view.read(parsed.get()), StandardCharsets.ISO_8859_1));
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }

  /** Opens a game file for streaming (cinematics). */
  public InputStream openFile(String path) throws IOException {
    Optional<VirtualPath> parsed = VirtualPath.parse(path);
    if (parsed.isEmpty()) throw new java.nio.file.NoSuchFileException(path);
    return view.open(parsed.get());
  }

  // ---------------------------------------------------------------- music

  /** {@code trap_S_StartBackgroundTrack(intro, loop)}. */
  public void startBackgroundTrack(String intro, String loop) {
    sound.setMusicVolume(Math.clamp(finite(console.cvars().value("s_musicvolume")), 0, 1));
    sound.startBackgroundTrack(intro, loop);
  }

  public void stopBackgroundTrack() {
    sound.stopBackgroundTrack();
  }

  /** Applies {@code s_volume}/{@code s_musicvolume} and advances the mixer by one frame. */
  public void updateSound(int frameMillis) {
    sound.setVolume(Math.clamp(finite(console.cvars().value("s_volume")), 0, 1));
    sound.setMusicVolume(Math.clamp(finite(console.cvars().value("s_musicvolume")), 0, 1));
    sound.update(Math.max(0, frameMillis));
  }

  private static float finite(float value) {
    return Float.isFinite(value) ? value : 0;
  }

  // ---------------------------------------------------------------- CD key

  /**
   * The CD key the ui shows: set by {@code trap_SetCDKey}, else read once from {@code q3key} in the
   * game's home store, else from the installation's {@code baseq3/q3key}. Never logged.
   */
  public String cdKey() {
    if (cdKey == null) cdKey = readCdKey();
    return cdKey;
  }

  /** {@code trap_SetCDKey}: kept for this run only; the installation is never written. */
  public void setCdKey(String value) {
    cdKey = value.length() > CD_KEY_LENGTH ? value.substring(0, CD_KEY_LENGTH) : value;
  }

  private String readCdKey() {
    List<Path> candidates = new ArrayList<>();
    // The home store is already the game's directory (e.g. ~/.craftq3/cpma).
    if (home != null) candidates.add(home.root().resolve(CD_KEY_FILE));
    if (installRoot != null) candidates.add(installRoot.resolve("baseq3").resolve(CD_KEY_FILE));
    for (Path file : candidates) {
      try {
        if (!Files.isRegularFile(file) || Files.size(file) > 1024) continue;
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).strip();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < text.length() && key.length() < CD_KEY_LENGTH; i++) {
          char c = text.charAt(i);
          if (c > ' ' && c < 127) key.append(c);
        }
        return key.toString();
      } catch (IOException unreadable) {
        LOG.debug("CD key file unreadable");
      }
    }
    return "";
  }

  @Override
  public void close() {
    for (String name : ownCommands) console.commands().unregister(name);
    ownCommands.clear();
    input.close();
    sound.stopAllSounds();
  }
}

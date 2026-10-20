package dev.bluevista.craftq3.engine.client.host;

import dev.bluevista.craftq3.engine.audio.SoundSystem;
import dev.bluevista.craftq3.engine.client.ClientHostServices;
import dev.bluevista.craftq3.engine.client.console.ClientConsole;
import dev.bluevista.craftq3.engine.client.input.ClientInput;
import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.client.render.ClientRenderer;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.formats.FileViewAssets;
import dev.bluevista.craftq3.engine.fs.FileView;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
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
  /** The cvar naming a CD key file is never logged; the key lives in {@code baseq3/q3key}. */
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
    output.attach(console.buffer(), () -> false);
    CvarTable cvars = console.cvars();
    EngineCvars.register(cvars);
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
    QvmImage image = withNativeStack(QvmImage.parse(module.moduleName(), bytes), bytes);
    AbiProfile profile =
        AbiProfile.resolve(console.cvars().string(AbiProfile.OVERRIDE_CVAR), image, bytes);
    String source = view.find(path).map(Object::toString).orElse(module.path());
    return new LoadedModule(module, image, profile, source);
  }

  /**
   * The original interpreter has no stack-bottom check: the program stack starts at the top of the
   * power-of-two data segment and may use everything down to the end of the guest's globals (the 64
   * KiB q3asm reserves at the end of BSS plus the rounding slack). Some menus (Q3Rally) need more
   * than the reservation, so the image is re-parsed with that whole region as its stack.
   */
  static QvmImage withNativeStack(QvmImage image, byte[] bytes) {
    long total = (long) image.dataLength() + image.literalLength() + image.bssLength();
    long globalsEnd =
        image.bssLength() >= QvmImage.PROGRAM_STACK_SIZE
            ? total - QvmImage.PROGRAM_STACK_SIZE
            : total;
    long available = (image.memorySize() - globalsEnd) & ~3L;
    if (available <= image.programStackSize()) return image;
    if (image.bssLength() < available) available = (image.memorySize() - total) & ~3L;
    if (available <= image.programStackSize()) return image;
    try {
      return QvmImage.parse(image.name(), bytes, (int) available);
    } catch (RuntimeException rejected) {
      return image;
    }
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
   * The CD key the ui shows: set by {@code trap_SetCDKey}, else read once from {@code baseq3/q3key}
   * of the installation (or the home store). Never logged.
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
    if (home != null) candidates.add(home.root().resolve("baseq3").resolve(CD_KEY_FILE));
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
    input.close();
    sound.stopAllSounds();
  }
}

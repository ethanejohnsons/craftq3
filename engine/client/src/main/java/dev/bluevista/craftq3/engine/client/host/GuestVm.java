package dev.bluevista.craftq3.engine.client.host;

import dev.bluevista.craftq3.engine.client.render.ClientAssets;
import dev.bluevista.craftq3.engine.client.render.ClientRenderer;
import dev.bluevista.craftq3.engine.client.script.ScriptSources;
import dev.bluevista.craftq3.engine.client.video.Cinematics;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.vm.QvmException;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.ImportTable;
import dev.bluevista.craftq3.engine.vmabi.VmHost;
import dev.bluevista.craftq3.engine.vmabi.VmImport;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.VmTrapError;
import dev.bluevista.craftq3.engine.vmabi.layout.FontInfoLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.GlConfigLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.GlyphInfoLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;
import dev.bluevista.craftq3.engine.vmabi.service.FsVmFiles;
import dev.bluevista.craftq3.engine.vmabi.service.GuestText;
import dev.bluevista.craftq3.engine.vmabi.service.SharedServices;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The lifecycle and the imports the cgame and ui hosts share, implemented once: loading the module
 * with ABI detection, the {@link VmHost} dispatcher, the renderer, sound, key-catcher, glconfig,
 * font, cinematic and script ({@code trap_PC_*}) imports, and restart handling (shut the guest
 * down, release its commands, files, cinematics and scripts, start a fresh instance).
 *
 * <p>A guest fault ({@code trap_Error}, a VM exception, a critical import failure) marks the host
 * {@link State#FAILED} and propagates to the caller, which drops to the menu.
 */
public abstract class GuestVm implements AutoCloseable {
  /** Host lifecycle. */
  public enum State {
    STOPPED,
    RUNNING,
    FAILED
  }

  private static final Log LOG = Log.get(GuestVm.class);

  protected final HostServices services;
  protected final VmModule module;
  protected final ClientRenderer renderer;
  protected final ClientAssets assets;
  private final Map<String, Integer> fontCache = new HashMap<>();
  private HostServices.LoadedModule loaded;
  private VmHost host;
  private QvmInstance vm;
  private SharedServices shared;
  private FsVmFiles files;
  private ScriptSources scripts;
  private Cinematics cinematics;
  private State state = State.STOPPED;
  private RuntimeException fault;
  private long degradationsBefore;

  /**
   * The running instance (memory inspection, a {@link dev.bluevista.craftq3.engine.vm.VmTracer}).
   */
  public QvmInstance instance() {
    return vm;
  }

  protected GuestVm(HostServices services, VmModule module) {
    this.services = Objects.requireNonNull(services);
    this.module = Objects.requireNonNull(module);
    this.renderer = services.renderer();
    this.assets = services.assets();
  }

  public State state() {
    return state;
  }

  public VmModule module() {
    return module;
  }

  /** The fault that stopped the guest, if it failed. */
  public Optional<RuntimeException> fault() {
    return Optional.ofNullable(fault);
  }

  /** The ABI of the running module. */
  public AbiProfile profile() {
    requireLoaded();
    return loaded.profile();
  }

  public HostServices.LoadedModule loaded() {
    requireLoaded();
    return loaded;
  }

  /** The dispatcher, for degradation counts. */
  public VmHost dispatcher() {
    requireLoaded();
    return host;
  }

  /** Degradations and unknown calls over every instance this host has run. */
  public long degradationTotal() {
    return degradationsBefore + (host == null ? 0 : host.degradationTotal());
  }

  /** The shared services of the running instance (argument scopes, prints). */
  protected SharedServices shared() {
    return shared;
  }

  protected QvmInstance vm() {
    return vm;
  }

  protected Cinematics cinematics() {
    return cinematics;
  }

  private void requireLoaded() {
    if (loaded == null) throw new IllegalStateException(module.moduleName() + " is not loaded");
  }

  // ---------------------------------------------------------------- lifecycle

  /**
   * Loads the module and builds a fresh instance with every import bound; the subclass then runs
   * its init export.
   *
   * @throws IOException when the module is missing
   */
  protected void instantiate() throws IOException {
    if (state == State.RUNNING) throw new IllegalStateException(module + " is already running");
    loaded = services.loadModule(module);
    files = services.newVmFiles();
    scripts = new ScriptSources(services::readText, line -> services.output().print(line));
    cinematics = new Cinematics(services::openFile, services.sound(), services.output()::print);
    shared = services.shared(module, files, this::unhandledCommand);
    Bindings bindings = shared.install(new Bindings(module));
    bind(bindings);
    if (host != null) degradationsBefore += host.degradationTotal();
    VmHost.Options options = services.vmOptions();
    host = new VmHost(ImportTable.of(module, loaded.profile()), bindings, options);
    vm =
        new QvmInstance(
            loaded.image(),
            host,
            HostServices.VM_LIMITS,
            dev.bluevista.craftq3.engine.common.time.Clock.system(),
            options.executionMode());
    fault = null;
    state = State.RUNNING;
    services.output().print(module.moduleName() + ": " + loaded.profile().key() + " ABI");
  }

  /** Binds every HOST import of the module. */
  protected abstract void bind(Bindings bindings);

  /** The command a guest-added console command fell through to (cgame: the server). */
  protected void unhandledCommand(CommandArgs args) {}

  /** The export the subclass runs before cleanup in {@link #shutdown()}, or -1 for none. */
  protected abstract int shutdownCommand();

  /** Runs {@code vmMain(command, args)}, marking the host failed if the guest faults. */
  protected final int call(int command, int... args) {
    if (state != State.RUNNING)
      throw new IllegalStateException(module.moduleName() + " is " + state);
    try {
      return vm.vmMain(command, args);
    } catch (VmTrapError | QvmException failure) {
      fail(failure);
      throw failure;
    } catch (RuntimeException failure) {
      fail(failure);
      throw failure;
    }
  }

  private void fail(RuntimeException failure) {
    state = State.FAILED;
    fault = failure;
    shared.print().flush();
    LOG.warn(module.moduleName() + " stopped: " + failure.getMessage());
  }

  /** Runs the shutdown export (when running) and releases everything the guest held. */
  public void shutdown() {
    try {
      if (state == State.RUNNING && shutdownCommand() >= 0) call(shutdownCommand());
    } catch (RuntimeException failure) {
      LOG.warn(module.moduleName() + " shutdown failed: " + failure.getMessage());
    } finally {
      release();
    }
  }

  private void release() {
    if (shared != null) {
      shared.commands().removeAll();
      shared.print().flush();
    }
    if (files != null) {
      try {
        files.closeAll();
      } catch (IOException ignored) {
        LOG.debug(module.moduleName() + " file cleanup failed");
      }
    }
    if (cinematics != null) cinematics.clear();
    if (scripts != null) scripts.clear();
    fontCache.clear();
    if (state == State.RUNNING) state = State.STOPPED;
  }

  @Override
  public void close() {
    shutdown();
  }

  // ---------------------------------------------------------------- shared import handlers

  protected static float f(int bits) {
    return Float.intBitsToFloat(bits);
  }

  protected static String text(QvmInstance vm, int address) {
    return GuestText.read(vm.memory(), address);
  }

  protected int registerModel(QvmInstance vm, int[] a) {
    return assets.model(GuestText.readPath(vm.memory(), a[0]));
  }

  protected int registerSkin(QvmInstance vm, int[] a) {
    return assets.skin(GuestText.readPath(vm.memory(), a[0]));
  }

  protected int registerShader(QvmInstance vm, int[] a) {
    return assets.shader(GuestText.readPath(vm.memory(), a[0]), ClientAssets.Usage.TWO_D);
  }

  protected int clearScene(QvmInstance vm, int[] a) {
    renderer.clearScene();
    return 0;
  }

  protected int addRefEntity(QvmInstance vm, int[] a) {
    renderer.addRefEntity(vm.memory(), a[0]);
    return 0;
  }

  protected int addPoly(QvmInstance vm, int[] a) {
    renderer.addPolys(vm.memory(), a[0], a[1], a[2], 1);
    return 0;
  }

  protected int addPolys(QvmInstance vm, int[] a) {
    renderer.addPolys(vm.memory(), a[0], a[1], a[2], a[3]);
    return 0;
  }

  protected int addLight(QvmInstance vm, int[] a) {
    renderer.addLight(vm.memory(), a[0], f(a[1]), f(a[2]), f(a[3]), f(a[4]));
    return 0;
  }

  protected int renderScene(QvmInstance vm, int[] a) {
    renderer.renderScene(vm.memory(), a[0]);
    return 0;
  }

  protected int setColor(QvmInstance vm, int[] a) {
    renderer.setColor(vm.memory(), a[0]);
    return 0;
  }

  protected int drawStretchPic(QvmInstance vm, int[] a) {
    renderer.drawStretchPic(
        f(a[0]), f(a[1]), f(a[2]), f(a[3]), f(a[4]), f(a[5]), f(a[6]), f(a[7]), a[8]);
    return 0;
  }

  protected int updateScreen(QvmInstance vm, int[] a) {
    renderer.updateScreen();
    return 0;
  }

  protected int lerpTag(QvmInstance vm, int[] a) {
    return renderer.lerpTag(vm.memory(), a[0], a[1], a[2], a[3], f(a[4]), text(vm, a[5]));
  }

  protected int modelBounds(QvmInstance vm, int[] a) {
    renderer.modelBounds(vm.memory(), a[0], a[1], a[2]);
    return 0;
  }

  protected int remapShader(QvmInstance vm, int[] a) {
    assets.remap(text(vm, a[0]), text(vm, a[1]));
    return 0;
  }

  protected int registerSound(QvmInstance vm, int[] a) {
    return services.sound().registerSound(GuestText.readPath(vm.memory(), a[0]), a[1] != 0);
  }

  protected int startLocalSound(QvmInstance vm, int[] a) {
    if (a[0] != 0) services.sound().startLocalSound(a[0], a[1]);
    return 0;
  }

  protected int startBackgroundTrack(QvmInstance vm, int[] a) {
    services.startBackgroundTrack(
        GuestText.readPath(vm.memory(), a[0]), GuestText.readPath(vm.memory(), a[1]));
    return 0;
  }

  protected int stopBackgroundTrack(QvmInstance vm, int[] a) {
    services.stopBackgroundTrack();
    return 0;
  }

  protected int keyIsDown(QvmInstance vm, int[] a) {
    return services.input().isDown(a[0]) ? 1 : 0;
  }

  protected int keyGetCatcher(QvmInstance vm, int[] a) {
    return services.input().catchers();
  }

  protected int keySetCatcher(QvmInstance vm, int[] a) {
    services.input().setCatchers(a[0]);
    return 0;
  }

  /** {@code trap_Key_GetKey(binding)}: the first key bound to it, or -1. */
  protected int keyGetKey(QvmInstance vm, int[] a) {
    return services.input().bindings().keyFor(text(vm, a[0]));
  }

  protected int keyNumToString(QvmInstance vm, int[] a) {
    GuestText.write(vm.memory(), a[1], KeyNames.toName(a[0]), a[2]);
    return 0;
  }

  /** {@code trap_GetGlconfig}: the real window size in this module's layout. */
  protected int glconfig(QvmInstance vm, int[] a) {
    GlConfigLayout.of(loaded.profile())
        .write(
            vm.memory(),
            a[0],
            GlConfigLayout.GlConfig.standard(renderer.width(), renderer.height()));
    return 0;
  }

  // ---------------------------------------------------------------- fonts

  /**
   * {@code trap_R_RegisterFont(name, pointSize, fontInfo_t *)}: the pre-rendered {@code
   * fonts/fontImage_<size>.dat} glyph table (point size 12 when unspecified), with every glyph's
   * shader registered. A missing font leaves the guest's struct untouched, as the original does.
   */
  protected int registerFont(QvmInstance vm, int[] a) {
    int pointSize = a[1] <= 0 ? 12 : a[1];
    String name = text(vm, a[0]);
    String path = "fonts/fontImage_" + pointSize + ".dat";
    Optional<byte[]> data =
        assets.lookup().read(path).filter(bytes -> bytes.length >= FontInfoLayout.BYTES);
    if (data.isEmpty()) {
      LOG.warnOnce("font:" + path, "R_RegisterFont: cannot find " + path);
      return 0;
    }
    ByteBuffer in = ByteBuffer.wrap(data.get()).order(ByteOrder.LITTLE_ENDIAN);
    List<GlyphInfoLayout.GlyphInfo> glyphs = new ArrayList<>(FontInfoLayout.GLYPH_COUNT);
    for (int i = 0; i < FontInfoLayout.GLYPH_COUNT; i++) {
      int at = FontInfoLayout.glyph(i);
      byte[] shaderBytes = new byte[GlyphInfoLayout.SHADER_NAME_BYTES];
      in.get(at + GlyphInfoLayout.SHADER_NAME, shaderBytes);
      int length = 0;
      while (length < shaderBytes.length && shaderBytes[length] != 0) length++;
      String shader =
          new String(shaderBytes, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1);
      int handle = shader.isEmpty() ? 0 : assets.shader(shader, ClientAssets.Usage.TWO_D);
      glyphs.add(
          new GlyphInfoLayout.GlyphInfo(
              in.getInt(at + GlyphInfoLayout.HEIGHT),
              in.getInt(at + GlyphInfoLayout.TOP),
              in.getInt(at + GlyphInfoLayout.BOTTOM),
              in.getInt(at + GlyphInfoLayout.PITCH),
              in.getInt(at + GlyphInfoLayout.X_SKIP),
              in.getInt(at + GlyphInfoLayout.IMAGE_WIDTH),
              in.getInt(at + GlyphInfoLayout.IMAGE_HEIGHT),
              in.getFloat(at + GlyphInfoLayout.S),
              in.getFloat(at + GlyphInfoLayout.T),
              in.getFloat(at + GlyphInfoLayout.S2),
              in.getFloat(at + GlyphInfoLayout.T2),
              handle,
              shader));
    }
    float scale = in.getFloat(FontInfoLayout.GLYPH_SCALE);
    FontInfoLayout.write(vm.memory(), a[2], glyphs, scale, name);
    fontCache.put(name, pointSize);
    return 0;
  }

  // ---------------------------------------------------------------- cinematics

  protected int cinPlay(QvmInstance vm, int[] a) {
    return cinematics.play(
        GuestText.readPath(vm.memory(), a[0]),
        a[1],
        a[2],
        a[3],
        a[4],
        a[5],
        services.milliseconds());
  }

  protected int cinStop(QvmInstance vm, int[] a) {
    return cinematics.stop(a[0]);
  }

  protected int cinRun(QvmInstance vm, int[] a) {
    cinematics.setVolume(services.cvars().value("s_volume"));
    return cinematics.run(a[0], services.milliseconds());
  }

  protected int cinDraw(QvmInstance vm, int[] a) {
    cinematics.draw(a[0], renderer.width(), renderer.height()).ifPresent(renderer::image);
    return 0;
  }

  protected int cinSetExtents(QvmInstance vm, int[] a) {
    cinematics.setExtents(a[0], a[1], a[2], a[3], a[4]);
    return 0;
  }

  // ---------------------------------------------------------------- scripts

  protected int pcAddGlobalDefine(QvmInstance vm, int[] a) {
    return scripts.addGlobalDefine(vm, a);
  }

  protected int pcLoadSource(QvmInstance vm, int[] a) {
    return scripts.load(vm, a);
  }

  protected int pcFreeSource(QvmInstance vm, int[] a) {
    return scripts.free(vm, a);
  }

  protected int pcReadToken(QvmInstance vm, int[] a) {
    return scripts.readToken(vm, a);
  }

  protected int pcSourceFileAndLine(QvmInstance vm, int[] a) {
    return scripts.sourceFileAndLine(vm, a);
  }

  /** Reads a {@code vec3_t}. */
  protected static Vec3f vec(QvmMemory memory, int address) {
    return VmStructs.readVec3(memory, address);
  }

  /** Binds {@code entry} only when its module's table maps it (retail lacks the later imports). */
  protected static void bind(
      Bindings bindings, VmImport entry, dev.bluevista.craftq3.engine.vmabi.ImportHandler handler) {
    bindings.bind(entry, handler);
  }
}

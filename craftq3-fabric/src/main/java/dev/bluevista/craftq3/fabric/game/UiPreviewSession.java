package dev.bluevista.craftq3.fabric.game;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.client.UiHost;
import dev.bluevista.craftq3.client.input.Q3Input;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.render.MaterialLibrary;
import dev.bluevista.craftq3.render.RenderScene;
import java.io.IOException;
import java.util.List;

/** Development GPU integration of the original UI, independent of a BSP or Minecraft world. */
public final class UiPreviewSession implements AutoCloseable {
  private final Pk3FileSystem files;
  private final AudioBackend audio;
  private final CvarSystem cvars = new CvarSystem();
  private final UiHost host = UiHost.disconnected();
  private final CommandSystem commands;
  private final RenderScene scene =
      new RenderScene("ui", List.of(), new RenderScene.Camera(new Vec3(0, 0, 0), 0, 0, 90));
  private MaterialLibrary materials;
  private Q3Ui ui;
  private int time;
  private boolean closed;

  public UiPreviewSession(Pk3FileSystem files, AudioBackend audio, int width, int height)
      throws IOException {
    this.files = files;
    this.audio = audio;
    cvars.register("sv_cheats", "0", CvarSystem.SYSTEMINFO | CvarSystem.ROM);
    commands = new CommandSystem(cvars, files, UiPreviewSession::print);
    var input = new Q3Input(cvars, commands, 0);
    commands.unknownHandler(
        command ->
            print("UI preview: engine command is not connected yet: " + command.argument(0)));
    try {
      materials = MaterialLibrary.load(files, scene);
      ui =
          new Q3Ui(
              files,
              cvars,
              commands,
              input.bindings(),
              audio,
              frame -> {},
              UiPreviewSession::print,
              host);
      ui.initialize(width, height);
      ui.setMenu(Q3Ui.Menu.MAIN);
    } catch (IOException | RuntimeException e) {
      try {
        close();
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  public RenderScene scene() {
    return scene;
  }

  public MaterialLibrary materials() {
    return materials;
  }

  public Q3Ui ui() {
    return ui;
  }

  public int time() {
    return time;
  }

  public AudioBackend.Diagnostics audioDiagnostics() {
    return audio.diagnostics();
  }

  public CgameFrame frame(int elapsed, int width, int height) {
    if (closed) throw new IllegalStateException("UI preview closed");
    time = Math.addExact(time, Math.clamp(elapsed, 0, 200));
    commands.runFrame(1024);
    if ((host.keyCatcher() & 2) == 0) ui.setMenu(Q3Ui.Menu.MAIN);
    audio.beginFrame();
    CgameFrame result = ui.frame(time, width, height);
    float volume = cvars.number("s_volume");
    audio.volume(Float.isFinite(volume) ? Math.clamp(volume, 0, 1) : 0);
    audio.endFrame(
        new AudioBackend.Listener(new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1)));
    return result;
  }

  private static void print(String text) {
    CraftQ3Client.LOGGER.info("Q3 UI: {}", text.stripTrailing());
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    IOException failure = null;
    for (AutoCloseable resource : new AutoCloseable[] {ui, audio, files}) {
      if (resource == null) continue;
      try {
        resource.close();
      } catch (Exception e) {
        if (failure == null) failure = new IOException("UI preview cleanup failed", e);
        else failure.addSuppressed(e);
      }
    }
    if (failure != null) throw failure;
  }
}

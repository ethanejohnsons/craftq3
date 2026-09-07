package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.FrameTimings;
import dev.bluevista.craftq3.render.MaterialLibrary;
import dev.bluevista.craftq3.render.RenderScene;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Independent noclip inspection camera; this is not Q3 player movement. */
public final class BspScreen extends Screen implements QuakeView {
  private final Blaze3dRenderBackend backend;
  private final Set<Integer> keys = new HashSet<>();
  private dev.bluevista.craftq3.fabric.audio.AudioSmoke audioSmoke;
  private MovieRenderSmoke movieSmoke;
  private CinematicAvSmoke cinematicSmoke;
  private RenderScene scene;
  private volatile RenderScene extracted;
  private int frames;
  private boolean lightEnabled;
  private final FrameTimings timings = new FrameTimings();
  private boolean diagnostics =
      !Boolean.getBoolean("craftq3.capture") || Boolean.getBoolean("craftq3.capturePanel");
  private long lastFrame = System.nanoTime();

  public BspScreen(RenderScene scene, MaterialLibrary materials) {
    super(Component.literal("CraftQ3: " + scene.mapName()));
    backend = new Blaze3dRenderBackend(materials);
    this.scene = scene;
    this.extracted = scene;
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    if (!minecraft.isWindowActive()) keys.clear();
    long now = System.nanoTime();
    double dt = Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
    lastFrame = now;
    var camera = scene.camera();
    float yaw = camera.yaw() + (float) (axis(GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_LEFT) * -90 * dt);
    float pitch =
        Math.clamp(
            camera.pitch() + (float) (axis(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP) * 90 * dt),
            -89,
            89);
    double angle = Math.toRadians(yaw),
        speed = (keys.contains(GLFW.GLFW_KEY_LEFT_SHIFT) ? 800 : 320) * dt;
    double forward = axis(GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S),
        right = axis(GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A);
    Vec3 offset =
        new Vec3(
                Math.cos(angle) * forward + Math.sin(angle) * right,
                Math.sin(angle) * forward - Math.cos(angle) * right,
                axis(GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_C))
            .scale(speed);
    scene =
        scene.withCamera(
            new RenderScene.Camera(
                camera.origin().add(offset), yaw, pitch, camera.horizontalFov()));
    scene =
        scene.withLights(
            lightEnabled
                ? java.util.List.of(
                    new RenderScene.DynamicLight(
                        scene.camera().origin(), 384, new Vec3(1, .85, .65)))
                : java.util.List.of());
    extracted = scene;
    if (dev.bluevista.craftq3.fabric.audio.AudioSmoke.enabled()) {
      if (audioSmoke == null)
        audioSmoke = new dev.bluevista.craftq3.fabric.audio.AudioSmoke(minecraft);
      audioSmoke.tick(scene.camera());
    }
    if (diagnostics) drawDiagnostics(graphics);
  }

  private void drawDiagnostics(GuiGraphicsExtractor graphics) {
    graphics.pose().pushMatrix();
    graphics.pose().scale(0.65f);
    var timing = timings.snapshot();
    var p = scene.camera().origin();
    var lines = new ArrayList<String>();
    lines.add(
        String.format(Locale.ROOT, "CraftQ3   %.0f FPS   %.2f ms", timing.fps(), timing.meanMs()));
    lines.add(String.format(Locale.ROOT, "Worst frame (120): %.2f ms", timing.worstMs()));
    lines.add(
        scene.mapName()
            + "  |  "
            + com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName());
    var stats = backend.statistics();
    lines.add(
        "Surfaces: "
            + stats.visibleSurfaces()
            + "/"
            + stats.totalSurfaces()
            + "   Triangles: "
            + stats.triangles());
    lines.add(
        "Main draws: "
            + stats.drawCalls()
            + String.format(Locale.ROOT, "   CPU prepare: %.2f ms", stats.prepareMs()));
    lines.add(
        "Leaf: "
            + stats.leaf()
            + "   Cluster: "
            + stats.cluster()
            + "   PVS "
            + (backend.pvsEnabled() ? (stats.pvsApplied() ? "on" : "conservative") : "off"));
    lines.add(
        "Images: "
            + stats.images()
            + "   Lightmaps: "
            + stats.lightmaps()
            + "   Warnings: "
            + stats.warnings());
    lines.add("View: " + backend.mode() + (backend.frozen() ? "   Animation paused" : ""));
    lines.add(
        String.format(
            Locale.ROOT,
            "Camera: %.0f %.0f %.0f  yaw %.0f pitch %.0f",
            p.x(),
            p.y(),
            p.z(),
            scene.camera().yaw(),
            scene.camera().pitch()));
    lines.add("F8 diagnostics   F6 view   F7 PVS");
    lines.add("F9 pause animation   F10 light   Esc return");
    lines.add("WASD move   Space/C rise/fall   Shift fast");
    lines.add("Arrow keys look");
    int panelWidth =
        Math.min(
            graphics.guiWidth() - 12, lines.stream().mapToInt(font::width).max().orElse(260) + 16);
    graphics.fill(6, 6, 6 + panelWidth, 16 + lines.size() * 11, 0xd918202b);
    graphics.fill(6, 6, 8, 16 + lines.size() * 11, 0xff65c5a8);
    graphics.enableScissor(8, 8, 4 + panelWidth, 16 + lines.size() * 11);
    for (int i = 0; i < lines.size(); i++)
      graphics.text(font, lines.get(i), 14, 12 + i * 11, i == 0 ? 0xff82e5bd : 0xffe0e8ef, false);
    graphics.disableScissor();
    graphics.pose().popMatrix();
  }

  private int axis(int positive, int negative) {
    return (keys.contains(positive) ? 1 : 0) - (keys.contains(negative) ? 1 : 0);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.key() == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(event);
    if (event.key() == GLFW.GLFW_KEY_F8) {
      if (keys.add(event.key())) diagnostics = !diagnostics;
      return true;
    }
    if (event.key() == GLFW.GLFW_KEY_F6
        || event.key() == GLFW.GLFW_KEY_F7
        || event.key() == GLFW.GLFW_KEY_F9
        || event.key() == GLFW.GLFW_KEY_F10) {
      if (keys.add(event.key())) {
        if (event.key() == GLFW.GLFW_KEY_F6) backend.cycleMode();
        else if (event.key() == GLFW.GLFW_KEY_F7) backend.togglePvs();
        else if (event.key() == GLFW.GLFW_KEY_F9) backend.toggleAnimation();
        else lightEnabled = !lightEnabled;
      }
      return true;
    }
    keys.add(event.key());
    return true;
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    keys.remove(event.key());
    return true;
  }

  @Override
  public void extractBackground(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {}

  @Override
  public boolean isPauseScreen() {
    return true;
  }

  public void drawFrame() {
    timings.frame(System.nanoTime());
    if (CinematicAvSmoke.enabled()) {
      if (cinematicSmoke == null) cinematicSmoke = new CinematicAvSmoke(minecraft, backend);
      cinematicSmoke.render(minecraft, extracted);
      return;
    }
    if (MovieRenderSmoke.enabled()) {
      if (movieSmoke == null) movieSmoke = new MovieRenderSmoke(backend);
      movieSmoke.render(minecraft, extracted);
      return;
    }
    backend.render(extracted, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
  }

  public void endFrame() {
    if (++frames == 180
        && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.capture")) {
      if (audioSmoke != null && !audioSmoke.finished()
          || movieSmoke != null && !movieSmoke.finished()
          || cinematicSmoke != null && !cinematicSmoke.finished()) {
        frames--;
        return;
      }
      String name =
          "craftq3-"
              + scene.mapName().replace("maps/", "").replace(".bsp", "")
              + "-"
              + backend.mode().name().toLowerCase(Locale.ROOT)
              + "-"
              + com.mojang.blaze3d.systems.RenderSystem.getDevice()
                  .getDeviceInfo()
                  .backendName()
                  .toLowerCase(java.util.Locale.ROOT)
              + ".png";
      net.minecraft.client.Screenshot.grab(
          net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile(),
          name,
          minecraft.gameRenderer.mainRenderTarget(),
          1,
          message -> {
            dev.bluevista.craftq3.fabric.CraftQ3Client.LOGGER.info(
                "CraftQ3 capture: {}", message.getString());
            minecraft.execute(
                () -> {
                  minecraft.gui.setScreen(null);
                  minecraft.stop();
                });
          });
    }
  }

  @Override
  public void removed() {
    keys.clear();
    if (audioSmoke != null) audioSmoke.close();
    backend.close();
    if (movieSmoke != null) movieSmoke.close();
    if (cinematicSmoke != null) cinematicSmoke.close();
  }
}

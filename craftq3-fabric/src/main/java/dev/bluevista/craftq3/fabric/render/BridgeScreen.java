package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.fabric.CraftQ3Client;
import dev.bluevista.craftq3.fabric.bridge.BridgeGame;
import dev.bluevista.craftq3.fabric.bridge.MinecraftTerrain;
import dev.bluevista.craftq3.render.CgameFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import org.lwjgl.glfw.GLFW;

/**
 * Experimental local-world bridge: Minecraft world rendering with original Quake
 * input/viewmodel/HUD.
 */
public final class BridgeScreen extends Screen implements QuakeInputView {
  private final BridgeGame game;
  private final MinecraftTerrain terrain;
  private final Blaze3dRenderBackend backend;
  private final net.minecraft.client.player.LocalPlayer player;
  private final net.minecraft.client.multiplayer.ClientLevel level;
  private final net.minecraft.client.server.IntegratedServer server;
  private volatile GameType previousMode;
  private dev.bluevista.craftq3.fabric.bridge.BridgePlayerController controller;
  private final java.util.concurrent.CompletableFuture<Void> controllerReady;
  private final boolean previousClientPhysics;
  private CgameFrame frame;
  private CgameFrame.Refdef worldView;
  private org.joml.Matrix4f minecraftProjection;
  private net.minecraft.world.phys.Vec3 cameraPosition;
  private dev.bluevista.craftq3.fabric.bridge.BridgePlayerShape playerShape;
  private boolean captured, closed, consoleOpen, consoleToggleDown;
  private final dev.bluevista.craftq3.client.input.ConsoleEditor console =
      new dev.bluevista.craftq3.client.input.ConsoleEditor();
  private int consoleScroll;
  private long previous = System.nanoTime();
  private int smokeFrames;
  private net.minecraft.world.phys.Vec3 smokeStart;
  private boolean smokeCaptured;

  private java.util.function.BiConsumer<BridgeGame, String> quakeAction =
      (game, map) -> game.transferMessage("Quake map transfer is unavailable in this host.");
  private final java.util.concurrent.CompletableFuture<Void> returnComplete =
      new java.util.concurrent.CompletableFuture<>();

  public void quakeAction(java.util.function.BiConsumer<BridgeGame, String> action) {
    quakeAction = java.util.Objects.requireNonNull(action);
  }

  public java.util.concurrent.CompletableFuture<Void> returnComplete() {
    return returnComplete;
  }

  public BridgeScreen(Minecraft mc, BridgeGame game, MinecraftTerrain terrain) {
    this(mc, game, terrain, java.util.concurrent.CompletableFuture.completedFuture(null));
  }

  public BridgeScreen(
      Minecraft mc,
      BridgeGame game,
      MinecraftTerrain terrain,
      java.util.concurrent.CompletableFuture<Void> audioReady) {
    super(Component.literal("Quake in Minecraft"));
    this.game = game;
    playerShape = game.playerShape();
    var initialShape = playerShape;
    this.terrain = terrain;
    player = mc.player;
    level = mc.level;
    server = mc.getSingleplayerServer();
    backend = new Blaze3dRenderBackend(game.materials());
    if (server == null || player == null)
      throw new IllegalStateException("Bridge requires a local Minecraft world");
    previousClientPhysics = player.noPhysics;
    var id = player.getUUID();
    controllerReady =
        server
            .submit(
                () -> {
                  var host = java.util.Objects.requireNonNull(server.getPlayerList().getPlayer(id));
                  previousMode = host.gameMode.getGameModeForPlayer();
                  controller =
                      dev.bluevista.craftq3.fabric.bridge.BridgePlayerController.begin(host);
                  controller.shape(initialShape);
                  return (Void) null;
                })
            .thenCombine(audioReady, (controller, audio) -> null);
  }

  public CgameFrame.Refdef worldView() {
    return worldView;
  }

  public org.joml.Matrix4f cameraRotation() {
    return BridgeProjection.rotation(worldView, terrain.transform());
  }

  public void projection(org.joml.Matrix4f value) {
    minecraftProjection = new org.joml.Matrix4f(value);
  }

  public void drawWorld(net.minecraft.client.renderer.state.level.CameraRenderState camera) {
    if (closed || frame == null || worldView == null) return;
    var projection =
        BridgeProjection.world(
            minecraftProjection == null ? camera.projectionMatrix : minecraftProjection,
            camera.viewRotationMatrix,
            camera.pos,
            terrain.transform());
    backend.renderMinecraftWorld(
        game.scene(),
        BridgeDepthSmoke.frame(BridgeProjection.partition(frame, true)),
        projection,
        minecraft.getWindow().getWidth(),
        minecraft.getWindow().getHeight());
  }

  public net.minecraft.world.phys.Vec3 cameraPosition() {
    return cameraPosition;
  }

  public dev.bluevista.craftq3.fabric.bridge.BridgePlayerShape playerShape(
      net.minecraft.client.player.LocalPlayer candidate) {
    return !closed && candidate == player ? playerShape : null;
  }

  @Override
  public boolean replacesMinecraftWorld() {
    return false;
  }

  @Override
  public void prepareFrame() {
    if (closed) return;
    if (!controllerReady.isDone()) return;
    try {
      controllerReady.join();
    } catch (java.util.concurrent.CompletionException error) {
      CraftQ3Client.LOGGER.error("Could not prepare bridge player; returning to Minecraft", error);
      onClose();
      return;
    }
    String mapRequest = game.takeQuakeRequest();
    if (mapRequest != null) {
      inputFocusLost();
      quakeAction.accept(game, mapRequest);
      return;
    }
    if (game.exitRequested()) {
      onClose();
      return;
    }
    if (minecraft.player != player || minecraft.level != level) {
      onClose();
      return;
    }
    boolean smoke = System.getProperty("craftq3.bridgeSmokeWorld") != null;
    boolean active =
        (minecraft.isWindowActive() || smoke)
            && !dev.bluevista.craftq3.fabric.bridge.BridgeTravelSmoke.unfocused(smokeFrames);
    if (active && !captured && !consoleOpen) capture(true);
    if (!active) inputFocusLost();
    long now = System.nanoTime();
    // This screen does not pause the native world. Keep both simulations advancing when
    // focus is lost, while inputFocusLost releases held controls above.
    int elapsed = smoke ? 16 : Math.clamp((now - previous) / 1_000_000, 0, 200);
    previous = now;
    try {
      if (System.getProperty("craftq3.bridgeSmokeWorld") != null) {
        dev.bluevista.craftq3.fabric.bridge.BridgeRecoverySmoke.checkpoint(minecraft, smokeFrames);
        if (BridgeConsoleSmoke.enabled()) BridgeConsoleSmoke.step(this, game, smokeFrames);
        if (dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke.enabled()) {
          dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke.step(minecraft, game, smokeFrames);
        } else {
          if (smokeFrames == 10) {
            smokeStart = player.position();
            game.input().key('w', true, game.time());
          }
          if (smokeFrames == 80) game.input().key(32, true, game.time());
          if (smokeFrames == 90) game.input().key(32, false, game.time());
          if (smokeFrames == 170) game.input().key('w', false, game.time());
        }
      }
      frame =
          game.frame(elapsed, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
      playerShape = game.playerShape();
      if (BridgeConsoleSmoke.enabled()) BridgeConsoleSmoke.observe(frame);
      var view =
          frame.commands().stream()
              .filter(CgameFrame.View.class::isInstance)
              .map(CgameFrame.View.class::cast)
              .filter(v -> (v.refdef().rdflags() & CgameFrame.RDF_NOWORLDMODEL) == 0)
              .findFirst();
      worldView = view.map(CgameFrame.View::refdef).orElse(null);
      if (view.isPresent()) {
        var ref = view.get().refdef();
        var eye = terrain.transform().toMinecraft(ref.origin());
        cameraPosition = new net.minecraft.world.phys.Vec3(eye.x(), eye.y(), eye.z());
        var feet = game.feet();
        player.setPos(feet.x(), feet.y(), feet.z());
        var aim = game.viewAngles();
        player.setYRot((float) (-90 - aim.y()));
        player.setXRot((float) aim.x());
        player.setOldPosAndRot();
      }
    } catch (RuntimeException failure) {
      CraftQ3Client.LOGGER.error("Minecraft bridge failed", failure);
      onClose();
      if (smoke) minecraft.stop();
    }
  }

  @Override
  public void endFrame() {
    if (System.getProperty("craftq3.bridgeSmokeWorld") == null || closed || frame == null) return;
    if (BridgeDepthSmoke.enabled()) BridgeDepthSmoke.capture(minecraft, worldView);
    BridgeCameraSmoke.observe(minecraft, this, game, frame, smokeFrames);
    dev.bluevista.craftq3.fabric.bridge.BridgePickupSmoke.observe(
        minecraft, game, frame, smokeFrames);
    boolean combatSmoke = dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke.enabled();
    if (++smokeFrames
            == (dev.bluevista.craftq3.fabric.bridge.BridgeHitboxSmoke.enabled()
                    || dev.bluevista.craftq3.fabric.bridge.BridgeFireballSmoke.enabled()
                ? 1200
                : dev.bluevista.craftq3.fabric.bridge.BridgeFireSmoke.enabled()
                        || dev.bluevista.craftq3.fabric.bridge.BridgeFluidSmoke.enabled()
                        || dev.bluevista.craftq3.fabric.bridge.BridgeBlazeSmoke.enabled()
                    ? 2400
                    : dev.bluevista.craftq3.fabric.bridge.BridgeTravelSmoke.enabled()
                        ? 3200
                        : dev.bluevista.craftq3.fabric.bridge.BridgePickupSmoke.enabled()
                            ? 700
                            : BridgeDepthSmoke.enabled()
                                ? 400
                                : dev.bluevista.craftq3.fabric.bridge.BridgeOutgoingImpulseSmoke
                                            .enabled()
                                        || dev.bluevista.craftq3.fabric.bridge.BridgeExplosionSmoke
                                            .enabled()
                                        || dev.bluevista.craftq3.fabric.bridge.BridgeRangedSmoke
                                            .enabled()
                                    ? 1000
                                    : BridgeCameraSmoke.enabled()
                                            || dev.bluevista.craftq3.fabric.bridge
                                                .BridgeKnockbackSmoke.enabled()
                                            || dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke
                                                .incoming()
                                        ? 650
                                        : combatSmoke ? 260 : 200)
        && !smokeCaptured) {
      smokeCaptured = true;
      String combatResult =
          combatSmoke ? dev.bluevista.craftq3.fabric.bridge.BridgeCombatSmoke.result(game) : "";
      if (!combatSmoke && (smokeStart == null || player.position().distanceTo(smokeStart) < 0.1))
        throw new IllegalStateException("Bridge smoke did not move");
      net.minecraft.client.Screenshot.grab(
          minecraft.gameDirectory,
          "craftq3-bridge.png",
          minecraft.gameRenderer.mainRenderTarget(),
          1,
          message -> {
            CraftQ3Client.LOGGER.info("Bridge capture: {}", message.getString());
            minecraft.execute(
                () -> {
                  onClose();
                  server.execute(
                      () -> {
                        var host = server.getPlayerList().getPlayer(player.getUUID());
                        if (host != null)
                          dev.bluevista.craftq3.fabric.bridge.BridgeHitboxSmoke.restored(host);
                        boolean restored =
                            host != null && host.gameMode.getGameModeForPlayer() == previousMode;
                        minecraft.execute(
                            () -> {
                              dev.bluevista.craftq3.fabric.bridge.BridgeHitboxSmoke.restored(
                                  player);
                              if (!restored)
                                throw new IllegalStateException(
                                    "Bridge did not restore Minecraft game mode");
                              try {
                                java.nio.file.Files.writeString(
                                    minecraft
                                        .gameDirectory
                                        .toPath()
                                        .resolve("craftq3-bridge.result"),
                                    "PASS "
                                        + combatResult
                                        + " moved="
                                        + (smokeStart == null
                                            ? 0
                                            : player.position().distanceTo(smokeStart))
                                        + " restoredMode="
                                        + previousMode
                                        + "\n");
                              } catch (java.io.IOException failure) {
                                throw new RuntimeException(failure);
                              }
                              minecraft.stop();
                            });
                      });
                });
          });
    }
  }

  @Override
  public void drawFrame() {
    if (!closed && frame != null)
      backend.render(
          game.scene(),
          BridgeProjection.partition(frame, false),
          minecraft.getWindow().getWidth(),
          minecraft.getWindow().getHeight(),
          false);
    if (!closed && consoleOpen)
      backend.render(
          game.scene(),
          game.consoleFrame(
              console.text(),
              console.cursor(),
              minecraft.getWindow().getWidth(),
              minecraft.getWindow().getHeight(),
              consoleScroll),
          minecraft.getWindow().getWidth(),
          minecraft.getWindow().getHeight(),
          false);
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
    if (consoleOpen) return;
    graphics.text(
        font, "Quake movement · ` console · Esc returns to Minecraft", 8, 8, 0xffffffff, false);
  }

  @Override
  public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {}

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT) {
      if (!consoleToggleDown) {
        consoleToggleDown = true;
        consoleOpen = !consoleOpen;
        game.input().releaseAll(game.time());
        capture(!consoleOpen);
      }
      return true;
    }
    if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
      if (consoleOpen) {
        consoleOpen = false;
        capture(true);
      } else onClose();
      return true;
    }
    if (consoleOpen) {
      switch (event.key()) {
        case GLFW.GLFW_KEY_ENTER -> {
          console.accept().ifPresent(game::command);
          consoleScroll = 0;
        }
        case GLFW.GLFW_KEY_BACKSPACE -> console.backspace();
        case GLFW.GLFW_KEY_DELETE -> console.delete();
        case GLFW.GLFW_KEY_LEFT -> console.left();
        case GLFW.GLFW_KEY_RIGHT -> console.right();
        case GLFW.GLFW_KEY_HOME -> console.home();
        case GLFW.GLFW_KEY_END -> console.end();
        case GLFW.GLFW_KEY_UP -> console.previous();
        case GLFW.GLFW_KEY_DOWN -> console.next();
        case GLFW.GLFW_KEY_PAGE_UP -> consoleScroll = Math.min(65536, consoleScroll + 8);
        case GLFW.GLFW_KEY_PAGE_DOWN -> consoleScroll = Math.max(0, consoleScroll - 8);
        case GLFW.GLFW_KEY_TAB ->
            console.completionPrefix().ifPresent(prefix -> console.complete(game.complete(prefix)));
        default -> {}
      }
      return true;
    }
    int key = Q3Screen.quakeKey(event.key());
    if (key >= 0) game.input().key(key, true, game.time());
    return true;
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    if (event.key() == GLFW.GLFW_KEY_GRAVE_ACCENT) consoleToggleDown = false;
    int key = Q3Screen.quakeKey(event.key());
    if (key >= 0) game.input().key(key, false, game.time());
    return true;
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    if (consoleOpen && event.codepoint() != '`' && event.codepoint() != '~')
      console.insert(event.codepoint());
    return true;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
    if (consoleOpen) return true;
    capture(true);
    int key = Q3Screen.mouseKey(event.button());
    if (key >= 0) game.input().key(key, true, game.time());
    return true;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    int key = Q3Screen.mouseKey(event.button());
    if (key >= 0) game.input().key(key, false, game.time());
    return true;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (consoleOpen) {
      consoleScroll = (int) Math.clamp(consoleScroll + Math.signum(vertical) * 3, 0, 65536);
      return true;
    }
    if (vertical != 0) {
      int key = vertical > 0 ? 184 : 183;
      game.input().key(key, true, game.time());
      game.input().key(key, false, game.time());
    }
    return true;
  }

  @Override
  public boolean cursorCaptured() {
    return captured;
  }

  @Override
  public void mouseDelta(double dx, double dy) {
    if (captured) game.input().mouse(dx, dy);
  }

  @Override
  public void inputFocusLost() {
    game.input().releaseAll(game.time());
    consoleToggleDown = false;
    capture(false);
  }

  private void capture(boolean value) {
    if (captured == value) return;
    captured = value;
    GLFW.glfwSetInputMode(
        minecraft.getWindow().handle(),
        GLFW.GLFW_CURSOR,
        value ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(null);
  }

  @Override
  public void removed() {
    if (closed) return;
    inputFocusLost();
    closed = true;
    BridgeCameraSmoke.closed(minecraft);
    player.noPhysics = previousClientPhysics;
    var id = player.getUUID();
    server.execute(
        () -> {
          try {
            if (controller != null) controller.close();
            returnComplete.complete(null);
          } catch (RuntimeException failure) {
            CraftQ3Client.LOGGER.error("Could not restore Minecraft player after bridge", failure);
            returnComplete.completeExceptionally(failure);
          }
        });
    try {
      backend.close();
    } finally {
      try {
        game.close();
      } catch (java.io.IOException e) {
        CraftQ3Client.LOGGER.warn("Bridge close", e);
      }
    }
  }
}

package dev.bluevista.craftq3.mc.devharness.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeGame;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import dev.bluevista.craftq3.mc.fabric.building.BuildingDimension;
import dev.bluevista.craftq3.mc.fabric.client.CraftQ3ClientMod;
import dev.bluevista.craftq3.mc.fabric.client.bridge.BridgeScreen;
import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import dev.bluevista.craftq3.mc.hybrid.building.BlockFace;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingQueries;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegions;
import dev.bluevista.craftq3.mc.hybrid.building.SupportShape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bridge and building modes in a real singleplayer world with the maintainer's installation
 * ({@code CRAFTQ3_Q3ROOT}; skipped without it), on the backend the run selected:
 *
 * <ol>
 *   <li><b>Bridge</b>: {@code /q3 bridge fresh} typed in chat opens the bridge; its Quake session
 *       connects to the integrated server over the payload transport and draws the cgame frame
 *       (weapon, HUD) over Minecraft's rendered world; the screenshot shows both. Holding fire at a
 *       zombie in front hurts it (Minecraft health drops). Shift+Escape ends the bridge and the
 *       server player is restored. Session and backend frame times are reported.
 *   <li><b>Building</b>: {@code /q3 build q3dm17} takes the player into the building dimension; the
 *       client loads the region and renders the BSP inside Minecraft's view; a stone block placed
 *       on a BSP floor next to the spawn renders with it (screenshot). {@code /q3 leave} returns.
 * </ol>
 *
 * <p>With an {@code eula.txt} the operator agreed to in the run directory, the bridge is also
 * entered on a dedicated server started in this process (a real network connection).
 *
 * <p>Screenshots and {@code craftq3-hybrid-report-<backend>.txt} go to the run directory.
 */
public final class Q3HybridClientTest implements FabricClientGameTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(Q3HybridClientTest.class);
  private static final int WIDTH = 1920, HEIGHT = 1080;
  private static final int SECOND_TICKS = 20;

  private final List<String> failures = new ArrayList<>();
  private final StringBuilder report = new StringBuilder();

  @Override
  public void runTest(ClientGameTestContext context) {
    context.waitForScreen(TitleScreen.class);
    String backendName =
        context.computeOnClient(c -> RenderSystem.getDevice().getDeviceInfo().backendName());
    String tag = backendName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    if ("opengl".equals(tag)) context.getInput().resizeWindow(WIDTH, HEIGHT);
    context.waitTicks(3);
    report.append("CraftQ3 hybrid modes on ").append(backendName).append('\n');
    String root = System.getenv("CRAFTQ3_Q3ROOT");
    if (root == null || root.isBlank() || !Files.isDirectory(Path.of(root))) {
      LOGGER.warn("CRAFTQ3_Q3ROOT is not set; skipping the hybrid client test");
      report.append("skipped: no CRAFTQ3_Q3ROOT\n");
      finish(tag);
      return;
    }
    try (TestSingleplayerContext world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      prepareWorld(world);
      context.waitTicks(20);
      try {
        bridge(context, world, tag);
        building(context, world, tag);
      } catch (RuntimeException | AssertionError e) {
        context.takeScreenshot("craftq3-hybrid-" + tag + "-failure");
        failures.add("aborted: " + e);
        LOGGER.error("Hybrid test aborted", e);
        context.runOnClient(
            c -> {
              if (c.gui.screen() != null) c.gui.setScreen(null);
            });
      }
    }
    // A dedicated server only starts once its operator has agreed to the Minecraft EULA
    // (eula.txt in the run directory); this test never agrees on anyone's behalf.
    if (!eulaAccepted()) {
      report.append(
          "dedicated server: skipped (no eula.txt with eula=true in the run directory)\n");
      finish(tag);
      return;
    }
    try {
      dedicated(context, tag);
    } catch (RuntimeException | AssertionError e) {
      context.takeScreenshot("craftq3-hybrid-" + tag + "-dedicated-failure");
      failures.add("dedicated aborted: " + e);
      LOGGER.error("Hybrid dedicated-server step aborted", e);
    }
    finish(tag);
  }

  // ------------------------------------------------------------------ steps

  /**
   * The same bridge through a dedicated server (in this process, a real network connection): the
   * Quake session reaches the server's bridge game over the payload transport and renders.
   */
  private void dedicated(ClientGameTestContext context, String tag) {
    try (var server = context.worldBuilder().createServer();
        var connection = server.connect()) {
      connection.waitForChunksRender();
      server.runOnServer(
          s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
            ServerLevel level = player.level();
            BlockPos feet = player.blockPosition();
            for (int x = -6; x <= 6; x++)
              for (int z = -6; z <= 6; z++) {
                level.setBlockAndUpdate(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++)
                  level.setBlockAndUpdate(feet.offset(x, y, z), Blocks.AIR.defaultBlockState());
              }
          });
      context.waitTicks(20);
      TestInput input = context.getInput();
      input.pressKey(options -> options.keyChat);
      context.waitForScreen(ChatScreen.class);
      input.typeChars("/q3 bridge fresh");
      input.pressKey(GLFW.GLFW_KEY_ENTER);
      context.waitForScreen(BridgeScreen.class);
      waitFor(
          context,
          "the bridge session reaches the dedicated server's game",
          c -> {
            BridgeScreen screen = BridgeScreen.current(c);
            return screen != null
                && screen.session() != null
                && screen.session().state() == SessionState.REMOTE
                && screen.presenter() != null
                && screen.presenter().framesDrawn() > 60;
          },
          90 * SECOND_TICKS);
      context.waitTicks(20);
      BufferedImage view = screenshot(context, "craftq3-bridge-dedicated-" + tag);
      check(deviation(view) > 10, "the dedicated-server bridge view looks flat");
      boolean bridged =
          server.computeOnServer(
              s -> BridgedPlayer.of(s.getPlayerList().getPlayers().getFirst()) != null);
      check(bridged, "the dedicated server did not bridge the player");
      long sent = context.computeOnClient(c -> BridgeScreen.current(c).transport().datagramsSent());
      long received =
          context.computeOnClient(c -> BridgeScreen.current(c).transport().datagramsReceived());
      report.append(
          String.format(
              Locale.ROOT,
              "dedicated server: bridge connected over payloads (%d datagrams sent, %d received)%n",
              sent,
              received));
      input.holdShift();
      input.pressKey(GLFW.GLFW_KEY_ESCAPE);
      input.releaseShift();
      context.waitFor(c -> c.gui.screen() == null, 10 * SECOND_TICKS);
      context.waitTicks(10);
      boolean left =
          server.computeOnServer(
              s -> BridgedPlayer.of(s.getPlayerList().getPlayers().getFirst()) == null);
      check(left, "the dedicated server kept the player bridged");
    }
  }

  private static void prepareWorld(TestSingleplayerContext world) {
    world
        .getServer()
        .runOnServer(
            server -> {
              server.setDifficulty(Difficulty.NORMAL, true);
              ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
              ServerLevel level = player.level();
              BlockPos feet = player.blockPosition();
              // A clear stone pad, an arena wall behind, nothing in front.
              for (int x = -10; x <= 10; x++)
                for (int z = -10; z <= 16; z++) {
                  level.setBlockAndUpdate(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                  for (int y = 0; y < 6; y++)
                    level.setBlockAndUpdate(feet.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
              for (int x = -3; x <= 3; x++)
                for (int y = 0; y < 3; y++)
                  level.setBlockAndUpdate(
                      feet.offset(x, y, -4), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
              player.snapTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
              player.connection.teleport(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0, 0);
            });
  }

  private void bridge(ClientGameTestContext context, TestSingleplayerContext world, String tag) {
    TestInput input = context.getInput();
    input.pressKey(options -> options.keyChat);
    context.waitForScreen(ChatScreen.class);
    input.typeChars("/q3 bridge fresh");
    input.pressKey(GLFW.GLFW_KEY_ENTER);
    context.waitForScreen(BridgeScreen.class);
    long start = System.nanoTime();
    waitFor(
        context,
        "the bridge session reaches the game",
        c -> {
          BridgeScreen screen = BridgeScreen.current(c);
          return screen != null
              && screen.session() != null
              && screen.session().state() == SessionState.REMOTE
              && screen.pose() != null
              && screen.presenter() != null
              && screen.presenter().framesDrawn() > 60;
        },
        90 * SECOND_TICKS);
    report.append(
        String.format(
            Locale.ROOT,
            "bridge: /q3 bridge to the first in-game frames: %d ms%n",
            (System.nanoTime() - start) / 1_000_000));
    boolean bridged =
        world
            .getServer()
            .computeOnServer(
                server -> BridgedPlayer.of(server.getPlayerList().getPlayers().getFirst()) != null);
    check(bridged, "the server player is not bridged");
    context.waitTicks(40);
    BufferedImage view = screenshot(context, "craftq3-bridge-" + tag);
    report.append("bridge screenshot: craftq3-bridge-").append(tag).append(".png\n");
    check(deviation(view) > 10, "the bridge view looks flat");

    // A zombie five blocks ahead, in the camera's line.
    int zombie =
        world
            .getServer()
            .computeOnServer(
                server -> {
                  ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                  ServerLevel level = player.level();
                  Zombie z = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
                  z.snapTo(player.getX(), player.getY(), player.getZ() + 5, 180, 0);
                  z.setNoAi(true);
                  z.setPersistenceRequired();
                  level.addFreshEntity(z);
                  return z.getId();
                });
    context.waitTicks(10);
    float before = zombieHealth(world, zombie);
    screenshot(context, "craftq3-bridge-" + tag + "-target");
    input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    context.waitTicks(2 * SECOND_TICKS);
    input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    context.waitTicks(5);
    screenshot(context, "craftq3-bridge-" + tag + "-fired");
    float after = zombieHealth(world, zombie);
    report.append(
        String.format(
            Locale.ROOT, "bridge: zombie health %.2f -> %.2f after firing%n", before, after));
    check(after < before, "firing did not hurt the zombie");

    long[] work = context.computeOnClient(c -> BridgeScreen.current(c).recentWorkNanos(600));
    long backendCpu =
        context.computeOnClient(c -> BridgeScreen.current(c).presenter().lastCpuNanos());
    report.append(
        String.format(
            Locale.ROOT,
            "bridge client: session frame p50 %.2f ms, p99 %.2f ms (%d frames); backend CPU %.2f ms; fps %d%n",
            percentile(work, 50) / 1e6,
            percentile(work, 99) / 1e6,
            work.length,
            backendCpu / 1e6,
            context.computeOnClient(Minecraft::getFps)));
    long serverTick =
        world
            .getServer()
            .computeOnServer(
                server -> {
                  BridgeGame game =
                      CraftQ3Level.of(server.getPlayerList().getPlayers().getFirst().level())
                          .craftq3$bridge();
                  return game == null ? -1L : game.averageTickNanos();
                });
    report.append(
        String.format(Locale.ROOT, "bridge server: average tick %.3f ms%n", serverTick / 1e6));

    transfers(context, tag);

    input.holdShift();
    input.pressKey(GLFW.GLFW_KEY_ESCAPE);
    input.releaseShift();
    context.waitFor(c -> c.gui.screen() == null, 10 * SECOND_TICKS);
    context.waitTicks(10);
    boolean restored =
        world
            .getServer()
            .computeOnServer(
                server -> BridgedPlayer.of(server.getPlayerList().getPlayers().getFirst()) == null);
    check(restored, "the bridge did not end after Shift+Escape");
    world.getServer().runOnServer(server -> discard(server, zombie));
    report.append("bridge: Shift+Escape ended the bridge\n");
  }

  /** Machinegun ammo of the session's latest snapshot, or -1. */
  private static int machinegunAmmo(dev.bluevista.craftq3.engine.session.EngineSession session) {
    if (session == null) return -1;
    return session
        .pipeline()
        .flatMap(p -> p.latestSnapshot())
        .map(
            snap -> {
              byte[] ps = snap.snapshot().player();
              int at = dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout.AMMO + 4 * 2;
              return (ps[at] & 255)
                  | (ps[at + 1] & 255) << 8
                  | (ps[at + 2] & 255) << 16
                  | ps[at + 3] << 24;
            })
        .orElse(-1);
  }

  /**
   * {@code quake q3dm17} in the bridge console starts a local match carrying the bridge loadout
   * (the machinegun ammo spent on the zombie); {@code minecraft} in the match's console returns to
   * the bridge with it.
   */
  private void transfers(ClientGameTestContext context, String tag) {
    int bridgeAmmo =
        context.computeOnClient(c -> machinegunAmmo(BridgeScreen.current(c).session()));
    context.runOnClient(c -> BridgeScreen.current(c).session().command("quake q3dm17\n"));
    waitFor(
        context,
        "the local match starts",
        c -> {
          var screen = dev.bluevista.craftq3.mc.fabric.client.screen.Q3Screen.current(c);
          return screen != null
              && screen.session() != null
              && screen.session().state() == SessionState.LOCAL
              && screen.presenter().framesDrawn() > 60
              && machinegunAmmo(screen.session()) >= 0;
        },
        90 * SECOND_TICKS);
    context.waitTicks(20);
    int matchAmmo =
        context.computeOnClient(
            c ->
                machinegunAmmo(
                    dev.bluevista.craftq3.mc.fabric.client.screen.Q3Screen.current(c).session()));
    screenshot(context, "craftq3-transfer-" + tag + "-match");
    report.append(
        String.format(
            Locale.ROOT,
            "transfer: quake q3dm17 carried machinegun ammo %d -> %d%n",
            bridgeAmmo,
            matchAmmo));
    check(
        bridgeAmmo > 0 && bridgeAmmo < 100,
        "the bridge machinegun ammo was not spent: " + bridgeAmmo);
    check(matchAmmo == bridgeAmmo, "the match did not receive the bridge loadout");
    context.runOnClient(
        c ->
            dev.bluevista.craftq3.mc.fabric.client.screen.Q3Screen.current(c)
                .session()
                .command("minecraft\n"));
    waitFor(
        context,
        "the bridge reopens",
        c -> {
          BridgeScreen screen = BridgeScreen.current(c);
          return screen != null
              && screen.session() != null
              && screen.session().state() == SessionState.REMOTE
              && screen.presenter() != null
              && screen.presenter().framesDrawn() > 60
              && machinegunAmmo(screen.session()) >= 0;
        },
        90 * SECOND_TICKS);
    context.waitTicks(20);
    int backAmmo = context.computeOnClient(c -> machinegunAmmo(BridgeScreen.current(c).session()));
    screenshot(context, "craftq3-transfer-" + tag + "-back");
    report.append(
        String.format(
            Locale.ROOT,
            "transfer: minecraft carried machinegun ammo %d -> %d%n",
            matchAmmo,
            backAmmo));
    check(backAmmo == matchAmmo, "the bridge did not receive the match loadout");
  }

  private void building(ClientGameTestContext context, TestSingleplayerContext world, String tag) {
    TestInput input = context.getInput();
    input.pressKey(options -> options.keyChat);
    context.waitForScreen(ChatScreen.class);
    input.typeChars("/q3 build q3dm17");
    input.pressKey(GLFW.GLFW_KEY_ENTER);
    long start = System.nanoTime();
    context.waitFor(
        c ->
            c.level != null
                && BuildingDimension.is(c.level)
                && CraftQ3Level.of(c.level).craftq3$building().active()
                && CraftQ3ClientMod.current().building().renderedFrames() > 30,
        90 * SECOND_TICKS);
    report.append(
        String.format(
            Locale.ROOT,
            "building: /q3 build q3dm17 to the BSP on screen: %d ms%n",
            (System.nanoTime() - start) / 1_000_000));
    world.getConnection().waitForChunksRender();
    // A stone block on a BSP floor cell near the player.
    BlockPos placed =
        world
            .getServer()
            .computeOnServer(
                server -> {
                  ServerLevel level = server.getLevel(BuildingDimension.KEY);
                  ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                  BuildingQueries q = CraftQ3Level.of(level).craftq3$building().at(player.getX());
                  BlockPos feet = player.blockPosition();
                  for (int r = 1; r < 24; r++)
                    for (int dx = -r; dx <= r; dx++)
                      for (int dz = -r; dz <= r; dz++)
                        for (int dy = -10; dy <= 10; dy++) {
                          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                          BlockPos p = feet.offset(dx, dy, dz);
                          if (q.cellClear(p.getX(), p.getY(), p.getZ())
                              && q.supportsFace(
                                  p.getX(),
                                  p.getY() - 1,
                                  p.getZ(),
                                  BlockFace.UP,
                                  SupportShape.CENTER)) {
                            level.setBlockAndUpdate(p, Blocks.GOLD_BLOCK.defaultBlockState());
                            level.setBlockAndUpdate(p.above(), Blocks.TORCH.defaultBlockState());
                            return p;
                          }
                        }
                  return null;
                });
    check(placed != null, "no BSP floor cell near the building spawn");
    if (placed != null) {
      BlockPos look = placed;
      context.runOnClient(
          c -> {
            var player = c.player;
            double dx = look.getX() + 0.5 - player.getX();
            double dy = look.getY() + 0.5 - player.getEyeY();
            double dz = look.getZ() + 0.5 - player.getZ();
            player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
          });
      report
          .append("building: gold block and torch on the BSP floor at ")
          .append(placed)
          .append('\n');
    }
    clickPlace(context, world, input);
    context.waitTicks(20);
    BufferedImage view = screenshot(context, "craftq3-build-" + tag);
    report.append("building screenshot: craftq3-build-").append(tag).append(".png\n");
    check(deviation(view) > 10, "the building view looks flat");
    var statuses = CraftQ3ClientMod.current().building().statuses();
    check(
        statuses.stream().anyMatch(s -> s instanceof BuildingRegions.Enabled),
        "the client has no enabled region: " + statuses);
    long cpu = context.computeOnClient(c -> CraftQ3ClientMod.current().building().lastCpuNanos());
    report.append(
        String.format(
            Locale.ROOT,
            "building client: BSP pass CPU %.2f ms, fps %d%n",
            cpu / 1e6,
            context.computeOnClient(Minecraft::getFps)));
    input.pressKey(options -> options.keyChat);
    context.waitForScreen(ChatScreen.class);
    input.typeChars("/q3 leave");
    input.pressKey(GLFW.GLFW_KEY_ENTER);
    context.waitFor(c -> c.level != null && !BuildingDimension.is(c.level), 20 * SECOND_TICKS);
    report.append("building: /q3 leave returned to the overworld\n");
  }

  /**
   * Standing on a BSP floor cell with oak planks in hand, the player looks at the BSP floor of the
   * neighbouring cell and right-clicks: the crosshair picks the BSP surface and the planks are
   * placed in that cell (client pick, server placement and support rules together).
   */
  private void clickPlace(
      ClientGameTestContext context, TestSingleplayerContext world, TestInput input) {
    BlockPos[] pair =
        world
            .getServer()
            .computeOnServer(
                server -> {
                  ServerLevel level = server.getLevel(BuildingDimension.KEY);
                  ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                  BuildingQueries q = CraftQ3Level.of(level).craftq3$building().at(player.getX());
                  BlockPos feet = player.blockPosition();
                  for (int r = 0; r < 24; r++)
                    for (int dx = -r; dx <= r; dx++)
                      for (int dz = -r; dz <= r; dz++)
                        for (int dy = -10; dy <= 10; dy++) {
                          if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                          BlockPos a = feet.offset(dx, dy, dz);
                          BlockPos b = a.offset(2, 0, 0);
                          if (standable(q, a)
                              && standable(q, b)
                              && level.getBlockState(b).isAir()
                              && q.cellClear(a.getX() + 1, a.getY(), a.getZ())
                              && q.cellClear(a.getX() + 1, a.getY() + 1, a.getZ())) {
                            player.teleportTo(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
                            player
                                .getInventory()
                                .setItem(
                                    player.getInventory().getSelectedSlot(),
                                    new net.minecraft.world.item.ItemStack(
                                        net.minecraft.world.item.Items.OAK_PLANKS, 16));
                            return new BlockPos[] {a, b};
                          }
                        }
                  return null;
                });
    check(pair != null, "no pair of BSP floor cells to click on");
    if (pair == null) return;
    context.waitTicks(10);
    BlockPos target = pair[1];
    context.runOnClient(
        c -> {
          var player = c.player;
          double dx = target.getX() + 0.5 - player.getX();
          double dy = target.getY() + 0.02 - player.getEyeY();
          double dz = target.getZ() + 0.5 - player.getZ();
          player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
          player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        });
    context.waitTicks(2);
    String picked =
        context.computeOnClient(
            c ->
                c.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                    ? hit.getType() + " " + hit.getBlockPos()
                    : String.valueOf(c.hitResult == null ? null : c.hitResult.getType()));
    input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    context.waitTicks(10);
    boolean planks =
        world
            .getServer()
            .computeOnServer(
                server ->
                    server
                        .getLevel(BuildingDimension.KEY)
                        .getBlockState(target)
                        .is(Blocks.OAK_PLANKS));
    report.append(
        "building: right-click on the BSP floor picked "
            + picked
            + "; planks placed at "
            + target
            + ": "
            + planks
            + "\n");
    check(planks, "right-clicking a BSP floor did not place the block (picked " + picked + ")");
  }

  private static boolean standable(BuildingQueries q, BlockPos p) {
    return q.cellClear(p.getX(), p.getY(), p.getZ())
        && q.cellClear(p.getX(), p.getY() + 1, p.getZ())
        && q.supportsFace(p.getX(), p.getY() - 1, p.getZ(), BlockFace.UP, SupportShape.FULL);
  }

  private static boolean eulaAccepted() {
    Path eula = FabricLoader.getInstance().getGameDir().resolve("eula.txt");
    try {
      return Files.isRegularFile(eula)
          && Files.readAllLines(eula).stream().anyMatch(l -> l.strip().equals("eula=true"));
    } catch (IOException e) {
      return false;
    }
  }

  // ------------------------------------------------------------------ helpers

  private static float zombieHealth(TestSingleplayerContext world, int id) {
    return world
        .getServer()
        .computeOnServer(
            server -> {
              for (ServerLevel level : server.getAllLevels()) {
                var e = level.getEntity(id);
                if (e instanceof Zombie z) return z.isAlive() ? z.getHealth() : 0f;
              }
              return 0f;
            });
  }

  private static void discard(MinecraftServer server, int id) {
    for (ServerLevel level : server.getAllLevels()) {
      var e = level.getEntity(id);
      if (e != null) e.discard();
    }
  }

  private void waitFor(
      ClientGameTestContext context, String what, Predicate<Minecraft> condition, int ticks) {
    for (int waited = 0; waited < ticks; waited++) {
      if (context.computeOnClient(c -> condition.test(c))) return;
      context.waitTick();
    }
    throw new AssertionError("timed out waiting until " + what);
  }

  private void check(boolean condition, String failure) {
    if (!condition) {
      failures.add(failure);
      LOGGER.error("Hybrid check failed: {}", failure);
    }
  }

  private void finish(String tag) {
    Path file =
        FabricLoader.getInstance().getGameDir().resolve("craftq3-hybrid-report-" + tag + ".txt");
    if (!failures.isEmpty()) report.append("FAILURES:\n");
    for (String failure : failures) report.append("  ").append(failure).append('\n');
    try {
      Files.writeString(file, report);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    LOGGER.info("Hybrid report ({}):\n{}", file, report);
    if (!failures.isEmpty()) throw new AssertionError("Hybrid failures: " + failures);
  }

  private static long percentile(long[] values, int p) {
    if (values.length == 0) return 0;
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[Math.min(sorted.length - 1, sorted.length * p / 100)];
  }

  private static BufferedImage screenshot(ClientGameTestContext context, String name) {
    Path path = context.takeScreenshot(name);
    try {
      BufferedImage image = ImageIO.read(path.toFile());
      if (image == null) throw new AssertionError("Unreadable screenshot " + path);
      return image;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static double deviation(BufferedImage image) {
    double sum = 0, squares = 0;
    long n = 0;
    for (int y = 0; y < image.getHeight(); y += 2)
      for (int x = 0; x < image.getWidth(); x += 2) {
        int p = image.getRGB(x, y);
        double l = 0.299 * (p >> 16 & 255) + 0.587 * (p >> 8 & 255) + 0.114 * (p & 255);
        sum += l;
        squares += l * l;
        n++;
      }
    double mean = sum / n;
    return Math.sqrt(Math.max(0, squares / n - mean * mean));
  }
}

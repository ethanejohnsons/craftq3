package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.CgameFrame;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Actual placement commands, separate-process persistence and original touch/respawn gameplay. */
public final class BridgePickupSmoke {
  private static Vec3 start;
  private static boolean visible, captured;
  private static int weaponAmmo, firstAmmo, firedAmmo;

  private BridgePickupSmoke() {}

  private static String mode() {
    return System.getProperty("craftq3.bridgePickupSmoke", "");
  }

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment() && !mode().isEmpty();
  }

  private static Path expected() {
    return FabricLoader.getInstance().getGameDir().resolve("craftq3-pickup-expected.txt");
  }

  private static CompletableFuture<Void> command(
      Minecraft client, String action, String item, int id) {
    var done = new CompletableFuture<Void>();
    client.execute(
        () ->
            PickupCommands.execute(
                client,
                "baseq3",
                action,
                item,
                id,
                message -> {
                  if (message.startsWith(
                      action.equals("add") ? "Saved Quake pickup" : "Removed Quake pickup"))
                    done.complete(null);
                  else done.completeExceptionally(new IllegalStateException(message));
                }));
    return done;
  }

  private static CompletableFuture<Void> teleport(Minecraft client, Vec3 point) {
    var server = client.getSingleplayerServer();
    var id = client.player.getUUID();
    return server.submit(
        () -> {
          server
              .getPlayerList()
              .getPlayer(id)
              .connection
              .teleport(point.x(), point.y(), point.z(), -90, 0);
          return null;
        });
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    visible = captured = false;
    weaponAmmo = firstAmmo = firedAmmo = 0;
    if (!Set.of("prepare", "verify").contains(mode()))
      throw new IllegalArgumentException("Unknown pickup QA mode");
    var server = client.getSingleplayerServer();
    var id = client.player.getUUID();
    CompletableFuture<Void> setup =
        server.submit(
            () -> {
              var world = server.getWorldPath(LevelResource.ROOT);
              if (!world
                  .toAbsolutePath()
                  .normalize()
                  .getFileName()
                  .toString()
                  .equals("CraftQ3 Bridge QA"))
                throw new IllegalStateException("Pickup QA requires its private world");
              var player = server.getPlayerList().getPlayer(id);
              var level = player.level();
              String dimension = level.dimension().identifier().toString();
              try {
                var entries = PickupPlacements.read(world, "baseq3", dimension);
                if (mode().equals("verify")) {
                  if (!entries.toString().equals(Files.readString(expected())))
                    throw new IllegalStateException("Saved placements differ across launches");
                  start = entries.getFirst().position().add(new Vec3(-4, -1, 0));
                } else {
                  for (var entry : entries)
                    PickupPlacements.remove(world, "baseq3", dimension, entry.id());
                  var block = player.blockPosition();
                  start = new Vec3(block.getX() + .5, block.getY(), block.getZ() + .5);
                }
              } catch (IOException error) {
                throw new UncheckedIOException(error);
              }
              var origin = BlockPos.containing(start.x(), start.y(), start.z());
              for (int x = -3; x <= 24; x++)
                for (int z = -4; z <= 4; z++)
                  for (int y = -1; y <= 5; y++)
                    level.setBlockAndUpdate(
                        origin.offset(x, y, z),
                        (y == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
              for (var mob :
                  level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(40)))
                mob.discard();
              return null;
            });
    if (mode().equals("prepare")) {
      String[] items = {
        "weapon_rocketlauncher", "ammo_rockets", "item_armor_combat", "item_health_large"
      };
      for (int i = 0; i < items.length; i++) {
        final int index = i;
        final String item = items[i];
        setup =
            setup
                .thenCompose(unused -> teleport(client, start.add(new Vec3((index + 1) * 4, 0, 0))))
                .thenCompose(unused -> command(client, "add", item, 0));
      }
      setup =
          setup
              .thenCompose(unused -> command(client, "add", "weapon_railgun", 0))
              .thenCompose(unused -> command(client, "remove", "", 5))
              .thenCompose(
                  unused ->
                      server.submit(
                          () -> {
                            try {
                              var entries =
                                  PickupPlacements.read(
                                      server.getWorldPath(LevelResource.ROOT),
                                      "baseq3",
                                      server
                                          .getPlayerList()
                                          .getPlayer(id)
                                          .level()
                                          .dimension()
                                          .identifier()
                                          .toString());
                              if (entries.size() != 4)
                                throw new IllegalStateException(
                                    "Placement/remove commands did not persist four items");
                              Files.writeString(expected(), entries.toString());
                            } catch (IOException error) {
                              throw new UncheckedIOException(error);
                            }
                            return null;
                          }));
    }
    return setup.thenCompose(unused -> teleport(client, start));
  }

  public static void step(BridgeGame game, int frame) {
    var state = game.loadout();
    if (frame == 0 && (state.weapons() != 6 || state.armor() != 0))
      throw new IllegalStateException("Pickup QA requires a fresh original loadout");
    if (weaponAmmo == 0 && (state.weapons() & 32) != 0) weaponAmmo = state.ammo().get(5);
    if (frame == 40) game.input().key('w', true, game.time());
    if (frame == 170) game.input().key('w', false, game.time());
    if (frame == 180) {
      firstAmmo = state.ammo().get(5);
      if (!visible || weaponAmmo <= 0 || firstAmmo <= weaponAmmo || state.armor() <= 0)
        throw new IllegalStateException(
            "Native terrain pickups did not grant original inventory: " + state);
      game.command("weapon 5");
    }
    if (frame == 230) game.input().key(178, true, game.time());
    if (frame == 240) game.input().key(178, false, game.time());
    if (frame == 260) {
      firedAmmo = state.ammo().get(5);
      if (firedAmmo != firstAmmo - 1)
        throw new IllegalStateException("Collected native-world rocket did not fire");
    }
    if (frame == 450) game.input().key('s', true, game.time());
    if (frame >= 450 && game.feet().x() <= start.x() + 4) game.input().key('s', false, game.time());
  }

  public static void observe(Minecraft client, BridgeGame game, CgameFrame frame, int number) {
    if (!enabled() || number != 30 || captured) return;
    visible =
        frame.commands().stream()
            .filter(CgameFrame.View.class::isInstance)
            .map(CgameFrame.View.class::cast)
            .flatMap(v -> v.entities().stream())
            .anyMatch(e -> e.model() != null && e.model().name().contains("rocketl"));
    if ((game.loadout().weapons() & 32) != 0)
      throw new IllegalStateException("World pickup check already owned the weapon");
    captured = true;
    net.minecraft.client.Screenshot.grab(
        client.gameDirectory,
        "craftq3-pickups-visible.png",
        client.gameRenderer.mainRenderTarget(),
        1,
        message -> {});
  }

  public static String result(BridgeGame game) {
    var state = game.loadout();
    if (!visible || firedAmmo <= 0 || state.ammo().get(5) <= firedAmmo)
      throw new IllegalStateException(
          "Original pickup did not respawn on Minecraft terrain: " + state);
    return "pickups="
        + mode()
        + " visible=true commands-save-reload=true weapon=true ammo=true armor=true respawn=true"
        + " rocket=true";
  }
}

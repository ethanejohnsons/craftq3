package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Original movement across the initial client chunk window, with native server tracking. */
public final class BridgeTravelSmoke {
  private static Vec3 start, unfocusedFeet;
  private static int unfocusedTime, unfocusedPowerup;
  private static boolean focusPassed;
  private static boolean initiallyUnloaded, arrived, fired;
  private static int arrivalFrame;
  private static CompletableFuture<String> evidence;

  private BridgeTravelSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeTravelSmoke");
  }

  /** Feed the real focus-handling branch deterministic foreground/background transitions. */
  public static boolean unfocused(int frame) {
    return enabled() && frame >= 250 && frame < 350;
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = client.player.getUUID();
    initiallyUnloaded = arrived = fired = focusPassed = false;
    evidence = null;
    return host.submit(
        () -> {
          if (!host.getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Travel QA requires its private world");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
          var level = player.level();
          var origin = player.blockPosition().above(16);
          start = new Vec3(origin.getX() + .5, origin.getY(), origin.getZ() + .5);
          // Preparing distant server terrain does not send chunks outside the native player view.
          for (int x = -3; x <= 405; x++)
            for (int z = -2; z <= 2; z++)
              for (int y = -1; y <= 4; y++)
                level.setBlockAndUpdate(
                    origin.offset(x, y, z),
                    (y == -1 || x == 400 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
          player.connection.teleport(start.x(), start.y(), start.z(), -90, 0);
          return null;
        });
  }

  public static void step(Minecraft client, BridgeGame game, int frame) {
    var destination = BlockPos.containing(start.x() + 399, start.y() - 1, start.z());
    if (frame == 0) {
      initiallyUnloaded =
          !client.level.getChunkSource().hasChunk(destination.getX() >> 4, destination.getZ() >> 4);
      if (!initiallyUnloaded) throw new IllegalStateException("Travel destination already loaded");
      game.command("give all");
    }
    if (frame == 30) {
      game.command("weapon 5; give Quad Damage");
      game.input().key('w', true, game.time());
    }
    if (frame == 270) {
      unfocusedFeet = game.feet();
      unfocusedTime = game.time();
      unfocusedPowerup = game.loadout().powerupMillis().getFirst();
    }
    if (frame == 350) {
      int elapsed = game.time() - unfocusedTime;
      var delta = game.feet().add(unfocusedFeet.scale(-1));
      double movement =
          Math.sqrt(delta.x() * delta.x() + delta.y() * delta.y() + delta.z() * delta.z());
      if (elapsed != 1280
          || unfocusedPowerup - game.loadout().powerupMillis().getFirst() != elapsed
          || movement > .1)
        throw new IllegalStateException(
            "Unfocused bridge froze time or retained movement: elapsed="
                + elapsed
                + " movement="
                + movement);
      focusPassed = true;
      game.input().key('w', true, game.time());
    }
    double distance = game.feet().x() - start.x();
    if (!arrived && distance >= 398.9) {
      arrived = true;
      arrivalFrame = frame;
      game.input().angles(-60, 0, 0);
      System.out.println(
          "CraftQ3 travel reached distant wall: " + distance + " loadout=" + game.loadout());
      game.input().key('w', false, game.time());
      if (!client.level.getBlockState(destination).is(Blocks.STONE)
          || Math.abs(game.feet().y() - start.y()) > .1)
        throw new IllegalStateException("Distant terrain failed original floor collision");
    }
    if (arrived && frame == arrivalFrame + 40) game.input().key(178, true, game.time());
    if (arrived && frame == arrivalFrame + 45) game.input().key(178, false, game.time());
    if (arrived && frame == arrivalFrame + 100) {
      fired = game.loadout().ammo().get(5) == 998;
      System.out.println("CraftQ3 travel fired: " + game.loadout());
    }
    // Check standing travel position before rocket splash can move the original player.
    if (arrived && frame == arrivalFrame + 20) {
      var host = client.getSingleplayerServer();
      var id = client.player.getUUID();
      var feet = game.feet();
      evidence =
          host.submit(
              () -> {
                var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
                double error =
                    player
                        .position()
                        .distanceTo(
                            new net.minecraft.world.phys.Vec3(feet.x(), feet.y(), feet.z()));
                if (player.getX() - start.x() < 397
                    || error > 2
                    || !player
                        .level()
                        .getChunkSource()
                        .chunkMap
                        .isChunkTracked(player, destination.getX() >> 4, destination.getZ() >> 4))
                  throw new IllegalStateException(
                      "Native travel tracking failed: distance="
                          + (player.getX() - start.x())
                          + " error="
                          + error);
                return "native-distance=" + (player.getX() - start.x()) + " native-error=" + error;
              });
    }
  }

  public static String result(BridgeGame game) {
    if (!initiallyUnloaded
        || !arrived
        || !fired
        || !focusPassed
        || evidence == null
        || !evidence.isDone())
      throw new IllegalStateException(
          "Travel incomplete: distance="
              + (game.feet().x() - start.x())
              + " arrived="
              + arrived
              + " fired="
              + fired);
    return "travel=true new-client-chunks=true original-floor-wall=true rocket=true"
        + " unfocused-clock=true input-released=true "
        + evidence.join();
  }
}

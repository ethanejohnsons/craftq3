package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import dev.bluevista.craftq3.server.ExternalPickup;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

/** Native world placement editing; the QVM sees only original item entities at bridge admission. */
public final class PickupCommands {
  private PickupCommands() {}

  public static int execute(
      Minecraft client,
      String game,
      String action,
      String classname,
      int id,
      Consumer<String> output) {
    var server = client.getSingleplayerServer();
    if (server == null || client.player == null || client.level == null) {
      output.accept("Open a local Minecraft world to edit Quake pickups.");
      return 0;
    }
    var playerId = client.player.getUUID();
    var dimension = client.level.dimension();
    server
        .submit(
            () -> {
              var player = Objects.requireNonNull(server.getPlayerList().getPlayer(playerId));
              if (!player.level().dimension().equals(dimension))
                throw new IllegalStateException("World changed; run the pickup command again.");
              var world = server.getWorldPath(LevelResource.ROOT);
              String key = dimension.identifier().toString();
              try {
                if (action.equals("add")) {
                  var p = player.position();
                  var entry =
                      PickupPlacements.add(
                          world, game, key, classname, new Vec3(p.x, p.y + 1, p.z));
                  return List.of(
                      "Saved Quake pickup #"
                          + entry.id()
                          + " "
                          + classname
                          + ". Re-enter the bridge to load it.");
                }
                if (action.equals("remove"))
                  return List.of(
                      PickupPlacements.remove(world, game, key, id)
                          ? "Removed Quake pickup #" + id + ". Re-enter the bridge to apply it."
                          : "No Quake pickup #" + id + " in this dimension.");
                var entries = PickupPlacements.read(world, game, key);
                if (entries.isEmpty()) return List.of("No placed Quake pickups in this dimension.");
                return entries.stream()
                    .map(
                        e ->
                            String.format(
                                Locale.ROOT,
                                "#%d %s at %.2f %.2f %.2f",
                                e.id(),
                                e.classname(),
                                e.position().x(),
                                e.position().y(),
                                e.position().z()))
                    .toList();
              } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
            })
        .whenComplete(
            (messages, error) ->
                client.execute(
                    () -> {
                      if (error == null) messages.forEach(output);
                      else {
                        Throwable cause = error;
                        while (cause.getCause() != null) cause = cause.getCause();
                        dev.bluevista.craftq3.fabric.CraftQ3Client.LOGGER.error(
                            "Quake pickup placement failed", error);
                        output.accept("Pickups: " + cause.getMessage());
                      }
                    }));
    return 1;
  }

  public static List<ExternalPickup> load(
      Minecraft client, String game, CoordinateTransform transform) throws IOException {
    // Other development fixtures share this private world but require their own fixed inventory.
    if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
        && System.getProperty("craftq3.bridgeSmokeWorld") != null
        && !BridgePickupSmoke.enabled()) return List.of();
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    var level = Objects.requireNonNull(client.level);
    var center = Objects.requireNonNull(client.player).position();
    var result = new ArrayList<ExternalPickup>();
    for (var entry :
        PickupPlacements.read(
            server.getWorldPath(LevelResource.ROOT),
            game,
            level.dimension().identifier().toString())) {
      var p = entry.position();
      double x = p.x() - center.x, y = p.y() - center.y, z = p.z() - center.z;
      var block = BlockPos.containing(p.x(), p.y(), p.z());
      if (x * x + y * y + z * z > 256 * 256
          || !level.getChunkSource().hasChunk(block.getX() >> 4, block.getZ() >> 4)) continue;
      result.add(new ExternalPickup(entry.classname(), transform.toQuake(p)));
    }
    return List.copyOf(result);
  }
}

package dev.bluevista.craftq3.fabric.building;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** Two-process, forced-chunk persistence audit in the private QA world. */
public final class BuildingWorldReloadSmoke {
  private static int ticks;
  private static boolean pending, finished;

  private BuildingWorldReloadSmoke() {}

  private static String mode() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        ? System.getProperty("craftq3.worldReloadSmoke", "")
        : "";
  }

  public static boolean preparing() {
    return mode().equals("prepare");
  }

  public static boolean verifying() {
    return mode().equals("verify");
  }

  private static Path directory(ServerLevel level) {
    var path = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    if (!path.getFileName().toString().equals("CraftQ3 Bridge QA")
        || !"CraftQ3 Bridge QA".equals(System.getProperty("craftq3.bridgeSmokeWorld")))
      throw new IllegalStateException("World reload smoke requires the private QA world");
    return path.resolve("craftq3");
  }

  public static void checkpoint(Minecraft client, BlockPos torch, Direction face, BlockPos floor) {
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    server
        .submit(
            () -> {
              try {
                var level = Objects.requireNonNull(server.getLevel(BuildingSession.DIMENSION));
                var path = directory(level);
                if (BuildingSession.active() != null
                    || !level.getBlockState(torch).is(Blocks.WALL_TORCH))
                  throw new IllegalStateException(
                      "Reload checkpoint needs a retained torch after leaving");
                var probe =
                    Objects.requireNonNull(
                        EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND));
                probe.setPos(floor.getX() + .5, floor.getY() + .01, floor.getZ() + .5);
                if (!level.addFreshEntity(probe))
                  throw new IllegalStateException("Could not add persistent collision probe");
                for (var pos : List.of(torch, floor)) {
                  var chunk = ChunkPos.containing(pos);
                  level.setChunkForced(chunk.x(), chunk.z(), true);
                }
                var expected = new Properties();
                expected.setProperty("torch", position(torch));
                expected.setProperty("floor", position(floor));
                expected.setProperty("face", face.getName());
                expected.setProperty("entity", probe.getUUID().toString());
                int regions = BuildSpaceIndex.entries(path.resolve("build-maps.properties")).size();
                expected.setProperty("regions", Integer.toString(regions));
                try (var writer =
                    Files.newBufferedWriter(path.resolve("world-reload-expected.properties"))) {
                  expected.store(writer, "Private QA reload fixture");
                }
                if (!server.saveEverything(false, true, true))
                  throw new IllegalStateException("World reload checkpoint save failed");
                return "PASS world checkpoint regions="
                    + regions
                    + " torch=true entity=true forced-chunks=true\n";
              } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            })
        .whenComplete((result, error) -> client.execute(() -> finish(client, result, error)));
  }

  public static void tick(Minecraft client) {
    if (!verifying() || finished || client.player == null || client.level == null) return;
    client.options.pauseOnLostFocus = false;
    if (client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)
      client.gui.setScreen(null);
    if (++ticks > 400)
      throw new IllegalStateException("Saved background entity chunks did not become ready");
    if (pending || ticks % 10 != 0) return;
    pending = true;
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    server
        .submit(
            () -> {
              try {
                return verify(Objects.requireNonNull(server.getLevel(BuildingSession.DIMENSION)));
              } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
            })
        .whenComplete(
            (result, error) ->
                client.execute(
                    () -> {
                      pending = false;
                      if (result != null || error != null) finish(client, result, error);
                    }));
  }

  private static String verify(ServerLevel level) throws IOException {
    if (BuildingSession.active() != null)
      throw new IllegalStateException("Reload unexpectedly opened a BSP view");
    var path = directory(level);
    var expected = new Properties();
    try (var reader = Files.newBufferedReader(path.resolve("world-reload-expected.properties"))) {
      expected.load(reader);
    }
    var torch = position(expected.getProperty("torch"));
    var floor = position(expected.getProperty("floor"));
    for (var pos : List.of(torch, floor)) {
      var chunk = ChunkPos.containing(pos);
      if (!level.getChunkSource().getForceLoadedChunks().contains(chunk.pack()))
        throw new IllegalStateException("Forced chunk did not persist");
      if (!level.areEntitiesActuallyLoadedAndTicking(chunk)) return null;
    }
    var index = BuildSpaceIndex.entries(path.resolve("build-maps.properties"));
    if (index.size() != Integer.parseInt(expected.getProperty("regions")))
      throw new IllegalStateException("Saved regions changed");
    for (int slot : index.values())
      if (BuildingWorlds.at(level, slot * 4096.0) == null)
        throw new IllegalStateException("Startup did not restore region " + slot);
    if (!level.getBlockState(torch).is(Blocks.WALL_TORCH)
        || !level.getBlockState(torch).canSurvive(level, torch))
      throw new IllegalStateException("Saved torch lost support before any build session");
    var probe = level.getEntityInAnyDimension(UUID.fromString(expected.getProperty("entity")));
    if (probe == null || probe.level() != level || Math.abs(probe.getY() - floor.getY()) > .05)
      throw new IllegalStateException(
          "Saved entity missing or fell through background BSP: " + probe);
    probe.setPos(floor.getX() + .5, floor.getY() + 1, floor.getZ() + .5);
    probe.move(MoverType.SELF, new Vec3(0, -2, 0));
    if (Math.abs(probe.getY() - floor.getY()) > .01)
      throw new IllegalStateException("Reloaded BSP collision failed");
    var face = Objects.requireNonNull(Direction.byName(expected.getProperty("face")));
    var support = torch.relative(face.getOpposite());
    if (!level.getBlockState(support).isAir())
      throw new IllegalStateException("Torch has native block support");
    level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
    level.setBlockAndUpdate(support, Blocks.AIR.defaultBlockState());
    if (!level.getBlockState(torch).is(Blocks.WALL_TORCH))
      throw new IllegalStateException("Reloaded BSP support update failed");
    probe.discard();
    level.setBlockAndUpdate(torch, Blocks.AIR.defaultBlockState());
    for (var pos : List.of(torch, floor)) {
      var chunk = ChunkPos.containing(pos);
      level.setChunkForced(chunk.x(), chunk.z(), false);
    }
    return "PASS world reload regions="
        + index.size()
        + " torch=true entity=true collision=true no-build-session=true\n";
  }

  private static String position(BlockPos p) {
    return p.getX() + "," + p.getY() + "," + p.getZ();
  }

  private static BlockPos position(String value) {
    var p = value.split(",");
    return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
  }

  private static void finish(Minecraft client, String result, Throwable error) {
    finished = true;
    if (error != null)
      throw new IllegalStateException("Background world reload smoke failed", error);
    try {
      Files.writeString(client.gameDirectory.toPath().resolve("craftq3-bridge.result"), result);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    System.out.print(result);
    client.stop();
  }
}

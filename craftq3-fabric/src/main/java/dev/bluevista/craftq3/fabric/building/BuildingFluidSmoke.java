package dev.bluevista.craftq3.fabric.building;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** Native bucket use and scheduled water/lava ticks on an original BSP-only foundation. */
public final class BuildingFluidSmoke {
  private static int ticks;
  private static BlockPos center;
  private static CompletableFuture<?> pending;
  private static final Map<BlockPos, BlockState> restored = new LinkedHashMap<>();
  private static boolean finished;

  private BuildingFluidSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.fluidSmoke");
  }

  public static void tick(Minecraft client, BuildingSession session) {
    if (finished) return;
    if (pending != null) {
      if (!pending.isDone()) return;
      try {
        pending.join();
      } catch (RuntimeException failure) {
        client.stop();
        throw failure;
      }
      pending = null;
    }
    int step = ++ticks;
    var host = Objects.requireNonNull(client.getSingleplayerServer());
    var id = client.player.getUUID();
    if (step == 30)
      pending =
          host.submit(
              () -> {
                if (!host.getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath()
                    .normalize()
                    .getFileName()
                    .toString()
                    .equals("CraftQ3 Bridge QA"))
                  throw new IllegalStateException("Fluid QA requires private save");
                setup(Objects.requireNonNull(host.getPlayerList().getPlayer(id)), session);
                return null;
              });
    if (step == 60 || step == 120 || step == 240 || step == 360)
      client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
    if (step == 100 || step == 160 || step == 220 || step == 340 || step == 390)
      pending =
          host.submit(
              () -> {
                var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
                var level = player.level();
                if (step == 100 || step == 340) {
                  var fluid = step == 100 ? Fluids.WATER : Fluids.LAVA;
                  if (!level.getFluidState(center).isSource()
                      || !level.getFluidState(center).getType().isSame(fluid)
                      || !level.getFluidState(center.east()).getType().isSame(fluid))
                    throw new IllegalStateException(
                        "Native bucket/spread failed at " + center + " fluid=" + fluid);
                  for (int x = -1; x <= 1; x++)
                    for (int z = -1; z <= 1; z++) {
                      var below = center.offset(x, -1, z);
                      if (!level.getBlockState(below).isAir())
                        throw new IllegalStateException(
                            "Fluid leaked through BSP floor at " + below);
                    }
                  if (((BucketItem) Items.WATER_BUCKET)
                      .emptyContents(player, level, center.below(), null))
                    throw new IllegalStateException("Bucket admitted fluid inside BSP solid");
                  equip(player, Items.BUCKET);
                }
                if (step == 160) {
                  if (!level.getFluidState(center).isEmpty())
                    throw new IllegalStateException("Native bucket pickup did not remove water");
                  level.setBlockAndUpdate(center.west(), Blocks.WATER.defaultBlockState());
                  level.setBlockAndUpdate(center.east(), Blocks.WATER.defaultBlockState());
                }
                if (step == 220) {
                  if (!level.getFluidState(center).isSource())
                    throw new IllegalStateException(
                        "Native source conversion ignored BSP foundation");
                  clearPool(player);
                  equip(player, Items.LAVA_BUCKET);
                }
                if (step == 390) {
                  if (!level.getFluidState(center).isEmpty())
                    throw new IllegalStateException("Native bucket pickup did not remove lava");
                  clearPool(player);
                  verifyNativeControls(player, session);
                  restored.forEach(level::setBlockAndUpdate);
                }
                return null;
              });
    if (step == 350)
      net.minecraft.client.Screenshot.grab(
          client.gameDirectory,
          "craftq3-building-fluid.png",
          client.gameRenderer.mainRenderTarget(),
          1,
          message -> {});
    if (step == 400) {
      finished = true;
      session
          .leave()
          .whenComplete(
              (unused, error) ->
                  client.execute(
                      () -> {
                        if (error != null)
                          throw new IllegalStateException("Fluid QA return failed", error);
                        try {
                          Files.writeString(
                              client.gameDirectory.toPath().resolve("craftq3-bridge.result"),
                              "PASS fluids native-bucket=true water-spread=true lava-spread=true"
                                  + " bsp-floor=true no-buried-source=true source-conversion=true"
                                  + " bucket-pickup=true waterlogging=true free-fall=true"
                                  + " returned=true\n");
                        } catch (java.io.IOException e) {
                          throw new java.io.UncheckedIOException(e);
                        }
                        client.stop();
                      }));
    }
  }

  private static void verifyNativeControls(ServerPlayer player, BuildingSession session) {
    var level = player.level();
    var wet = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
    level.setBlockAndUpdate(center, Blocks.OAK_SLAB.defaultBlockState());
    if (!((BucketItem) Items.WATER_BUCKET).emptyContents(player, level, center, null)
        || !level.getBlockState(center).getValue(wet))
      throw new IllegalStateException("Native slab waterlogging failed");
    equip(player, Items.BUCKET);
    Items.BUCKET.use(level, player, InteractionHand.MAIN_HAND);
    if (level.getBlockState(center).getValue(wet))
      throw new IllegalStateException("Native waterlogged bucket pickup failed");
    level.setBlockAndUpdate(center, Blocks.AIR.defaultBlockState());
    var control = new BlockPos(session.region() * 4096 + 1900, 100, 0);
    level.getChunkAt(control);
    var before = level.getBlockState(control);
    var below = level.getBlockState(control.below());
    if (!before.isAir() || !below.isAir() || !BuildingFluids.pass(level, control, control.below()))
      throw new IllegalStateException("Native free-fall control is obstructed");
    try {
      level.setBlockAndUpdate(control, Blocks.WATER.defaultBlockState());
      Fluids.WATER.tick(level, control, level.getBlockState(control), level.getFluidState(control));
      if (!level.getFluidState(control.below()).getType().isSame(Fluids.WATER))
        throw new IllegalStateException("Native water could not fall outside BSP geometry");
    } finally {
      level.setBlockAndUpdate(control, before);
      level.setBlockAndUpdate(control.below(), below);
    }
  }

  private static void equip(ServerPlayer player, Item item) {
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
    player.containerMenu.broadcastChanges();
  }

  private static void clearPool(ServerPlayer player) {
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++)
        player.level().setBlockAndUpdate(center.offset(x, 0, z), Blocks.AIR.defaultBlockState());
  }

  private static void setup(ServerPlayer player, BuildingSession session) {
    var level = player.level();
    var map = session.map();
    for (var face : map.faces()) {
      if (face.type() != 1 || face.vertexCount() == 0 || face.normal().z() < .99) continue;
      var sum = new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0);
      for (int i = 0; i < face.vertexCount(); i++)
        sum = sum.add(map.vertices().get(face.firstVertex() + i).position());
      var point = session.transform().toMinecraft(sum.scale(1.0 / face.vertexCount()));
      var candidate = BlockPos.containing(point.x(), Math.ceil(point.y() - .001), point.z());
      boolean fits = true;
      for (int x = -2; x <= 2; x++)
        for (int z = -2; z <= 2; z++) {
          var pos = candidate.offset(x, 0, z);
          level.getChunkAt(pos);
          if (!BuildingFluids.clear(level, pos)
              || !level.getBlockState(pos).isAir()
              || !level.getBlockState(pos.below()).isAir()
              || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP))
            fits = false;
        }
      if (!fits) continue;
      center = candidate;
      for (int x = -2; x <= 2; x++)
        for (int z = -2; z <= 2; z++) {
          var pos = center.offset(x, 0, z);
          restored.put(pos, level.getBlockState(pos));
          if (Math.abs(x) == 2 || Math.abs(z) == 2)
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
        }
      player.connection.teleport(center.getX() + .5, center.getY() + 2, center.getZ() + .5, 0, 90);
      player.getAbilities().flying = true;
      player.onUpdateAbilities();
      equip(player, Items.WATER_BUCKET);
      System.out.println("CraftQ3 original BSP fluid foundation: " + center);
      return;
    }
    throw new IllegalStateException("No clear original BSP fluid foundation");
  }
}

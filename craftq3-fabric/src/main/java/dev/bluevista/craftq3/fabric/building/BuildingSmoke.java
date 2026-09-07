package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.file.Files;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/** Native block placement/breaking and dimension-return smoke in the selected private QA world. */
public final class BuildingSmoke {
  private static int ticks;
  private static String movement;
  private static BuildingSession session;
  private static BlockHitResult target;
  private static boolean placed, broken, captured;
  private static boolean previousPause;
  private static volatile boolean persisted;

  private BuildingSmoke() {}

  public static boolean enabled() {
    return Boolean.getBoolean("craftq3.buildSmoke");
  }

  public static void tick(Minecraft client) {
    var active = BuildingSession.active();
    if (active == null || client.player == null || !active.matches(client.player)) return;
    if (ticks == 0) previousPause = client.options.pauseOnLostFocus;
    client.options.pauseOnLostFocus = false;
    if (client.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen)
      client.gui.setScreen(null);
    session = active;
    if (BuildingDecorationSmoke.enabled()) {
      BuildingDecorationSmoke.tick(client, active);
      return;
    }
    if (BuildingFluidSmoke.enabled()) {
      BuildingFluidSmoke.tick(client, active);
      return;
    }
    if (BuildingNarrowNavigationSmoke.enabled()) {
      BuildingNarrowNavigationSmoke.tick(client, active);
      return;
    }
    if (BuildingMobCombatSmoke.enabled()) {
      BuildingMobCombatSmoke.tick(client, active);
      return;
    }
    if (BuildingNavigationSmoke.enabled()) {
      BuildingNavigationSmoke.tick(client, active);
      return;
    }
    if (BuildingExplosionSmoke.enabled()) {
      BuildingExplosionSmoke.tick(client, active);
      return;
    }
    if (BuildingProjectileSmoke.enabled()) {
      BuildingProjectileSmoke.tick(client, active);
      return;
    }
    if (BuildingSupportSmoke.enabled()) {
      BuildingSupportSmoke.tick(client, active);
      return;
    }
    int step = ++ticks;
    if (BuildingRecoverySmoke.enabled()) {
      if (step == 30) BuildingRecoverySmoke.checkpoint(client, active);
      return;
    }
    if (step == 30) {
      movement = BuildingMovementSmoke.verify(client, active);
      System.out.println("CraftQ3 building movement " + movement);
    }
    if (step == 40) {
      var server = Objects.requireNonNull(client.getSingleplayerServer());
      var id = client.player.getUUID();
      server.execute(
          () -> {
            var player = Objects.requireNonNull(server.getPlayerList().getPlayer(id));
            var previous = System.getProperty("craftq3.buildVerifyCell", "");
            if (!previous.isBlank()) {
              var coordinates = previous.split(",");
              var position =
                  new BlockPos(
                      Integer.parseInt(coordinates[0]),
                      Integer.parseInt(coordinates[1]),
                      Integer.parseInt(coordinates[2]));
              if (!player.level().getBlockState(position).is(Blocks.STONE))
                throw new IllegalStateException(
                    "Saved Minecraft block did not persist across launches: " + position);
              persisted = true;
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 64));
            player.containerMenu.broadcastChanges();
          });
    }
    if (step == 50)
      net.minecraft.client.Screenshot.grab(
          client.gameDirectory,
          "craftq3-building-overview.png",
          client.gameRenderer.mainRenderTarget(),
          1,
          message -> {});
    if (step == 60) {
      var eye = client.player.getEyePosition();
      for (int i = 0; i < 24 && target == null; i++) {
        double angle = (i % 8) * Math.PI / 4;
        double reach = 4.5 + (i / 8) * 1.5;
        var hit =
            active
                .geometry()
                .pick(
                    new Vec3(eye.x, eye.y, eye.z),
                    new Vec3(
                        eye.x + Math.cos(angle) * reach,
                        eye.y - 4,
                        eye.z + Math.sin(angle) * reach));
        if (hit.isEmpty()) continue;
        var value = hit.get();
        var cell = value.cell();
        var pos = new BlockPos((int) cell.x(), (int) cell.y(), (int) cell.z());
        if (!client.level.getBlockState(pos).isAir()
            || new net.minecraft.world.phys.AABB(pos).intersects(client.player.getBoundingBox()))
          continue;
        var point =
            new net.minecraft.world.phys.Vec3(
                value.placementPoint().x(), value.placementPoint().y(), value.placementPoint().z());
        target =
            new BlockHitResult(
                point,
                Direction.getApproximateNearest(
                    value.normal().x(), value.normal().y(), value.normal().z()),
                pos,
                false);
        var delta = net.minecraft.world.phys.Vec3.atCenterOf(pos).subtract(eye);
        client.player.setYRot((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot(
            (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
        client.player.setOldPosAndRot();
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, target);
      }
      if (target == null)
        throw new IllegalStateException(
            "No reachable BSP build face at " + client.player.position());
    }
    if (step == 85) {
      placed = client.level.getBlockState(target.getBlockPos()).is(Blocks.STONE);
      if (!placed)
        throw new IllegalStateException(
            "Native Minecraft placement on BSP failed at " + target.getBlockPos());
      client.gameMode.startDestroyBlock(target.getBlockPos(), Direction.UP);
    }
    if (step == 105) {
      broken = client.level.getBlockState(target.getBlockPos()).isAir();
      if (!broken) throw new IllegalStateException("Native Minecraft block breaking failed");
      client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, target);
    }
    if (step == 140 && !captured) {
      captured = true;
      if (!client.level.getBlockState(target.getBlockPos()).is(Blocks.STONE))
        throw new IllegalStateException("Replacement block missing");
      var server = Objects.requireNonNull(client.getSingleplayerServer());
      var id = client.player.getUUID();
      server
          .submit(
              () -> {
                var player = Objects.requireNonNull(server.getPlayerList().getPlayer(id));
                if (!player.level().getBlockState(target.getBlockPos()).is(Blocks.STONE))
                  throw new IllegalStateException("Server did not store placed block");
                return null;
              })
          .whenComplete(
              (unused, failure) ->
                  client.execute(
                      () -> {
                        if (failure != null)
                          throw new IllegalStateException(
                              "Build server verification failed", failure);
                        capture(client);
                      }));
    }
  }

  private static void capture(Minecraft client) {
    net.minecraft.client.Screenshot.grab(
        client.gameDirectory,
        "craftq3-building.png",
        client.gameRenderer.mainRenderTarget(),
        1,
        message -> client.execute(() -> finish(client)));
  }

  private static void finish(Minecraft client) {
    session
        .leave()
        .whenComplete(
            (unused, failure) ->
                client.execute(
                    () -> {
                      if (failure != null)
                        throw new IllegalStateException("Build return failed", failure);
                      try {
                        Files.writeString(
                            client.gameDirectory.toPath().resolve("craftq3-bridge.result"),
                            "PASS building placed="
                                + placed
                                + " broken="
                                + broken
                                + " returned=true persisted="
                                + persisted
                                + " movement="
                                + movement
                                + " cell="
                                + target.getBlockPos()
                                + "\n");
                      } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                      }
                      client.options.pauseOnLostFocus = previousPause;
                      client.stop();
                    }));
  }
}

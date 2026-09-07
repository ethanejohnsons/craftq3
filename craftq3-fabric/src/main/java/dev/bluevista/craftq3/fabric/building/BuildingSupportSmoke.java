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
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/** Native torch placement and support-neighbor removal in the selected private QA world. */
final class BuildingSupportSmoke {
  record Target(BlockHitResult hit, Direction face) {}

  private static int ticks, round;
  private static volatile Target target;
  private static BlockPos floorCell;
  private static boolean floor, wall, neighbors, broken, finished;

  private BuildingSupportSmoke() {}

  static boolean enabled() {
    return Boolean.getBoolean("craftq3.buildSupportSmoke");
  }

  static void tick(Minecraft client, BuildingSession session) {
    if (finished) return;
    int step = ++ticks;
    if (step == 1) {
      target = null;
      var server = Objects.requireNonNull(client.getSingleplayerServer());
      var id = client.player.getUUID();
      server.execute(
          () ->
              target =
                  find(
                      session,
                      Objects.requireNonNull(server.getPlayerList().getPlayer(id)),
                      round == 0));
    }
    if (step == 40) {
      if (target == null) throw new IllegalStateException("No original BSP torch support found");
      client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, target.hit());
    }
    if (step == 60) {
      var cell = target.hit().getBlockPos();
      if (!client.level.getBlockState(cell).is(round == 0 ? Blocks.TORCH : Blocks.WALL_TORCH))
        throw new IllegalStateException("Native torch placement failed: " + target);
      var host = Objects.requireNonNull(client.getSingleplayerServer());
      host.execute(
          () -> {
            var level = Objects.requireNonNull(host.getLevel(BuildingSession.DIMENSION));
            if (!level.getBlockState(cell).is(round == 0 ? Blocks.TORCH : Blocks.WALL_TORCH))
              throw new IllegalStateException("Server did not accept BSP torch");
            var support = cell.relative(target.face().getOpposite());
            if (!level.getBlockState(support).isAir())
              throw new IllegalStateException("Fixture support is a native block");
            // The temporary QA block is removed immediately, exercising normal shape updates from
            // that neighbor. Only the immutable BSP remains available to support the torch.
            level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(support, Blocks.AIR.defaultBlockState());
            if (!level.getBlockState(cell).is(round == 0 ? Blocks.TORCH : Blocks.WALL_TORCH)
                || !level.getBlockState(cell).canSurvive(level, cell))
              throw new IllegalStateException(
                  "Torch lost its BSP support after native neighbor update");
          });
    }
    if (step == 85) {
      var cell = target.hit().getBlockPos();
      if (!client.level.getBlockState(cell).is(round == 0 ? Blocks.TORCH : Blocks.WALL_TORCH))
        throw new IllegalStateException("Torch disappeared after support update");
      if (round == 0) {
        floor = true;
        floorCell = cell;
      } else wall = true;
      neighbors = true;
      net.minecraft.client.Screenshot.grab(
          client.gameDirectory,
          round == 0 ? "craftq3-support-floor.png" : "craftq3-support-wall.png",
          client.gameRenderer.mainRenderTarget(),
          1,
          message -> {});
      client.gameMode.startDestroyBlock(cell, target.face());
    }
    if (step == 105) {
      if (!client.level.getBlockState(target.hit().getBlockPos()).isAir())
        throw new IllegalStateException("Native torch breaking failed");
      broken = true;
      if (round++ == 0) {
        ticks = 0;
        return;
      }
      client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, target.hit());
    }
    if (step == 125) {
      if (!client.level.getBlockState(target.hit().getBlockPos()).is(Blocks.WALL_TORCH))
        throw new IllegalStateException("Replacement torch missing before leave");
      finished = true;
      session
          .leave()
          .whenComplete(
              (unused, failure) ->
                  client.execute(
                      () -> {
                        if (failure != null)
                          throw new IllegalStateException("Torch smoke return failed", failure);
                        checkAfterLeaveAndSwitch(client, session);
                      }));
    }
  }

  private static void checkAfterLeaveAndSwitch(Minecraft client, BuildingSession first) {
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    server
        .submit(
            () -> {
              verifyRetained(server, first);
              return null;
            })
        .whenComplete(
            (unused, failure) ->
                client.execute(
                    () -> {
                      if (failure != null)
                        throw new IllegalStateException(
                            "Post-leave building world failed", failure);
                      try {
                        var files =
                            dev.bluevista.craftq3.assets.fs.Pk3FileSystem.mount(
                                java.nio.file.Path.of(
                                    System.getProperty("craftq3.developmentInstallation")),
                                "baseq3");
                        BuildingSession second;
                        try {
                          second = new BuildingSession(client, files, "q3dm1");
                        } catch (Exception e) {
                          files.close();
                          throw e;
                        }
                        second
                            .enter()
                            .thenCompose(
                                ignored ->
                                    server.submit(
                                        () -> {
                                          if (second.region() == first.region())
                                            throw new IllegalStateException(
                                                "Distinct maps share a build region");
                                          var level =
                                              Objects.requireNonNull(
                                                  server.getLevel(BuildingSession.DIMENSION));
                                          if (BuildingWorlds.at(
                                                  level, second.transform().minecraftOrigin().x())
                                              != second.environment())
                                            throw new IllegalStateException(
                                                "New map geometry was not registered");
                                          verifyRetained(server, first);
                                          if (!BuildingWorldReloadSmoke.preparing())
                                            level.setBlockAndUpdate(
                                                target.hit().getBlockPos(),
                                                Blocks.AIR.defaultBlockState());
                                          return null;
                                        }))
                            .thenCompose(ignored -> second.leave())
                            .whenComplete(
                                (ignored, error) ->
                                    client.execute(
                                        () -> {
                                          if (error != null)
                                            throw new IllegalStateException(
                                                "Cross-map building world failed", error);
                                          if (BuildingWorldReloadSmoke.preparing()) {
                                            BuildingWorldReloadSmoke.checkpoint(
                                                client,
                                                target.hit().getBlockPos(),
                                                target.face(),
                                                floorCell);
                                            return;
                                          }
                                          try {
                                            Files.writeString(
                                                client
                                                    .gameDirectory
                                                    .toPath()
                                                    .resolve("craftq3-bridge.result"),
                                                "PASS support floor="
                                                    + floor
                                                    + " wall="
                                                    + wall
                                                    + " neighbors="
                                                    + neighbors
                                                    + " broken="
                                                    + broken
                                                    + " returned=true after-leave=true"
                                                    + " after-switch=true"
                                                    + " retained-collision=true\n");
                                          } catch (java.io.IOException e) {
                                            throw new java.io.UncheckedIOException(e);
                                          }
                                          client.stop();
                                        }));
                      } catch (Exception e) {
                        throw new IllegalStateException("Cross-map smoke setup failed", e);
                      }
                    }));
  }

  private static void verifyRetained(
      net.minecraft.server.MinecraftServer server, BuildingSession first) {
    var level = Objects.requireNonNull(server.getLevel(BuildingSession.DIMENSION));
    var cell = target.hit().getBlockPos();
    if (BuildingWorlds.at(level, cell.getX()) != first.environment())
      throw new IllegalStateException("Old map lost its server geometry");
    var support = cell.relative(target.face().getOpposite());
    if (!level.getBlockState(support).isAir())
      throw new IllegalStateException("Expected BSP-only support");
    level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
    level.setBlockAndUpdate(support, Blocks.AIR.defaultBlockState());
    if (!level.getBlockState(cell).is(Blocks.WALL_TORCH)
        || !level.getBlockState(cell).canSurvive(level, cell))
      throw new IllegalStateException("Old-map torch dropped after session closure or map switch");
    var probe =
        Objects.requireNonNull(
            net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(
                level, net.minecraft.world.entity.EntitySpawnReason.COMMAND));
    probe.setPos(floorCell.getX() + .5, floorCell.getY() + 1, floorCell.getZ() + .5);
    probe.move(
        net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(0, -2, 0));
    if (Math.abs(probe.getY() - floorCell.getY()) > .01)
      throw new IllegalStateException(
          "Entity fell through retained BSP floor: " + probe.position());
    probe.discard();
    System.out.println("CraftQ3 retained support/collision PASS old-region=" + first.region());
  }

  static Target find(
      BuildingSession session, net.minecraft.server.level.ServerPlayer player, boolean floor) {
    return find(session, player, floor, false);
  }

  static Target find(
      BuildingSession session,
      net.minecraft.server.level.ServerPlayer player,
      boolean floor,
      boolean full) {
    var map = session.map();
    for (var surface : map.faces()) {
      if (surface.type() != 1 || surface.vertexCount() == 0) continue;
      var normal = session.transform().directionToMinecraft(surface.normal()).scale(32);
      var face = Direction.getApproximateNearest(normal.x(), normal.y(), normal.z());
      if (floor ? face != Direction.UP : face.getAxis() == Direction.Axis.Y) continue;
      var center = new Vec3(0, 0, 0);
      for (int i = 0; i < surface.vertexCount(); i++)
        center = center.add(map.vertices().get(surface.firstVertex() + i).position());
      center = session.transform().toMinecraft(center.scale(1.0 / surface.vertexCount()));
      for (int sample = 0; sample < 9; sample++) {
        var offset =
            floor ? new Vec3(sample % 3 - 1, 0, sample / 3 - 1) : new Vec3(0, sample % 3 - 1, 0);
        var point = center.add(offset);
        var hit =
            session.geometry().pick(point.add(normal.scale(.5)), point.add(normal.scale(-.5)));
        if (hit.isEmpty()) continue;
        var value = hit.get();
        var cell = value.cell();
        var pos = new BlockPos((int) cell.x(), (int) cell.y(), (int) cell.z());
        var level = player.level();
        if (!level.getBlockState(pos).isAir()
            || !level.getBlockState(pos.relative(face.getOpposite())).isAir()) continue;
        var expected =
            floor
                ? Blocks.TORCH.defaultBlockState()
                : Blocks.WALL_TORCH
                    .defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, face);
        if (!expected.canSurvive(level, pos)
            || (full
                && !BuildingDecorations.supports(level, pos.relative(face.getOpposite()), face)))
          continue;
        var click = value.placementPoint();
        var eye = click.add(normal.scale(2)).add(new Vec3(0, floor ? 0 : 1, 0));
        var feet = eye.add(new Vec3(0, -player.getEyeHeight(), 0));
        var body =
            new BuildingGeometry.Box(
                feet.add(new Vec3(-.3, 0, -.3)), feet.add(new Vec3(.3, 1.8, .3)));
        if (!session.geometry().clear(body)
            || !level.noCollision(
                player,
                new AABB(
                    body.min().x(),
                    body.min().y(),
                    body.min().z(),
                    body.max().x(),
                    body.max().y(),
                    body.max().z()))) continue;
        var delta = click.add(eye.scale(-1));
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z()));
        float pitch =
            (float) -Math.toDegrees(Math.atan2(delta.y(), Math.hypot(delta.x(), delta.z())));
        player.connection.teleport(feet.x(), feet.y(), feet.z(), yaw, pitch);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH, 64));
        player.containerMenu.broadcastChanges();
        System.out.println("CraftQ3 native support candidate " + pos + " face=" + face);
        return new Target(
            new BlockHitResult(
                new net.minecraft.world.phys.Vec3(click.x(), click.y(), click.z()),
                face,
                pos,
                false),
            face);
      }
    }
    throw new IllegalStateException(
        "No clear original " + (floor ? "floor" : "wall") + " support face");
  }
}

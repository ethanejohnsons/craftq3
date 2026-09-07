package dev.bluevista.craftq3.fabric.building;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.*;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;

/** Native decoration placement, frame interaction, breaking and retained original wall support. */
public final class BuildingDecorationSmoke {
  private static int ticks;
  private static CompletableFuture<?> pending;
  private static BuildingSupportSmoke.Target target;
  private static UUID frameId, paintingId;
  private static boolean finished;
  private static int unlit, lit;

  public static boolean baselineLighting() {
    return enabled() && Boolean.getBoolean("craftq3.lightingSmoke") && ticks >= 370 && ticks < 390;
  }

  private BuildingDecorationSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && (Boolean.getBoolean("craftq3.decorationSmoke")
            || Boolean.getBoolean("craftq3.lightingSmoke"));
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
    var host = client.getSingleplayerServer();
    var id = client.player.getUUID();
    if (step == 60
        || step == 140
        || step == 180
        || step == 220
        || step == 280
        || step == 360
        || step == 460)
      pending =
          host.submit(
              () -> {
                var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
                if (!host.getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath()
                    .normalize()
                    .getFileName()
                    .toString()
                    .equals("CraftQ3 Bridge QA"))
                  throw new IllegalStateException("Requires private QA save");
                var level = player.level();
                if (step == 60) {
                  target = BuildingSupportSmoke.find(session, player, false, true);
                  equip(player, Items.ITEM_FRAME);
                }
                if (step == 140) {
                  var frames =
                      level.getEntitiesOfClass(
                          ItemFrame.class,
                          new net.minecraft.world.phys.AABB(target.hit().getBlockPos())
                              .inflate(.1));
                  if (frames.size() != 1 || !frames.getFirst().survives())
                    throw new IllegalStateException(
                        "Native frame placement/support failed: " + frames.size());
                  var pos = target.hit().getBlockPos();
                  var face = target.face();
                  if (new ItemFrame(level, pos, face).survives()
                      || new ItemFrame(level, pos.relative(face), face).survives()
                      || new ItemFrame(level, pos.relative(face.getOpposite()), face).survives())
                    throw new IllegalStateException(
                        "Duplicate, unsupported or buried frame survived");
                  frameId = frames.getFirst().getUUID();
                  equip(player, Items.DIAMOND);
                }
                if (step == 180) {
                  var frame = (ItemFrame) level.getEntity(frameId);
                  if (frame == null || !frame.getItem().is(Items.DIAMOND))
                    throw new IllegalStateException("Native item insertion failed");
                  player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                  player.containerMenu.broadcastChanges();
                }
                if (step == 220) {
                  var frame = (ItemFrame) level.getEntity(frameId);
                  if (frame == null || frame.getRotation() != 1)
                    throw new IllegalStateException("Native frame rotation failed");
                }
                if (step == 280) {
                  if (level.getEntity(frameId) != null)
                    throw new IllegalStateException("Native frame breaking failed");
                  equip(player, Items.PAINTING);
                }
                if (step == 360) {
                  var paintings =
                      level.getEntitiesOfClass(
                          Painting.class,
                          new net.minecraft.world.phys.AABB(target.hit().getBlockPos()).inflate(4));
                  if (paintings.size() != 1 || !paintings.getFirst().survives())
                    throw new IllegalStateException(
                        "Native painting placement/support failed: " + paintings.size());
                  paintingId = paintings.getFirst().getUUID();
                }
                if (step == 460) {
                  var painting = (Painting) level.getEntity(paintingId);
                  if (painting == null || !painting.survives())
                    throw new IllegalStateException(
                        "Painting lost original wall support during native ticking");
                }
                return null;
              });
    if (step == 100 || step == 300)
      client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, target.hit());
    if (step == 160 || step == 200) {
      var frame =
          client
              .level
              .getEntitiesOfClass(
                  ItemFrame.class,
                  new net.minecraft.world.phys.AABB(target.hit().getBlockPos()).inflate(.1))
              .stream()
              .filter(e -> e.getUUID().equals(frameId))
              .findFirst()
              .orElseThrow();
      client.gameMode.interact(
          client.player,
          frame,
          new net.minecraft.world.phys.EntityHitResult(frame),
          InteractionHand.MAIN_HAND);
    }
    if (step == 240 || step == 260)
      client
          .level
          .getEntitiesOfClass(
              ItemFrame.class,
              new net.minecraft.world.phys.AABB(target.hit().getBlockPos()).inflate(.1))
          .stream()
          .filter(e -> e.getUUID().equals(frameId))
          .findFirst()
          .ifPresent(frame -> client.gameMode.attack(client.player, frame));
    if (Boolean.getBoolean("craftq3.lightingSmoke") && (step == 380 || step == 400)) {
      int value =
          net.minecraft.util.LightCoordsUtil.getLightCoords(
              client.level, target.hit().getBlockPos());
      if (step == 380) {
        unlit = value;
        net.minecraft.client.Screenshot.grab(
            client.gameDirectory,
            "craftq3-building-light-before.png",
            client.gameRenderer.mainRenderTarget(),
            1,
            message -> {});
      } else {
        lit = value;
        if (net.minecraft.util.LightCoordsUtil.block(lit)
                <= net.minecraft.util.LightCoordsUtil.block(unlit)
            || net.minecraft.util.LightCoordsUtil.sky(lit)
                != net.minecraft.util.LightCoordsUtil.sky(unlit))
          throw new IllegalStateException(
              "Original light grid did not brighten native decoration: " + unlit + " -> " + lit);
        var cow =
            net.minecraft.world.entity.EntityTypes.COW.create(
                client.level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        cow.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(target.hit().getBlockPos()));
        var renderer = client.getEntityRenderDispatcher().getRenderer(cow);
        if (net.minecraft.util.LightCoordsUtil.block(renderer.getPackedLightCoords(cow, 1)) == 0)
          throw new IllegalStateException("Native entity lighting ignored BSP grid");
        cow.setRemainingFireTicks(100);
        cow.setSharedFlagOnFire(true);
        if (net.minecraft.util.LightCoordsUtil.block(renderer.getPackedLightCoords(cow, 1)) != 15)
          throw new IllegalStateException("BSP lighting dimmed native emissive entity");
        System.out.println(
            "CraftQ3 native grid lighting PASS packed="
                + unlit
                + " -> "
                + lit
                + " native-emission=true");
      }
    }
    if (step == 400)
      net.minecraft.client.Screenshot.grab(
          client.gameDirectory,
          "craftq3-building-decoration.png",
          client.gameRenderer.mainRenderTarget(),
          1,
          message -> {});
    if (step == 470) {
      finished = true;
      session
          .leave()
          .thenCompose(
              unused ->
                  host.submit(
                      () -> {
                        var level =
                            Objects.requireNonNull(host.getLevel(BuildingSession.DIMENSION));
                        var painting = (Painting) level.getEntity(paintingId);
                        if (painting == null || !painting.survives())
                          throw new IllegalStateException("Painting lost BSP support after leave");
                        painting.discard();
                        return null;
                      }))
          .whenComplete(
              (unused, error) ->
                  client.execute(
                      () -> {
                        if (error != null) {
                          client.stop();
                          throw new IllegalStateException("Decoration return failed", error);
                        }
                        try {
                          Files.writeString(
                              client.gameDirectory.toPath().resolve("craftq3-bridge.result"),
                              "PASS decorations frame-placement=true insert=true rotate=true"
                                  + " break=true painting=true native-ticks=true"
                                  + " retained-support=true returned=true\n");
                        } catch (java.io.IOException e) {
                          throw new java.io.UncheckedIOException(e);
                        }
                        client.stop();
                      }));
    }
  }

  private static void equip(ServerPlayer player, Item item) {
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
    player.containerMenu.broadcastChanges();
  }
}

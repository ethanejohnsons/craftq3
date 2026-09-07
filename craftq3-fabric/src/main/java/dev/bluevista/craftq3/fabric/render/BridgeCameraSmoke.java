package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.fabric.bridge.BridgeGame;
import dev.bluevista.craftq3.render.CgameFrame;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** Original first/orbit cameras, camera-wall collision and independent player aim. */
public final class BridgeCameraSmoke {
  private static final List<BlockPos> wall = new ArrayList<>();
  private static CameraType previousCamera;
  private static CompletableFuture<Void> pending;
  private static boolean first, covered, open, orbit, returned;
  private static volatile boolean capture;
  private static double coveredDistance, openDistance;

  private BridgeCameraSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeCameraSmoke");
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
    previousCamera = client.options.getCameraType();
    client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    var id = Objects.requireNonNull(client.player).getUUID();
    return server.submit(
        () -> {
          if (!server
              .getWorldPath(LevelResource.ROOT)
              .toAbsolutePath()
              .normalize()
              .getFileName()
              .toString()
              .equals("CraftQ3 Bridge QA"))
            throw new IllegalStateException("Requires private QA save");
          var player = Objects.requireNonNull(server.getPlayerList().getPlayer(id));
          var level = player.level();
          var pos = player.blockPosition();
          wall.clear();
          for (int x = -8; x <= 8; x++)
            for (int z = -8; z <= 8; z++)
              for (int y = -1; y <= 6; y++) {
                var cell = pos.offset(x, y, z);
                boolean barrier = x == -2 && y >= 0;
                level.setBlockAndUpdate(
                    cell, (y == -1 || barrier ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                if (barrier) wall.add(cell);
              }
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(20)))
            mob.discard();
          player.connection.teleport(pos.getX() + .5, pos.getY(), pos.getZ() + .5, -90, 0);
          first = covered = open = orbit = returned = capture = false;
          pending = null;
          return null;
        });
  }

  public static void step(Minecraft client, BridgeGame game, int frame) {
    if (pending != null && pending.isDone()) {
      pending.join();
      pending = null;
    }
    if (frame == 90)
      game.command("cg_thirdPerson 1; cg_thirdPersonRange 128; cg_thirdPersonAngle 0");
    if (frame == 160) {
      var host = Objects.requireNonNull(client.getSingleplayerServer());
      var id = Objects.requireNonNull(client.player).getUUID();
      pending =
          host.submit(
              () -> {
                var player = Objects.requireNonNull(host.getPlayerList().getPlayer(id));
                for (var pos : wall)
                  player.level().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                return null;
              });
    }
    if (frame == 260) game.command("cg_thirdPersonAngle 90");
    if (frame == 360) game.command("cg_thirdPerson 0");
  }

  static void observe(
      Minecraft client, BridgeScreen screen, BridgeGame game, CgameFrame frame, int number) {
    if (!enabled() || number < 40 || frame == null) return;
    var camera = client.gameRenderer.mainCamera();
    var ref = Objects.requireNonNull(screen.worldView());
    var player = Objects.requireNonNull(client.player);
    if (camera.isDetached() || client.options.getCameraType() != CameraType.THIRD_PERSON_FRONT)
      throw new IllegalStateException(
          "Bridge changed native camera preference or exposed native body");
    if (camera.position().distanceTo(screen.cameraPosition()) > 1e-5)
      throw new IllegalStateException(
          "Native camera did not follow cgame position at frame "
              + number
              + ": "
              + camera.position()
              + " != "
              + screen.cameraPosition());
    var f = camera.forwardVector();
    if (Math.abs(f.x() - ref.axisX().x()) > 1e-5
        || Math.abs(f.y() - ref.axisX().z()) > 1e-5
        || Math.abs(f.z() + ref.axisX().y()) > 1e-5)
      throw new IllegalStateException("Native camera forward diverged");
    var inverse = screen.cameraRotation().transpose();
    var actual = new org.joml.Matrix4f().rotation(camera.rotation());
    if (!actual.equals(inverse, 1e-5f))
      throw new IllegalStateException("Native camera orientation diverged");
    var aim = game.viewAngles();
    if (Math.abs(net.minecraft.util.Mth.wrapDegrees(player.getYRot() + 90 + aim.y())) > .001
        || Math.abs(player.getXRot() - aim.x()) > .001)
      throw new IllegalStateException("Orbit camera changed player aim");
    int bodies = 0, weapons = 0;
    var models = new ArrayList<String>();
    for (var command : frame.commands())
      if (command instanceof CgameFrame.View view && view.refdef().worldModel())
        for (var entity : view.entities())
          if (entity.visible(false) && entity.model() != null) {
            models.add(entity.model().name() + " fx=" + entity.renderFx());
            if (entity.model().name().startsWith("models/players/")) bodies++;
            if (entity.depthHack()) weapons++;
          }
    var feet = game.feet();
    double distance = Math.hypot(camera.position().x - feet.x(), camera.position().z - feet.z());
    if (number == 60) {
      if (bodies != 0 || weapons == 0)
        throw new IllegalStateException("Initial first-person model masks failed");
      first = true;
    }
    if (number == 140) {
      System.out.println("CraftQ3 third-person submitted models: " + models);
      if (bodies < 1 || weapons != 0 || distance < .2 || distance > 2)
        throw new IllegalStateException(
            "Third-person camera wall failed: "
                + distance
                + " bodies="
                + bodies
                + " weapons="
                + weapons);
      coveredDistance = distance;
      covered = true;
    }
    if (number == 230) {
      if (bodies < 1 || weapons != 0 || distance < 3 || distance < coveredDistance + 1)
        throw new IllegalStateException(
            "Third-person camera did not expand after wall removal: " + distance);
      openDistance = distance;
      open = true;
      Screenshot.takeScreenshot(
          client.gameRenderer.mainRenderTarget(),
          image -> {
            try (image) {
              image.writeToFile(
                  java.nio.file.Path.of("/tmp/craftq3-bridge-camera-third-person.png"));
              capture = true;
            } catch (java.io.IOException error) {
              throw new java.io.UncheckedIOException(error);
            }
          });
    }
    if (number == 320) {
      if (bodies < 1
          || Math.abs(net.minecraft.util.Mth.wrapDegrees(camera.yRot() - player.getYRot())) < 1)
        throw new IllegalStateException("Orbit camera did not differ from player aim");
      orbit = true;
    }
    if (number == 410) {
      if (bodies != 0 || weapons == 0 || distance > .05)
        throw new IllegalStateException("First-person return failed: " + distance);
      returned = true;
    }
  }

  static void closed(Minecraft client) {
    if (enabled() && previousCamera != null) client.options.setCameraType(previousCamera);
  }

  public static String result(Minecraft client) {
    if (pending != null) pending.join();
    if (!first || !covered || !open || !orbit || !returned || !capture)
      throw new IllegalStateException(
          "Camera fixture incomplete: "
              + first
              + "/"
              + covered
              + "/"
              + open
              + "/"
              + orbit
              + "/"
              + returned
              + "/"
              + capture);
    if (client.options.getCameraType() != CameraType.THIRD_PERSON_FRONT)
      throw new IllegalStateException("Native camera preference changed");
    return "camera=true first-person=true quake-body=true wall=true orbit-aim=true preference=true"
        + " returned-first-person=true covered-distance="
        + coveredDistance
        + " open-distance="
        + openDistance;
  }
}

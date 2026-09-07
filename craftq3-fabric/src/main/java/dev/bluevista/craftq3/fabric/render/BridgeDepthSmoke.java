package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;

/** GPU readback of submitted Quake geometry behind and in front of a native stone wall. */
public final class BridgeDepthSmoke {
  private static int frames, phase, hiddenPixels, visiblePixels;
  private static boolean pending, done;

  private BridgeDepthSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.bridgeDepthSmoke");
  }

  public static CompletableFuture<Void> setup(Minecraft client) {
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
          var origin = player.blockPosition();
          for (int dx = -2; dx <= 8; dx++)
            for (int dz = -4; dz <= 4; dz++)
              for (int dy = -1; dy <= 5; dy++)
                level.setBlockAndUpdate(
                    origin.offset(dx, dy, dz),
                    (dy == -1 || dx == 2 && dy < 4 ? Blocks.STONE : Blocks.AIR)
                        .defaultBlockState());
          for (var mob : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12)))
            mob.discard();
          player.connection.teleport(origin.getX() + .5, origin.getY(), origin.getZ() + .5, -90, 0);
          frames = phase = hiddenPixels = visiblePixels = 0;
          pending = done = false;
          return null;
        });
  }

  static CgameFrame frame(CgameFrame original) {
    if (!enabled()) return original;
    var commands = new ArrayList<CgameFrame.Command>();
    for (var command : original.commands()) {
      var view = (CgameFrame.View) command;
      var ref = view.refdef();
      double distance = (phase == 0 ? 4 : 1) * 32;
      var center =
          ref.origin()
              .add(ref.axisX().scale(distance))
              .add(ref.axisY().scale(-distance * .25))
              .add(ref.axisZ().scale(distance * .15));
      var left = ref.axisY().scale(distance * .06);
      var up = ref.axisZ().scale(distance * .06);
      var vertices =
          List.of(
              vertex(center.add(left).add(up)),
              vertex(center.add(left.scale(-1)).add(up)),
              vertex(center.add(left.scale(-1)).add(up.scale(-1))),
              vertex(center.add(left).add(up.scale(-1))));
      var polygons = new ArrayList<>(view.polygons());
      polygons.add(new CgameFrame.Poly("$whiteimage", vertices));
      polygons.add(new CgameFrame.Poly("$whiteimage", vertices.reversed()));
      commands.add(new CgameFrame.View(ref, view.entities(), polygons, view.lights()));
    }
    return new CgameFrame(commands, original.assets(), original.timeMillis());
  }

  private static CgameFrame.PolyVertex vertex(Vec3 point) {
    return new CgameFrame.PolyVertex(point, new BspMap.Uv(0, 0), 0xff00ffff);
  }

  static void capture(Minecraft client, CgameFrame.Refdef ref) {
    if (done || pending || ref == null || ++frames < 80) return;
    pending = true;
    Screenshot.takeScreenshot(
        client.gameRenderer.mainRenderTarget(),
        image -> {
          int x = (int) (image.getWidth() * (.5 + .125 / Math.tan(Math.toRadians(ref.fovX()) / 2)));
          int y =
              (int) (image.getHeight() * (.5 - .075 / Math.tan(Math.toRadians(ref.fovY()) / 2)));
          int count = 0;
          try (image) {
            for (int dx = -8; dx <= 8; dx++)
              for (int dy = -8; dy <= 8; dy++) {
                int color = image.getPixel(x + dx, y + dy);
                if ((color & 255) > 200
                    && ((color >>> 16) & 255) > 200
                    && ((color >>> 8) & 255) < 50) count++;
              }
            image.writeToFile(
                Path.of(
                    "/tmp/craftq3-bridge-depth-" + (phase == 0 ? "hidden" : "visible") + ".png"));
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
          int pixels = count;
          client.execute(
              () -> {
                System.out.println(
                    "CraftQ3 shared depth phase="
                        + phase
                        + " magenta="
                        + pixels
                        + " probe="
                        + x
                        + ","
                        + y);
                if (phase == 0) hiddenPixels = pixels;
                else visiblePixels = pixels;
                if (phase++ == 1) done = true;
                frames = 0;
                pending = false;
              });
        });
  }

  public static String result() {
    if (!done || hiddenPixels != 0 || visiblePixels < 250)
      throw new IllegalStateException(
          "Shared depth failed: done="
              + done
              + " hidden="
              + hiddenPixels
              + " visible="
              + visiblePixels);
    return "shared-depth=true hidden-pixels=" + hiddenPixels + " visible-pixels=" + visiblePixels;
  }
}

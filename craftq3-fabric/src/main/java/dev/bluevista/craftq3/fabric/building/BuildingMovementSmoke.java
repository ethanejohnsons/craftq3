package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.core.math.Vec3;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Read-only geometry search and native movement assertions in the private building QA session. */
final class BuildingMovementSmoke {
  private BuildingMovementSmoke() {}

  static String verify(Minecraft client, BuildingSession session) {
    var player = client.player;
    var saved = player.position();
    boolean grounded = player.onGround();
    var velocity = player.getDeltaMovement();
    var geometry = session.geometry();
    try {
      var collide = Entity.class.getDeclaredMethod("collide", net.minecraft.world.phys.Vec3.class);
      collide.setAccessible(true);
      // Search the original map for a clear, sub-block BSP ledge. No fixture blocks or BSP
      // mutations are needed, and player state is restored before the next client tick.
      for (int ring = 0; ring <= 32; ring++) {
        for (int dx = -ring; dx <= ring; dx++) {
          for (int dz = -ring; dz <= ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            double x = saved.x + dx, z = saved.z + dz;
            var high =
                new BuildingGeometry.Box(
                    new Vec3(x - .3, saved.y + 16, z - .3),
                    new Vec3(x + .3, saved.y + 17.8, z + .3));
            var floor = geometry.trace(high, new Vec3(0, -48, 0));
            if (floor.startSolid() || floor.fraction() >= 1) continue;
            double y = high.min().y() - 48 * floor.fraction();
            var body = high.moved(new Vec3(0, y - high.min().y(), 0));
            for (int direction = 0; direction < 4; direction++) {
              double mx = direction < 2 ? (direction == 0 ? .75 : -.75) : 0;
              double mz = direction >= 2 ? (direction == 2 ? .75 : -.75) : 0;
              var requested = new Vec3(mx, 0, mz);
              var flat = geometry.clip(body, requested);
              if (Math.hypot(flat.x(), flat.z()) > .74) continue;
              var region =
                  new BuildingGeometry.Box(
                      new Vec3(
                          body.min().x() + Math.min(0, mx), y, body.min().z() + Math.min(0, mz)),
                      new Vec3(
                          body.max().x() + Math.max(0, mx),
                          body.max().y() + .6,
                          body.max().z() + Math.max(0, mz)));
              var support = geometry.stepSurface(region, player.maxUpStep());
              if (support.isEmpty()
                  || support.getAsDouble() - y < .04
                  || support.getAsDouble() - y > .58) continue;
              player.setPos(x, y, z);
              var move = new net.minecraft.world.phys.Vec3(mx, 0, mz);
              player.setOnGround(true);
              var stepped = (net.minecraft.world.phys.Vec3) collide.invoke(player, move);
              if (stepped.horizontalDistance() < .74) continue;
              if (stepped.y < .04 || stepped.y > player.maxUpStep() + 1e-5)
                throw new IllegalStateException("Invalid native step height: " + stepped);
              player.setOnGround(false);
              var airborne = (net.minecraft.world.phys.Vec3) collide.invoke(player, move);
              if (airborne.horizontalDistance() >= stepped.horizontalDistance() - .01
                  || airborne.y != 0)
                throw new IllegalStateException("Airborne entity incorrectly stepped: " + airborne);
              player.setOnGround(true);
              player.move(net.minecraft.world.entity.MoverType.SELF, move);
              if (player
                      .position()
                      .distanceTo(new net.minecraft.world.phys.Vec3(x, y, z).add(stepped))
                  > .001)
                throw new IllegalStateException("Native Entity.move did not apply its BSP step");
              return "step=true airborne=true rise=" + stepped.y + " at=" + x + "," + y + "," + z;
            }
          }
        }
      }
      throw new IllegalStateException("No traversable original BSP ledge found near " + saved);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "Native movement audit failed",
          e instanceof InvocationTargetException invocation ? invocation.getCause() : e);
    } finally {
      player.setPos(saved);
      player.setOnGround(grounded);
      player.setDeltaMovement(velocity);
      player.setOldPosAndRot();
    }
  }
}

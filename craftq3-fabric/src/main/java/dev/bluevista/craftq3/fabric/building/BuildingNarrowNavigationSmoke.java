package dev.bluevista.craftq3.fabric.building;

import java.nio.file.Files;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** Actual small-mob traversal where a whole native path cell intersects original BSP solids. */
public final class BuildingNarrowNavigationSmoke {
  private record Cell(double floor, boolean coarseBlocked, boolean largeBlocked) {}

  private record Route(BlockPos start, BlockPos target, double floor) {}

  private static int ticks;
  private static boolean pending, started, finished;
  private static Mob mob;
  private static Route route;
  private static String evidence;

  private BuildingNarrowNavigationSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.narrowNavigationSmoke");
  }

  public static void tick(Minecraft client, BuildingSession session) {
    if (finished || ++ticks < 60 || pending || ticks % 5 != 0) return;
    pending = true;
    var server = Objects.requireNonNull(client.getSingleplayerServer());
    server
        .submit(
            () -> {
              var level = Objects.requireNonNull(server.getLevel(BuildingSession.DIMENSION));
              if (!server
                  .getWorldPath(LevelResource.ROOT)
                  .toAbsolutePath()
                  .normalize()
                  .getFileName()
                  .toString()
                  .equals("CraftQ3 Bridge QA"))
                throw new IllegalStateException("Requires private QA save");
              try {
                if (!started) {
                  started = true;
                  setup(level, session);
                  return null;
                }
                double distance =
                    mob.position()
                        .distanceTo(
                            new Vec3(
                                route.target().getX() + .5,
                                route.floor(),
                                route.target().getZ() + .5));
                if (distance < .55) {
                  String result =
                      "PASS narrow-navigation walked=true actual-body=true size-rejection=true "
                          + evidence
                          + " distance="
                          + distance;
                  mob.discard();
                  mob = null;
                  return result;
                }
                if (ticks > 500 || !mob.isAlive() || mob.getY() < route.floor() - 2)
                  throw new IllegalStateException(
                      "Native small mob failed narrow route: "
                          + mob.position()
                          + " target="
                          + route.target()
                          + " path="
                          + mob.getNavigation().getPath());
                return null;
              } catch (RuntimeException e) {
                if (mob != null) {
                  mob.discard();
                  mob = null;
                }
                throw e;
              }
            })
        .whenComplete(
            (result, error) ->
                client.execute(
                    () -> {
                      pending = false;
                      if (error != null) {
                        finished = true;
                        client.stop();
                        throw new IllegalStateException("Narrow navigation smoke failed", error);
                      }
                      if (result == null) return;
                      finished = true;
                      session
                          .leave()
                          .whenComplete(
                              (unused, failure) ->
                                  client.execute(
                                      () -> {
                                        if (failure != null) {
                                          client.stop();
                                          throw new IllegalStateException(
                                              "Narrow navigation return failed", failure);
                                        }
                                        try {
                                          Files.writeString(
                                              client
                                                  .gameDirectory
                                                  .toPath()
                                                  .resolve("craftq3-bridge.result"),
                                              result + " returned=true\n");
                                          System.out.println(result + " returned=true");
                                        } catch (java.io.IOException e) {
                                          throw new java.io.UncheckedIOException(e);
                                        }
                                        client.stop();
                                      }));
                    }));
  }

  private static void setup(ServerLevel level, BuildingSession session) {
    mob = Objects.requireNonNull(EntityTypes.CHICKEN.create(level, EntitySpawnReason.COMMAND));
    mob.removeAllGoals(goal -> true);
    mob.setPersistenceRequired();
    route = find(level, session, mob);
    System.out.println(
        "CraftQ3 narrow route " + route + " body=" + mob.getBbWidth() + "x" + mob.getBbHeight());
    mob.setPos(route.start().getX() + .5, route.floor() + .05, route.start().getZ() + .5);
    mob.move(MoverType.SELF, new Vec3(0, -.1, 0));
    if (!level.addFreshEntity(mob))
      throw new IllegalStateException("Cannot add narrow-navigation mob");
    var path = mob.getNavigation().createPath(route.target(), 0);
    if (path == null || !path.canReach())
      throw new IllegalStateException("Native path rejected valid body clearance: " + path);
    int tight = 0;
    for (int i = 0; i < path.getNodeCount(); i++) {
      var p = path.getNodePos(i);
      var cell = cell(level, session, mob, p);
      if (cell == null)
        throw new IllegalStateException("Native path contains invalid small-body position: " + p);
      if (cell.coarseBlocked()) tight++;
    }
    if (tight == 0)
      throw new IllegalStateException("Path did not exercise formerly rejected BSP cells");
    if (!mob.getNavigation().moveTo(path, 1))
      throw new IllegalStateException("Native narrow moveTo rejected");
    evidence = "nodes=" + path.getNodeCount() + " tight-nodes=" + tight;
    System.out.println("CraftQ3 narrow native path " + evidence);
  }

  private static Route find(ServerLevel level, BuildingSession session, Mob mob) {
    var map = session.map();
    var candidates = new LinkedHashSet<BlockPos>();
    for (var face : map.faces()) {
      if (face.type() != 1
          || face.vertexCount() == 0
          || session.transform().directionToMinecraft(face.normal()).y() * 32 < .999) continue;
      for (int i = 0; i < face.vertexCount(); i++) {
        var vertex =
            session.transform().toMinecraft(map.vertices().get(face.firstVertex() + i).position());
        int y = (int) Math.floor(vertex.y() + .5);
        for (int dx = -1; dx <= 1; dx++)
          for (int dz = -1; dz <= 1; dz++)
            candidates.add(
                new BlockPos(
                    (int) Math.floor(vertex.x()) + dx, y, (int) Math.floor(vertex.z()) + dz));
      }
    }
    var cells = new HashMap<BlockPos, Optional<Cell>>();
    for (var target : candidates) {
      var end =
          cells
              .computeIfAbsent(target, p -> Optional.ofNullable(cell(level, session, mob, p)))
              .orElse(null);
      if (end == null || !end.coarseBlocked() || !end.largeBlocked()) continue;
      for (int axis = 0; axis < 2; axis++)
        for (int sign : new int[] {1, -1}) {
          boolean valid = true;
          for (int step = 1; step <= 4; step++) {
            var p = target.offset(axis == 0 ? step * sign : 0, 0, axis == 1 ? step * sign : 0);
            var value =
                cells
                    .computeIfAbsent(p, q -> Optional.ofNullable(cell(level, session, mob, q)))
                    .orElse(null);
            if (value == null || Math.abs(value.floor() - end.floor()) > .01) {
              valid = false;
              break;
            }
            var body = body(p, value.floor(), mob.getBbWidth(), mob.getBbHeight());
            if (!session
                .geometry()
                .pathClear(
                    body,
                    new dev.bluevista.craftq3.core.math.Vec3(
                        axis == 0 ? -sign : 0, 0, axis == 1 ? -sign : 0))) {
              valid = false;
              break;
            }
          }
          if (valid) {
            var start = target.offset(axis == 0 ? 4 * sign : 0, 0, axis == 1 ? 4 * sign : 0);
            level.getChunkAt(start);
            level.getChunkAt(target);
            return new Route(start, target, end.floor());
          }
        }
    }
    throw new IllegalStateException(
        "No original narrow route found for small native mob among "
            + candidates.size()
            + " cells");
  }

  private static Cell cell(ServerLevel level, BuildingSession session, Mob mob, BlockPos pos) {
    var floor = session.geometry().navigationFloor(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    if (floor.isEmpty()) return null;
    var body = body(pos, floor.getAsDouble(), mob.getBbWidth(), mob.getBbHeight());
    if (!session.geometry().clear(body)
        || !level.noCollision(
            new AABB(
                body.min().x(),
                body.min().y(),
                body.min().z(),
                body.max().x(),
                body.max().y(),
                body.max().z()))) return null;
    var coarse =
        new BuildingGeometry.Box(
            new dev.bluevista.craftq3.core.math.Vec3(
                pos.getX() + .01,
                Math.max(pos.getY(), floor.getAsDouble()) + .01,
                pos.getZ() + .01),
            new dev.bluevista.craftq3.core.math.Vec3(
                pos.getX() + .99, pos.getY() + .99, pos.getZ() + .99));
    return new Cell(
        floor.getAsDouble(),
        !session.geometry().clear(coarse),
        !session.geometry().clear(body(pos, floor.getAsDouble(), .9, 1.4)));
  }

  private static BuildingGeometry.Box body(BlockPos p, double floor, double width, double height) {
    return new BuildingGeometry.Box(
        new dev.bluevista.craftq3.core.math.Vec3(
            p.getX() + .5 - width / 2, floor + .005, p.getZ() + .5 - width / 2),
        new dev.bluevista.craftq3.core.math.Vec3(
            p.getX() + .5 + width / 2, floor + height, p.getZ() + .5 + width / 2));
  }
}

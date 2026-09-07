package dev.bluevista.craftq3.fabric.building;

import java.nio.file.Files;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;

/** Native explosion audit over a discovered original BSP barrier in the private QA save. */
public final class BuildingExplosionSmoke {
  private record Fixture(Vec3 center, BlockPos exposed, BlockPos covered) {}

  private static int ticks;
  private static boolean started;

  private BuildingExplosionSmoke() {}

  public static boolean enabled() {
    return FabricLoader.getInstance().isDevelopmentEnvironment()
        && Boolean.getBoolean("craftq3.explosionSmoke");
  }

  public static void tick(Minecraft client, BuildingSession session) {
    if (started || ++ticks < 60) return;
    started = true;
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
              return verify(level, session);
            })
        .whenComplete(
            (result, error) ->
                client.execute(
                    () -> {
                      if (error != null) {
                        client.stop();
                        throw new IllegalStateException("Explosion smoke failed", error);
                      }
                      session
                          .leave()
                          .whenComplete(
                              (unused, failure) ->
                                  client.execute(
                                      () -> {
                                        if (failure != null) {
                                          client.stop();
                                          throw new IllegalStateException(
                                              "Explosion return failed", failure);
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

  private static String verify(ServerLevel level, BuildingSession session) {
    var fixture = find(level, session);
    System.out.println("CraftQ3 explosion fixture " + fixture);
    var covered = cow(level, fixture.covered());
    var exposed = cow(level, fixture.exposed());
    float coveredHealth, exposedHealth;
    try {
      if (!level.addFreshEntity(covered) || !level.addFreshEntity(exposed))
        throw new IllegalStateException("Could not add explosion targets");
      var query = new AABB(fixture.center(), fixture.center()).inflate(8);
      var targets = level.getEntities((Entity) null, query, e -> e == covered || e == exposed);
      if (targets.size() != 2)
        throw new IllegalStateException("Explosion target chunks not accessible");
      float hidden = ServerExplosion.getSeenPercent(fixture.center(), covered);
      float visible = ServerExplosion.getSeenPercent(fixture.center(), exposed);
      if (hidden != 0 || visible != 1)
        throw new IllegalStateException("Exposure mismatch: " + hidden + " / " + visible);
      var calculator =
          new ExplosionDamageCalculator() {
            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
              return entity == covered || entity == exposed;
            }
          };
      var explosion =
          new ServerExplosion(
              level,
              null,
              null,
              calculator,
              fixture.center(),
              4,
              false,
              Explosion.BlockInteraction.KEEP);
      float expectedCovered =
          Math.max(
              0,
              covered.getHealth() - calculator.getEntityDamageAmount(explosion, covered, hidden));
      explosion.explode();
      coveredHealth = covered.getHealth();
      exposedHealth = exposed.getHealth();
      if (Math.abs(coveredHealth - expectedCovered) > .001
          || exposedHealth >= coveredHealth
          || covered.getDeltaMovement().lengthSqr() > 1e-12
          || exposed.getDeltaMovement().lengthSqr() <= 1e-12)
        throw new IllegalStateException(
            "Native damage/knockback mismatch: " + coveredHealth + " / " + exposedHealth);
    } finally {
      covered.discard();
      exposed.discard();
    }
    var beforeExposed = level.getBlockState(fixture.exposed());
    var beforeCovered = level.getBlockState(fixture.covered());
    try {
      level.setBlockAndUpdate(fixture.exposed(), Blocks.GLASS.defaultBlockState());
      level.setBlockAndUpdate(fixture.covered(), Blocks.GLASS.defaultBlockState());
      var calculator =
          new ExplosionDamageCalculator() {
            @Override
            public boolean shouldBlockExplode(
                Explosion explosion,
                BlockGetter blocks,
                BlockPos pos,
                BlockState state,
                float power) {
              return (pos.equals(fixture.exposed()) || pos.equals(fixture.covered()))
                  && super.shouldBlockExplode(explosion, blocks, pos, state, power);
            }

            @Override
            public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
              return false;
            }
          };
      long seed = unshieldedSeed(level, fixture);
      level.getRandom().setSeed(seed);
      long begin = System.nanoTime();
      new ServerExplosion(
              level,
              null,
              null,
              calculator,
              fixture.center(),
              4,
              false,
              Explosion.BlockInteraction.DESTROY)
          .explode();
      System.out.println(
          "CraftQ3 BSP block blast elapsed-ms=" + (System.nanoTime() - begin) / 1_000_000.0);
      if (!level.getBlockState(fixture.exposed()).isAir()
          || !level.getBlockState(fixture.covered()).is(Blocks.GLASS))
        throw new IllegalStateException(
            "Native blast did not destroy exposed glass and retain covered glass");
      if (!BuildingOcclusion.blocked(level, fixture.center(), Vec3.atCenterOf(fixture.covered())))
        throw new IllegalStateException("Blast changed immutable BSP");
    } finally {
      level.setBlockAndUpdate(fixture.exposed(), beforeExposed);
      level.setBlockAndUpdate(fixture.covered(), beforeCovered);
    }
    return "PASS explosions entity-cover=true exposed-damage=true knockback=true block-cover=true"
        + " exposed-block=true native-baseline=true immutable-bsp=true health="
        + coveredHealth
        + "/"
        + exposedHealth;
  }

  private static long unshieldedSeed(ServerLevel level, Fixture fixture) {
    // Translate identical native block geometry into an unused QA region without any BSP.
    var front = fixture.exposed().offset(8192, 0, 0);
    var back = fixture.covered().offset(8192, 0, 0);
    var center = fixture.center().add(8192, 0, 0);
    if (BuildingWorlds.at(level, center.x) != null)
      throw new IllegalStateException("Counterfactual QA region already owns geometry");
    var previousFront = level.getBlockState(front);
    var previousBack = level.getBlockState(back);
    if (!previousFront.isAir() || !previousBack.isAir())
      throw new IllegalStateException("Counterfactual QA cells are occupied");
    try {
      level.setBlockAndUpdate(front, Blocks.GLASS.defaultBlockState());
      level.setBlockAndUpdate(back, Blocks.GLASS.defaultBlockState());
      var method = ServerExplosion.class.getDeclaredMethod("calculateExplodedPositions");
      method.setAccessible(true);
      for (long seed = 0; seed < 32; seed++) {
        level.getRandom().setSeed(seed);
        var blast =
            new ServerExplosion(
                level, null, null, null, center, 4, false, Explosion.BlockInteraction.DESTROY);
        var positions = (List<?>) method.invoke(blast);
        if (positions.contains(front) && positions.contains(back)) {
          System.out.println(
              "CraftQ3 unshielded native glass selection PASS seed="
                  + seed
                  + " range="
                  + center.distanceTo(Vec3.atCenterOf(back)));
          return seed;
        }
      }
      throw new IllegalStateException("Covered glass is outside verified native blast reach");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    } finally {
      level.setBlockAndUpdate(front, previousFront);
      level.setBlockAndUpdate(back, previousBack);
    }
  }

  private static Mob cow(ServerLevel level, BlockPos pos) {
    var cow = Objects.requireNonNull(EntityTypes.COW.create(level, EntitySpawnReason.COMMAND));
    cow.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
    cow.setNoAi(true);
    cow.setNoGravity(true);
    return cow;
  }

  private static Fixture find(ServerLevel level, BuildingSession session) {
    var map = session.map();
    for (var face : map.faces()) {
      if (face.type() != 1 || face.vertexCount() == 0) continue;
      var normal = session.transform().directionToMinecraft(face.normal()).scale(32);
      if (Math.abs(normal.y()) > .001
          || Math.max(Math.abs(normal.x()), Math.abs(normal.z())) < .999) continue;
      var point = new dev.bluevista.craftq3.core.math.Vec3(0, 0, 0);
      for (int i = 0; i < face.vertexCount(); i++)
        point = point.add(map.vertices().get(face.firstVertex() + i).position());
      point = session.transform().toMinecraft(point.scale(1.0 / face.vertexCount()));
      for (double depth : new double[] {1, 2, 3}) {
        var front = point.add(normal.scale(1));
        var back = point.add(normal.scale(-depth));
        var exposed = BlockPos.containing(front.x(), front.y(), front.z());
        var covered = BlockPos.containing(back.x(), back.y(), back.z());
        var center = Vec3.atCenterOf(exposed).add(normal.x() * 1.5, 0, normal.z() * 1.5);
        if (!clear(session, exposed)
            || !clear(session, covered)
            || !level.getBlockState(exposed).isAir()
            || !level.getBlockState(covered).isAir()) continue;
        var hidden = cow(level, covered);
        var visible = cow(level, exposed);
        try {
          if (!BuildingProjectiles.clear(level, hidden.getBoundingBox())
              || !BuildingProjectiles.clear(level, visible.getBoundingBox())) continue;
          if (ServerExplosion.getSeenPercent(center, hidden) != 0
              || ServerExplosion.getSeenPercent(center, visible) != 1) continue;
        } finally {
          hidden.discard();
          visible.discard();
        }
        level.getChunkAt(exposed);
        level.getChunkAt(covered);
        return new Fixture(center, exposed, covered);
      }
    }
    throw new IllegalStateException(
        "No original thin BSP barrier with clear build cells and full cover found");
  }

  private static boolean clear(BuildingSession session, BlockPos p) {
    return session
        .geometry()
        .solidClear(
            new BuildingGeometry.Box(
                new dev.bluevista.craftq3.core.math.Vec3(
                    p.getX() + .001, p.getY() + .001, p.getZ() + .001),
                new dev.bluevista.craftq3.core.math.Vec3(
                    p.getX() + .999, p.getY() + .999, p.getZ() + .999)));
  }
}

package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.render.Blaze3dRenderBackend;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import dev.bluevista.craftq3.render.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.joml.Matrix4f;

/** Native Minecraft controls/inventory/blocks layered over an immutable user-supplied BSP. */
public final class BuildingSession {
  public static final ResourceKey<Level> DIMENSION =
      ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("craftq3", "build"));
  private static volatile BuildingSession active;
  private final Pk3FileSystem files;
  private final RenderScene scene;
  private final MaterialLibrary materials;
  private final BspTraceWorld bsp;
  private final BuildingLighting lighting;
  private final String hash;
  private final String mapPath, sourceGame;
  private final Minecraft client;
  private final net.minecraft.client.server.IntegratedServer host;
  private final UUID playerId;
  private CoordinateTransform transform;
  private BuildingGeometry geometry;
  private BuildingSupport support;
  private BuildingWorlds.Environment environment;
  private int region;
  private ServerLevel previousLevel;
  private net.minecraft.world.phys.Vec3 previousPosition;
  private float previousYaw, previousPitch;
  private GameType previousMode;
  private Abilities.Packed previousAbilities;
  private Blaze3dRenderBackend renderer;
  private Matrix4f projection;
  private volatile boolean ready;
  private CompletableFuture<Void> leaving;

  public BuildingSession(Minecraft client, Pk3FileSystem files, String mapName) throws Exception {
    this.client = client;
    this.files = files;
    host = Objects.requireNonNull(client.getSingleplayerServer());
    playerId = Objects.requireNonNull(client.player).getUUID();
    String path = "maps/" + mapName + (mapName.endsWith(".bsp") ? "" : ".bsp");
    var virtualPath = new dev.bluevista.craftq3.core.fs.VirtualPath(path);
    mapPath = virtualPath.value();
    sourceGame = files.which(virtualPath).orElseThrow().game();
    byte[] bytes = files.read(virtualPath);
    hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var map = BspReader.read(bytes);
    scene = BspSceneBuilder.build(path, map, 8);
    materials = MaterialLibrary.load(files, scene);
    bsp = new BspTraceWorld(map);
    lighting = new BuildingLighting(map);
  }

  public static BuildingSession active() {
    return active;
  }

  public boolean matches(Entity entity) {
    return matches(entity.level());
  }

  public boolean matches(Level level) {
    return ready && client.getSingleplayerServer() == host && level.dimension().equals(DIMENSION);
  }

  dev.bluevista.craftq3.assets.bsp.BspMap map() {
    return scene.bsp();
  }

  public int region() {
    return region;
  }

  public BuildingWorlds.Environment environment() {
    return environment;
  }

  public BuildingSupport support() {
    return support;
  }

  public BuildingGeometry geometry() {
    return geometry;
  }

  BuildingLighting lighting() {
    return lighting;
  }

  public CoordinateTransform transform() {
    return transform;
  }

  public CompletableFuture<Void> enter() {
    if (active != null) throw new IllegalStateException("Leave the current build map first");
    return host.submit(
        () -> {
          var level = host.getLevel(DIMENSION);
          if (level == null)
            throw new IllegalStateException(
                "CraftQ3 build dimension is unavailable; reopen this world with the updated mod");
          var player = Objects.requireNonNull(host.getPlayerList().getPlayer(playerId));
          int slot;
          try {
            var directory = host.getWorldPath(LevelResource.ROOT).resolve("craftq3");
            var saved = BuildManifest.read(directory.resolve("build-worlds.properties")).get(hash);
            double originY =
                saved == null
                    ? BuildingWorldLoader.defaultOrigin(level, scene.bsp())
                    : saved.originY();
            // Validate before allocating a durable index entry for a new map.
            BuildingWorldLoader.transform(level, scene.bsp(), 0, originY);
            slot = BuildSpaceIndex.slot(directory.resolve("build-maps.properties"), hash);
            transform = BuildingWorldLoader.transform(level, scene.bsp(), slot, originY);
            BuildManifest.remember(
                directory.resolve("build-worlds.properties"),
                new BuildManifest.Entry(hash, slot, sourceGame, mapPath, originY));
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          geometry = new BuildingGeometry(bsp, transform);
          support = new BuildingSupport(bsp, transform);
          region = slot;
          environment = new BuildingWorlds.Environment(geometry, support);
          BuildingWorlds.register(level, slot, environment);
          previousLevel = player.level();
          previousPosition = player.position();
          previousYaw = player.getYRot();
          previousPitch = player.getXRot();
          previousMode = player.gameMode.getGameModeForPlayer();
          previousAbilities = player.getAbilities().pack();
          var spawn = transform.toMinecraft(scene.camera().origin().add(new Vec3(0, 0, -26)));
          try {
            BuildReturnState.save(player);
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          active = this;
          ready = true;
          try {
            player.setGameMode(GameType.CREATIVE);
            if (!player.teleportTo(
                level,
                spawn.x(),
                spawn.y(),
                spawn.z(),
                Set.of(),
                -scene.camera().yaw() - 90,
                0,
                true)) throw new IllegalStateException("Could not enter build dimension");
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
          } catch (RuntimeException failure) {
            ready = false;
            active = null;
            restore(player);
            throw failure;
          }
          return null;
        });
  }

  private void restore(ServerPlayer player) {
    player.teleportTo(
        previousLevel,
        previousPosition.x,
        previousPosition.y,
        previousPosition.z,
        Set.of(),
        previousYaw,
        previousPitch,
        true);
    player.setGameMode(previousMode);
    player.getAbilities().apply(previousAbilities);
    player.onUpdateAbilities();
    if (player.level() != previousLevel
        || player.position().distanceTo(previousPosition) > .01
        || player.gameMode.getGameModeForPlayer() != previousMode)
      throw new IllegalStateException("Minecraft return state was not restored");
  }

  public synchronized CompletableFuture<Void> leave() {
    if (leaving != null) return leaving;
    ready = false;
    if (active == this) active = null;
    client.execute(
        () -> {
          if (renderer != null) renderer.close();
          try {
            files.close();
          } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
    leaving =
        host.submit(
            () -> {
              var player = host.getPlayerList().getPlayer(playerId);
              if (player != null) {
                restore(player);
              }
              return null;
            });
    return leaving;
  }

  public void projection(Matrix4f value) {
    projection = new Matrix4f(value);
  }

  public void render(CameraRenderState camera) {
    if (!ready || client.level == null || !client.level.dimension().equals(DIMENSION)) return;
    if (renderer == null) renderer = new Blaze3dRenderBackend(materials);
    var origin = transform.toQuake(new Vec3(camera.pos.x, camera.pos.y, camera.pos.z));
    var anchor = transform.minecraftOrigin();
    // Q3 (x,y,z) -> Minecraft (x,z,-y), then translate relative to the exact Minecraft camera.
    var mapping =
        new Matrix4f()
            .translation(
                (float) (anchor.x() - camera.pos.x),
                (float) (anchor.y() - camera.pos.y),
                (float) (anchor.z() - camera.pos.z))
            .rotateX((float) -Math.PI / 2)
            .scale(1f / 32);
    var matrix =
        new Matrix4f(projection == null ? camera.projectionMatrix : projection)
            .mul(camera.viewRotationMatrix)
            .mul(mapping);
    float horizontal = (float) Math.toDegrees(2 * Math.atan(1 / camera.projectionMatrix.m00()));
    var view =
        new RenderScene.Camera(
            origin, -camera.yRot - 90, camera.xRot, Math.clamp(horizontal, 2, 178));
    renderer.renderMinecraftWorld(
        scene.withCamera(view),
        matrix,
        client.getWindow().getWidth(),
        client.getWindow().getHeight());
  }
}

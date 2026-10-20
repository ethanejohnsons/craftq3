package dev.bluevista.craftq3.mc.fabric.building;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import dev.bluevista.craftq3.mc.fabric.net.BuildingRegionsPayload;
import dev.bluevista.craftq3.mc.fabric.server.Q3Content;
import dev.bluevista.craftq3.mc.fabric.server.Q3ServerContent;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegion;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegionCodec;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Building mode on one server: {@code /q3 build <map>} places the map's BSP in its region of the
 * building dimension (reusing the region of an identical BSP) and takes the player there; {@code
 * /q3 leave} takes it back. Regions are saved as a persistent attachment of the building level.
 *
 * <p>Loading never throws: when the level loads, saved regions are loaded on a virtual thread
 * against the configured installation; a region whose PK3 is missing or changed comes back disabled
 * with the reason (its blocks stay saved; its BSP rules are off), and players entering the
 * dimension are told. The server waits (bounded) for the load before players join, so BSP rules are
 * active from the first tick they matter.
 */
public final class BuildingService implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(BuildingService.class);

  /** How long server start waits for saved regions to load. */
  public static final long LOAD_WAIT_SECONDS = 60;

  /** Base64 {@link BuildingRegionCodec} record of the level's regions. */
  public static final AttachmentType<String> REGIONS =
      AttachmentRegistry.createPersistent(
          Identifier.fromNamespaceAndPath("craftq3", "building_regions"), Codec.STRING);

  /** Where a player was before entering the building dimension. */
  public record ReturnPoint(
      String dimension, double x, double y, double z, float yaw, float pitch) {
    public static final Codec<ReturnPoint> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.STRING.fieldOf("dimension").forGetter(ReturnPoint::dimension),
                        Codec.DOUBLE.fieldOf("x").forGetter(ReturnPoint::x),
                        Codec.DOUBLE.fieldOf("y").forGetter(ReturnPoint::y),
                        Codec.DOUBLE.fieldOf("z").forGetter(ReturnPoint::z),
                        Codec.FLOAT.fieldOf("yaw").forGetter(ReturnPoint::yaw),
                        Codec.FLOAT.fieldOf("pitch").forGetter(ReturnPoint::pitch))
                    .apply(i, ReturnPoint::new));
  }

  public static final AttachmentType<ReturnPoint> RETURN_POINT =
      AttachmentRegistry.createPersistent(
          Identifier.fromNamespaceAndPath("craftq3", "building_return"), ReturnPoint.CODEC);

  private final MinecraftServer server;
  private final Q3ServerContent content;
  private CompletableFuture<?> loading = CompletableFuture.completedFuture(null);

  public BuildingService(MinecraftServer server, Q3ServerContent content) {
    this.server = Objects.requireNonNull(server);
    this.content = Objects.requireNonNull(content);
  }

  /** Forces class initialisation (attachment registration) from the mod initializer. */
  public static void register() {}

  // ---- saved regions --------------------------------------------------------------------------

  /** The regions saved in {@code level}; a malformed record reads as none (and is kept). */
  public static List<BuildingRegion> saved(ServerLevel level) {
    String encoded = level.getAttached(REGIONS);
    if (encoded == null || encoded.isEmpty()) return List.of();
    try {
      return BuildingRegionCodec.decode(Base64.getDecoder().decode(encoded));
    } catch (IOException | IllegalArgumentException malformed) {
      LOGGER.error(
          "CraftQ3: the building regions of {} are unreadable", level.dimension(), malformed);
      return List.of();
    }
  }

  public static void save(ServerLevel level, List<BuildingRegion> regions) {
    level.setAttached(
        REGIONS, Base64.getEncoder().encodeToString(BuildingRegionCodec.encode(regions)));
  }

  /**
   * The building level loaded: its saved regions start loading (disabled until they have). Never
   * throws.
   */
  public void levelLoaded(ServerLevel level) {
    if (BuildingDimension.is(level)) loadRegions(level);
  }

  /**
   * Loads the regions saved in {@code level} (any level; normally the building dimension): they are
   * disabled until loaded, then enabled or disabled with their reason. Never throws.
   */
  public void loadRegions(ServerLevel level) {
    List<BuildingRegion> regions = saved(level);
    CraftQ3Level state = CraftQ3Level.of(level);
    List<BuildingRegions.Status> pending = new ArrayList<>();
    for (BuildingRegion r : regions)
      pending.add(new BuildingRegions.Disabled(r, "loading " + r.map()));
    state.craftq3$setBuilding(new LevelBuilding(pending));
    if (regions.isEmpty()) return;
    loading =
        content
            .content()
            .handle(
                (mounted, error) -> {
                  BuildingRegions.MapFiles files =
                      mounted == null ? (game, map) -> Optional.empty() : files(mounted);
                  String reason =
                      mounted == null
                          ? "no usable Quake III installation ("
                              + (error.getCause() != null ? error.getCause() : error).getMessage()
                              + ")"
                          : null;
                  List<BuildingRegions.Status> loaded = new ArrayList<>();
                  for (BuildingRegion r : regions) {
                    BuildingRegions.Status s =
                        reason != null
                            ? new BuildingRegions.Disabled(r, reason)
                            : BuildingRegions.load(r, files);
                    loaded.add(s);
                    if (s instanceof BuildingRegions.Disabled d)
                      LOGGER.warn("CraftQ3: building region {} disabled: {}", r.slot(), d.reason());
                  }
                  return loaded;
                })
            .thenAcceptAsync(
                loaded -> {
                  state.craftq3$setBuilding(new LevelBuilding(loaded));
                  syncAll(level);
                },
                server)
            .exceptionally(
                failure -> {
                  LOGGER.error("CraftQ3: loading building regions failed", failure);
                  return null;
                });
  }

  /** Waits (bounded) for saved regions to load; server thread, before players join. */
  public void awaitLoad() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOAD_WAIT_SECONDS);
    // The level update itself is a task for this (the server) thread; run tasks while waiting.
    server.managedBlock(() -> loading.isDone() || System.nanoTime() > deadline);
    if (!loading.isDone()) LOGGER.warn("CraftQ3: building regions are still loading");
  }

  private static BuildingRegions.MapFiles files(Q3Content mounted) {
    return (game, map) -> {
      if (!game.equalsIgnoreCase(mounted.game())) return Optional.empty();
      return mounted.read(map);
    };
  }

  // ---- entering and leaving -------------------------------------------------------------------

  /** {@code /q3 build <map>}: places the map and takes the player to it. */
  public void build(ServerPlayer player, String mapName, Consumer<Component> feedback) {
    if (BridgedPlayer.of(player) != null) {
      feedback.accept(Component.literal("CraftQ3: leave the bridge first (/q3 leave)"));
      return;
    }
    String name = mapName.trim().toLowerCase(Locale.ROOT);
    if (name.startsWith("maps/")) name = name.substring(5);
    if (name.endsWith(".bsp")) name = name.substring(0, name.length() - 4);
    String path = "maps/" + name + ".bsp";
    feedback.accept(Component.literal("CraftQ3: preparing " + name + "..."));
    content
        .content()
        .thenApplyAsync(
            mounted -> {
              try {
                Optional<byte[]> bytes = mounted.read(path);
                if (bytes.isEmpty()) throw new IOException("There is no " + path);
                return new Object[] {mounted, bytes.get()};
              } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
              }
            },
            command -> Thread.ofVirtual().name("craftq3-build-read").start(command))
        .whenCompleteAsync(
            (read, error) -> {
              if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                feedback.accept(Component.literal("CraftQ3: " + cause.getMessage()));
                return;
              }
              place(player, (Q3Content) read[0], path, (byte[]) read[1], feedback);
            },
            server);
  }

  private void place(
      ServerPlayer player,
      Q3Content mounted,
      String path,
      byte[] bytes,
      Consumer<Component> feedback) {
    ServerLevel level = server.getLevel(BuildingDimension.KEY);
    if (level == null) {
      feedback.accept(Component.literal("CraftQ3: the building dimension is not available"));
      return;
    }
    BuildingRegions.Status status;
    try {
      status = placeNow(level, mounted.game(), path, bytes);
    } catch (IllegalArgumentException refused) {
      feedback.accept(Component.literal("CraftQ3: " + refused.getMessage()));
      return;
    }
    BuildingRegion region = status.region();
    if (status instanceof BuildingRegions.Disabled d) {
      feedback.accept(
          Component.literal("CraftQ3: " + region.map() + " is disabled: " + d.reason()));
      return;
    }
    BuildingRegions.Enabled e = (BuildingRegions.Enabled) status;
    Vec3 spawn = spawnPoint(e);
    if (!BuildingDimension.is(player.level()) && player.getAttached(RETURN_POINT) == null)
      player.setAttached(
          RETURN_POINT,
          new ReturnPoint(
              player.level().dimension().identifier().toString(),
              player.getX(),
              player.getY(),
              player.getZ(),
              player.getYRot(),
              player.getXRot()));
    player.teleport(
        new TeleportTransition(
            level, spawn, Vec3.ZERO, player.getYRot(), 0, TeleportTransition.DO_NOTHING));
    sync(player);
    feedback.accept(
        Component.literal(
            "CraftQ3: building in "
                + region.mapName()
                + " (region "
                + region.slot()
                + "); /q3 leave returns"));
  }

  /**
   * Places {@code bytes} (the BSP at {@code path} of {@code game}) in the building {@code level}:
   * an identical BSP reuses its region, a new one takes the next free slot and is saved; the
   * region's queries are built and become active. Server thread.
   *
   * @throws IllegalArgumentException when the map is malformed or does not fit a region
   */
  public BuildingRegions.Status placeNow(
      ServerLevel level, String game, String path, byte[] bytes) {
    List<BuildingRegion> regions = new ArrayList<>(saved(level));
    BuildingRegion region =
        BuildingRegions.place(regions, game, path, bytes, BuildingDimension.LAYOUT);
    if (!regions.contains(region)) {
      regions.add(region);
      save(level, regions);
    }
    CraftQ3Level state = CraftQ3Level.of(level);
    Optional<BuildingRegions.Enabled> enabled = state.craftq3$building().enabled(region.slot());
    BuildingRegions.Status status =
        enabled.isPresent()
            ? enabled.get()
            : BuildingRegions.load(region, (g, m) -> Optional.of(bytes));
    state.craftq3$setBuilding(state.craftq3$building().with(status));
    syncAll(level);
    return status;
  }

  /** The Minecraft position of the map's first deathmatch spawn (else its centre). */
  public static Vec3 spawnPoint(BuildingRegions.Enabled region) {
    CoordinateTransform t = region.region().transform();
    for (var entity : region.map().entities()) {
      String classname = entity.classname().orElse("");
      if (!classname.equals("info_player_deathmatch") && !classname.equals("info_player_start"))
        continue;
      String[] parts = entity.get("origin").orElse("").trim().split("\\s+");
      if (parts.length != 3) continue;
      try {
        float x = Float.parseFloat(parts[0]), y = Float.parseFloat(parts[1]);
        float z = Float.parseFloat(parts[2]);
        return new Vec3(t.minecraftX(x), t.minecraftY(z - 23), t.minecraftZ(y));
      } catch (NumberFormatException skip) {
        // Try the next spawn.
      }
    }
    var model = region.map().models().getFirst();
    return new Vec3(
        t.minecraftX((model.mins().x() + model.maxs().x()) / 2),
        t.minecraftY(model.maxs().z()),
        t.minecraftZ((model.mins().y() + model.maxs().y()) / 2));
  }

  /** {@code /q3 leave} in the building dimension: back to where the player came from. */
  public boolean leave(ServerPlayer player, Consumer<Component> feedback) {
    if (!BuildingDimension.is(player.level())) return false;
    ReturnPoint back = player.getAttached(RETURN_POINT);
    ServerLevel target = null;
    Vec3 pos = null;
    float yaw = player.getYRot(), pitch = player.getXRot();
    if (back != null) {
      Identifier id = Identifier.tryParse(back.dimension());
      if (id != null)
        target =
            server.getLevel(
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id));
      pos = new Vec3(back.x(), back.y(), back.z());
      yaw = back.yaw();
      pitch = back.pitch();
    }
    if (target == null) {
      target = server.overworld();
      pos = Vec3.atBottomCenterOf(target.getRespawnData().globalPos().pos());
    }
    player.removeAttached(RETURN_POINT);
    player.teleport(
        new TeleportTransition(target, pos, Vec3.ZERO, yaw, pitch, TeleportTransition.DO_NOTHING));
    feedback.accept(Component.literal("CraftQ3: left the building dimension"));
    return true;
  }

  // ---- client sync ----------------------------------------------------------------------------

  /** Tells {@code player} the regions of the level it is in, and warns about disabled ones. */
  public void sync(ServerPlayer player) {
    if (!ServerPlayNetworking.canSend(player, BuildingRegionsPayload.TYPE)) return;
    Level level = player.level();
    LevelBuilding building = CraftQ3Level.of(level).craftq3$building();
    List<BuildingRegion> enabled = new ArrayList<>();
    for (BuildingRegions.Status s : building.statuses()) {
      if (s instanceof BuildingRegions.Enabled) enabled.add(s.region());
      else if (s instanceof BuildingRegions.Disabled d && !d.reason().startsWith("loading"))
        player.sendSystemMessage(
            Component.literal(
                "CraftQ3: building region "
                    + d.region().slot()
                    + " ("
                    + d.region().mapName()
                    + ") is disabled: "
                    + d.reason()));
    }
    ServerPlayNetworking.send(
        player, new BuildingRegionsPayload(BuildingRegionCodec.encode(enabled)));
  }

  private void syncAll(ServerLevel level) {
    for (ServerPlayer p : level.players()) sync(p);
  }

  @Override
  public void close() {
    loading.cancel(false);
  }
}

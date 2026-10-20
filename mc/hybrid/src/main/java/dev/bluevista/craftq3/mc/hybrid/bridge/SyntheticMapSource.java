package dev.bluevista.craftq3.mc.hybrid.bridge;

import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.map.MapSource;
import dev.bluevista.craftq3.mc.hybrid.map.EntityText;
import dev.bluevista.craftq3.mc.hybrid.map.SyntheticBsp;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The bridge's map: a generated BSP-equivalent with no geometry of its own (Minecraft terrain joins
 * world traces as a {@link CollisionSource}), a worldspawn, one spawn point at the player, the
 * {@link HostTriggers}, and the original item entities of the {@link PickupPlacement}s. There is no
 * AAS, so bots cannot play here.
 *
 * <p>{@link #prepare} sets the world for the next {@code map craftq3_bridge}; the server calls
 * {@link #load} on its simulation thread.
 */
public final class SyntheticMapSource implements MapSource {
  /** The map name the server spawns. */
  public static final String MAP_NAME = "craftq3_bridge";

  /** The host triggers use inline models {@code *1 ..}. */
  public static final int FIRST_HOST_MODEL = 1;

  /** Where parked entities wait: deep below every terrain and every map. */
  public static final float PARK_Z = -60000;

  /** {@code info_player_deathmatch} spawnflag INITIAL: a local player's first spawn. */
  public static final int SPAWN_INITIAL = 1;

  /**
   * An item to spawn.
   *
   * @param origin its Quake origin (the game drops it to the floor below)
   */
  public record ItemSpawn(String classname, Vec3f origin) {
    public ItemSpawn {
      if (!PickupPlacement.isItemClassname(classname))
        throw new IllegalArgumentException("Not an item classname: " + classname);
      Objects.requireNonNull(origin, "origin");
    }
  }

  /**
   * What the next bridge level contains.
   *
   * @param terrain Minecraft terrain in Quake coordinates (usually a {@code GridCollisionSource})
   * @param spawn the player's spawn point (feet height plus the game's spawn offset is fine)
   * @param yaw the spawn's facing in Quake degrees
   */
  public record World(
      CollisionSource terrain, Vec3f spawn, float yaw, List<ItemSpawn> items, String message) {
    public World {
      Objects.requireNonNull(terrain, "terrain");
      Objects.requireNonNull(spawn, "spawn");
      items = List.copyOf(items);
      if (items.size() > PickupPlacement.MAX_PLACEMENTS)
        throw new IllegalArgumentException("Too many items");
      if (!Float.isFinite(yaw) || !spawn.isFinite())
        throw new IllegalArgumentException("Non-finite spawn");
      for (int axis = 0; axis < 3; axis++)
        if (Math.abs(spawn.get(axis)) > SyntheticBsp.WORLD_EXTENT / 2)
          throw new IllegalArgumentException("Spawn outside the bridge world");
      message = message == null ? "Minecraft bridge" : message;
    }
  }

  private volatile World world;

  /** Sets what the next load builds. */
  public void prepare(World next) {
    world = Objects.requireNonNull(next);
  }

  public Optional<World> world() {
    return Optional.ofNullable(world);
  }

  @Override
  public boolean exists(String name) {
    return world != null && MAP_NAME.equals(normalize(name));
  }

  @Override
  public GameMap load(String name) throws IOException {
    World w = world;
    if (w == null || !MAP_NAME.equals(normalize(name))) throw new NoSuchFileException(name);
    BspMap bsp = build(w).build("maps/" + MAP_NAME + ".bsp");
    return new GameMap(
        MAP_NAME, bsp, bsp.entityText(), bsp.checksum(), Optional.empty(), List.of(w.terrain()));
  }

  /** The map for a world (exposed for tests and tools). */
  public static SyntheticBsp build(World w) {
    SyntheticBsp bsp = new SyntheticBsp();
    bsp.entity(BspEntity.of("classname", "worldspawn", "message", sanitize(w.message())));
    bsp.entity(
        BspEntity.of(
            "classname",
            "info_player_deathmatch",
            "origin",
            // Whole units, so the game parses exactly the origin the host later looks for.
            EntityText.vector(
                Math.round(w.spawn().x()), Math.round(w.spawn().y()), Math.round(w.spawn().z())),
            "angle",
            EntityText.number(w.yaw()),
            "spawnflags",
            Integer.toString(SPAWN_INITIAL)));
    int first = -1;
    for (int i = 0; i < HostTriggers.MODELS; i++) {
      int model =
          bsp.boxModel(
              HostTriggers.mins(),
              HostTriggers.maxs(),
              HostTriggers.SHADER,
              HostTriggers.contents());
      if (first < 0) first = model;
    }
    if (first != FIRST_HOST_MODEL) throw new AssertionError("Host triggers must start at *1");
    bsp.entities(HostTriggers.entities(first, park()));
    List<BspEntity> items = new ArrayList<>(w.items().size());
    for (ItemSpawn item : w.items())
      items.add(
          BspEntity.of(
              "classname",
              item.classname(),
              "origin",
              EntityText.vector(item.origin().x(), item.origin().y(), item.origin().z())));
    bsp.entities(items);
    return bsp;
  }

  /** The parking position of host triggers. */
  public static float[] park() {
    return new float[] {0, 0, PARK_Z};
  }

  private static String normalize(String name) {
    String n = name.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (n.startsWith("maps/")) n = n.substring(5);
    if (n.endsWith(".bsp")) n = n.substring(0, n.length() - 4);
    return n;
  }

  private static String sanitize(String text) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < text.length() && out.length() < 200; i++) {
      char c = text.charAt(i);
      out.append(c == '"' || c < 0x20 ? ' ' : c);
    }
    return out.toString();
  }
}

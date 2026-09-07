package dev.bluevista.craftq3.server;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host terrain replaces BSP collision while original QVMs retain entities and gameplay rules. */
public record ExternalWorld(BspMap metadata, TraceWorld collision) {
  public static final int MAX_DAMAGE = 255, MAX_PICKUPS = 256;

  public ExternalWorld {
    Objects.requireNonNull(metadata);
    Objects.requireNonNull(collision);
    if (metadata.models().isEmpty() || !metadata.brushes().isEmpty() || !metadata.faces().isEmpty())
      throw new IllegalArgumentException(
          "External terrain requires a geometry-free world descriptor");
  }

  public static ExternalWorld at(TraceWorld collision, Vec3 spawn, float yaw) {
    return descriptor(collision, spawn, yaw, false, List.of());
  }

  /**
   * Host damage is delivered through original map-authored hurt entities, not private game fields.
   */
  public static ExternalWorld combat(TraceWorld collision, Vec3 spawn, float yaw) {
    return combat(collision, spawn, yaw, List.of());
  }

  public static ExternalWorld combat(
      TraceWorld collision, Vec3 spawn, float yaw, List<ExternalPickup> pickups) {
    if (pickups.size() > MAX_PICKUPS)
      throw new IllegalArgumentException("Too many external pickups");
    return descriptor(collision, spawn, yaw, true, List.copyOf(pickups));
  }

  private static ExternalWorld descriptor(
      TraceWorld collision, Vec3 spawn, float yaw, boolean damage, List<ExternalPickup> pickups) {
    if (pickups.size() > MAX_PICKUPS)
      throw new IllegalArgumentException("Too many external pickups");
    if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Invalid spawn yaw");
    var bounds =
        new BspMap.Bounds(
            new Vec3(-1_000_000, -1_000_000, -1_000_000),
            new Vec3(1_000_000, 1_000_000, 1_000_000));
    var entities =
        List.of(
            Map.of("classname", "worldspawn", "message", "Minecraft / Quake bridge"),
            Map.of(
                "classname",
                "info_player_deathmatch",
                "origin",
                spawn.x() + " " + spawn.y() + " " + spawn.z(),
                "angle",
                Float.toString(yaw)));
    var entityList = new java.util.ArrayList<>(entities);
    var models = new java.util.ArrayList<BspMap.Model>();
    models.add(new BspMap.Model(bounds, 0, 0, 0, 0));
    if (damage)
      for (int amount = 1; amount <= MAX_DAMAGE; amount++) {
        models.add(
            new BspMap.Model(
                new BspMap.Bounds(new Vec3(-1, -1, -1), new Vec3(1, 1, 1)), 0, 0, 0, 0));
        entityList.add(
            Map.of(
                "classname",
                "trigger_hurt",
                "model",
                "*" + amount,
                "dmg",
                Integer.toString(amount),
                "spawnflags",
                "4",
                "craftq3_damage",
                Integer.toString(amount)));
      }
    for (var pickup : pickups) entityList.add(pickup.entity());
    return new ExternalWorld(
        new BspMap(
            entityList,
            List.of(),
            List.of(),
            List.of(),
            List.of(new BspMap.Leaf(-1, 0, bounds, 0, 0, 0, 0)),
            List.of(),
            List.of(),
            models,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0]))),
        collision);
  }
}

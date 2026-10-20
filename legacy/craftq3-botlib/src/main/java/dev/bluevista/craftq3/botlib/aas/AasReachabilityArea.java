package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/** Original bot goal area localization, with borrowed collision and live entity metadata. */
public final class AasReachabilityArea {
  private static final Vec3 CROUCH_MIN = new Vec3(-15, -15, -24);
  private static final Vec3 CROUCH_MAX = new Vec3(15, 15, 8);

  /** Only the live brush model index participates in this service's entity selection. */
  public record Entity(int modelIndex) {
    public Entity {
      if (modelIndex < 0 || modelIndex >= 256)
        throw new IllegalArgumentException("Bot entity model index outside 0..255");
    }
  }

  private final AasNavigation navigation;
  private final AasMap map;
  private final TraceWorld world;
  private final IntFunction<Optional<Entity>> entities;
  private final Map<Integer, Integer> moverAreas;
  private final AasPresenceTrace presence;
  private final int maxNodeVisits;

  public AasReachabilityArea(
      AasNavigation navigation,
      List<Map<String, String>> bspEntities,
      TraceWorld world,
      IntFunction<Optional<Entity>> entities) {
    this(navigation, bspEntities, world, entities, 1_000_000);
  }

  /** Each raw area/presence trace has this limit; a query makes at most 56 raw area traces. */
  public AasReachabilityArea(
      AasNavigation navigation,
      List<Map<String, String>> bspEntities,
      TraceWorld world,
      IntFunction<Optional<Entity>> entities,
      int maxNodeVisits) {
    this.navigation = Objects.requireNonNull(navigation);
    map = navigation.map();
    this.world = Objects.requireNonNull(world);
    this.entities = Objects.requireNonNull(entities);
    if (maxNodeVisits < 1 || maxNodeVisits > 2_000_000)
      throw new IllegalArgumentException("AAS reachable-area work budget out of range");
    this.maxNodeVisits = maxNodeVisits;
    presence = new AasPresenceTrace(map, maxNodeVisits);
    moverAreas = moverAreas(Objects.requireNonNull(bspEntities), map);
  }

  public int reachableArea(Vec3 origin, int client) {
    origin = point(origin);
    if (client < -1 || client > 1023)
      throw new IllegalArgumentException("Bot client outside -1..1023");
    var ground =
        world.trace(
            new TraceRequest(
                origin, offset(origin, 0, 0, -3), CROUCH_MIN, CROUCH_MAX, 65537, client));
    int entity = ground.hit().map(hit -> hit.entity()).orElse(1023);
    boolean onEntity =
        !ground.startSolid() && (float) ground.fraction() < 1 && entity >= 0 && entity < 1022;
    if (onEntity) {
      var state = Objects.requireNonNull(entities.apply(entity));
      int model = state.map(Entity::modelIndex).orElse(0);
      Integer moverArea = moverAreas.get(model);
      if (moverArea != null) return moverArea;
    }
    int area = fuzzyArea(origin);
    if (!onEntity || hasReachability(area)) return area;
    // The native fallback disables dynamic entity tracing (pass entity -1) and uses AAS's
    // pre-expanded crouching geometry, independently of the imported three-unit ground trace.
    var below = presence.trace(origin, offset(origin, 0, 0, -800), 4);
    return fuzzyArea(below.endPosition());
  }

  /** Nearby area recovery used by the original bot goal selector; not item drop localization. */
  public int fuzzyArea(Vec3 origin) {
    origin = point(origin);
    int fallback = navigation.pointArea(origin);
    if (hasReachability(fallback)) return fallback;
    for (var entry : trace(origin, offset(origin, 0, 0, 4)))
      if (hasReachability(entry.area())) return entry.area();
    for (int z : new int[] {12, 0, -12}) {
      int best = 0;
      float bestDistance = Float.POSITIVE_INFINITY;
      for (int x : new int[] {8, 0, -8}) {
        for (int y : new int[] {8, 0, -8}) {
          for (var entry : trace(origin, offset(origin, x, y, z))) {
            if (fallback == 0) fallback = entry.area();
            if (!hasReachability(entry.area())) continue;
            float distance = distanceSquared(origin, entry.point());
            if (distance < bestDistance) {
              best = entry.area();
              bestDistance = distance;
            }
          }
        }
      }
      if (best != 0) return best;
    }
    return fallback;
  }

  private List<AasAreaTrace.Entry> trace(Vec3 start, Vec3 end) {
    return AasAreaTrace.trace(map, start, end, 10, maxNodeVisits);
  }

  private boolean hasReachability(int area) {
    return area > 0 && map.areaSettings().get(area).reachabilityCount() > 0;
  }

  private static Map<Integer, Integer> moverAreas(List<Map<String, String>> entities, AasMap map) {
    if (entities.size() > 65_536) throw new IllegalArgumentException("BSP entity budget exceeded");
    var movers = new HashMap<Integer, Boolean>();
    for (var entity : entities) {
      String model = entity.getOrDefault("model", "");
      if (!model.startsWith("*")) continue;
      int index;
      try {
        index = Integer.parseInt(model.substring(1));
      } catch (NumberFormatException failure) {
        throw new IllegalArgumentException("Invalid BSP brush model " + model, failure);
      }
      if (index < 0 || index >= 256)
        throw new IllegalArgumentException("BSP brush model outside 0..255");
      String classname = entity.getOrDefault("classname", "");
      movers.put(
          index,
          classname.equalsIgnoreCase("func_plat") || classname.equalsIgnoreCase("func_bobbing"));
    }
    var result = new HashMap<Integer, Integer>();
    for (int i = 1; i < map.reachabilities().size(); i++) {
      var reach = map.reachabilities().get(i);
      int model =
          switch (reach.baseTravelType()) {
            case 11 -> reach.face();
            case 19 -> reach.face() & 0xffff;
            default -> -1;
          };
      if (movers.getOrDefault(model, false)) result.putIfAbsent(model, reach.area());
    }
    return Map.copyOf(result);
  }

  private static float distanceSquared(Vec3 a, Vec3 b) {
    float x = (float) a.x() - (float) b.x();
    float y = (float) a.y() - (float) b.y();
    float z = (float) a.z() - (float) b.z();
    return x * x + y * y + z * z;
  }

  private static Vec3 point(Vec3 value) {
    Objects.requireNonNull(value);
    if (Math.abs(value.x()) > 1e9 || Math.abs(value.y()) > 1e9 || Math.abs(value.z()) > 1e9)
      throw new IllegalArgumentException("Bot reachable-area coordinates exceed limit");
    return new Vec3((float) value.x(), (float) value.y(), (float) value.z());
  }

  private static Vec3 offset(Vec3 value, float x, float y, float z) {
    return point(new Vec3((float) value.x() + x, (float) value.y() + y, (float) value.z() + z));
  }
}

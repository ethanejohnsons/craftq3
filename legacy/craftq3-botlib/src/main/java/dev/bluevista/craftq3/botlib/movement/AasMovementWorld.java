package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.botlib.aas.AasGoalLocator;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** AAS static presence hulls composed with individually linked engine entities. */
public final class AasMovementWorld implements AasMovementPredictor.World {
  @FunctionalInterface
  public interface EntityTrace {
    TraceResult trace(int entity, TraceRequest request);
  }

  private static final Vec3 MINS = new Vec3(-15, -15, -24);
  private static final int CONTENTS_MASK = 65537;
  private static final int MAX_LINKS = 2_000_000, MAX_ENTITY_QUERIES = 1_000_000;
  private final AasNavigation navigation;
  private final AasGoalLocator locator;
  private final AasPresenceTrace geometry;
  private final EntityTrace entityTrace;
  private final ToIntFunction<Vec3> contents;
  private final Map<Integer, List<Integer>> entityAreas = new HashMap<>();
  private final Map<Integer, ArrayList<Integer>> areaEntities = new HashMap<>();
  private int links;

  public AasMovementWorld(
      AasNavigation navigation, EntityTrace entityTrace, ToIntFunction<Vec3> contents) {
    this.navigation = Objects.requireNonNull(navigation);
    this.entityTrace = Objects.requireNonNull(entityTrace);
    this.contents = Objects.requireNonNull(contents);
    locator = new AasGoalLocator(navigation);
    geometry = new AasPresenceTrace(navigation.map());
  }

  /** Bounds are absolute botlib bounds. Every update moves the entity to each area's front. */
  public void link(int entity, Vec3 absMin, Vec3 absMax) {
    entity(entity);
    List<Integer> next = locator.linkedAreas(absMin, absMax, 2);
    int previous = entityAreas.getOrDefault(entity, List.of()).size();
    if ((long) links - previous + next.size() > MAX_LINKS)
      throw new IllegalStateException("AAS entity-area link budget exceeded");
    unlink(entity);
    entityAreas.put(entity, next);
    for (int area : next)
      areaEntities.computeIfAbsent(area, ignored -> new ArrayList<>()).addFirst(entity);
    links += next.size();
  }

  /** Invalidated frame visibility does not unlink; an explicit null update does. */
  public void unlink(int entity) {
    entity(entity);
    var previous = entityAreas.remove(entity);
    if (previous == null) return;
    for (int area : previous) {
      var members = areaEntities.get(area);
      members.remove(Integer.valueOf(entity));
      if (members.isEmpty()) areaEntities.remove(area);
    }
    links -= previous.size();
  }

  public void clear() {
    entityAreas.clear();
    areaEntities.clear();
    links = 0;
  }

  public int linkCount() {
    return links;
  }

  @Override
  public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int passEntity) {
    if (passEntity < 0) return geometry.trace(start, end, presence);
    entity(passEntity);
    int[] queries = {0};
    return geometry.trace(
        start,
        end,
        presence,
        (area, from, to, mask) -> {
          var members = areaEntities.get(area);
          if (members == null) return Optional.empty();
          var request =
              new TraceRequest(
                  from, to, MINS, new Vec3(15, 15, mask == 2 ? 32 : 8), CONTENTS_MASK, passEntity);
          AasPresenceTrace.EntityHit best = null;
          float fraction = 1;
          for (int number : members) {
            if (number == passEntity) continue;
            if (++queries[0] > MAX_ENTITY_QUERIES)
              throw new IllegalStateException("AAS entity trace work budget exceeded");
            var result = Objects.requireNonNull(entityTrace.trace(number, request));
            float value = (float) result.fraction();
            if (value < fraction) {
              fraction = value;
              best =
                  new AasPresenceTrace.EntityHit(
                      result.startSolid(), value, result.endPosition(), number);
            }
          }
          return Optional.ofNullable(best);
        });
  }

  @Override
  public Vec3 planeNormal(int plane) {
    if (plane < 0 || plane >= navigation.map().planes().size())
      throw new IllegalArgumentException("Invalid AAS movement plane");
    return navigation.map().planes().get(plane).normal();
  }

  @Override
  public int area(Vec3 point) {
    return navigation.pointArea(point);
  }

  @Override
  public int presence(Vec3 point) {
    int area = navigation.pointArea(point);
    return area == 0 ? 0 : navigation.map().areaSettings().get(area).presenceType();
  }

  @Override
  public int areaContents(int area) {
    if (area < 0 || area >= navigation.map().areaSettings().size())
      throw new IllegalArgumentException("Invalid AAS movement area");
    return area == 0 ? 0 : navigation.map().areaSettings().get(area).contents();
  }

  @Override
  public int contents(Vec3 point) {
    return contents.applyAsInt(point);
  }

  private static void entity(int entity) {
    if (entity < 0 || entity >= 1024)
      throw new IllegalArgumentException("Invalid AAS entity number");
  }
}

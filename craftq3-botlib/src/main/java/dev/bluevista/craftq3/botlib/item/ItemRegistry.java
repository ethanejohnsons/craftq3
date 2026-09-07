package dev.bluevista.craftq3.botlib.item;

import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * Map-authored bot item discovery; collision/AAS goal placement is supplied by the world adapter.
 */
public final class ItemRegistry {
  private static final java.util.regex.Pattern NUMBER_PREFIX =
      java.util.regex.Pattern.compile(
          "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
  public static final int MAX_LEVEL_ITEMS = 256;
  public static final int NOT_FREE = 1, NOT_TEAM = 2, NOT_SINGLE = 4, NOT_BOT = 8, ROAM = 16;

  public record Placement(Vec3 origin, Vec3 goalOrigin, int area) {
    public Placement {
      Objects.requireNonNull(origin);
      Objects.requireNonNull(goalOrigin);
      if (area < 0) throw new IllegalArgumentException("Invalid bot item area");
    }
  }

  @FunctionalInterface
  public interface PlacementResolver {
    /**
     * Empty means the suspended item cannot be reached; area zero retains a known unreachable item.
     */
    Optional<Placement> resolve(ItemInfo item, Vec3 mapOrigin, boolean suspended);
  }

  public interface LivePlacementResolver {
    Placement resolve(ItemInfo item, Vec3 origin);

    /** Suspended goals retain their original launch area while the entity position is updated. */
    default boolean isJumpPadArea(int area) {
      return false;
    }
  }

  public record WorldEntity(
      int number, int type, int flags, int modelIndex, Vec3 origin, Vec3 lastVisibleOrigin) {
    public WorldEntity {
      if (number < 1 || number > 1023)
        throw new IllegalArgumentException("Invalid bot world entity");
      Objects.requireNonNull(origin);
      Objects.requireNonNull(lastVisibleOrigin);
    }
  }

  public record LevelItem(
      int number,
      int bspEntity,
      ItemInfo info,
      int flags,
      float weight,
      Placement placement,
      int entity,
      float timeout) {
    public LevelItem {
      Objects.requireNonNull(info);
      Objects.requireNonNull(placement);
      if (number < 1 || bspEntity < 0 || !Float.isFinite(weight) || !Float.isFinite(timeout))
        throw new IllegalArgumentException("Invalid level item");
    }

    public Goal goal() {
      int goalFlags =
          Goal.ITEM | ((flags & ROAM) != 0 ? Goal.ROAM : 0) | (timeout != 0 ? Goal.DROPPED : 0);
      return new Goal(
          placement.goalOrigin(),
          placement.area(),
          info.mins(),
          info.maxs(),
          entity,
          number,
          goalFlags,
          info.number());
    }
  }

  private final ItemConfig config;
  private final PlacementResolver placement;
  private final Consumer<String> diagnostics;
  private List<LevelItem> items = List.of();
  private int staticCount;

  public ItemRegistry(
      ItemConfig config, PlacementResolver placement, Consumer<String> diagnostics) {
    this.config = Objects.requireNonNull(config);
    this.placement = Objects.requireNonNull(placement);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  /** Replaces a map's registry transactionally, preserving native reverse creation enumeration. */
  public void initialize(List<Map<String, String>> entities) {
    Objects.requireNonNull(entities);
    if (entities.size() > 65536)
      throw new IllegalArgumentException("Bot BSP entity limit exceeded");
    var loaded = new ArrayList<LevelItem>();
    for (int index = 0; index < entities.size(); index++) {
      var entity = entities.get(index);
      Optional<ItemInfo> candidate = config.first(entity.getOrDefault("classname", ""));
      if (candidate.isEmpty()) continue;
      ItemInfo info = candidate.orElseThrow();
      if (!entity.containsKey("origin")) {
        diagnostics.accept("Bot item " + info.classname() + " lacks an origin");
        continue;
      }
      Vec3 origin;
      try {
        origin = vector(entity.get("origin"));
      } catch (IllegalArgumentException failure) {
        diagnostics.accept("Bot item " + info.classname() + " has an invalid origin");
        continue;
      }
      var resolved = placement.resolve(info, origin, (integer(entity, "spawnflags") & 1) != 0);
      if (resolved.isEmpty()) continue;
      if (loaded.size() == MAX_LEVEL_ITEMS)
        throw new IllegalArgumentException("Bot level item limit exceeded");
      int flags =
          (integer(entity, "notfree") != 0 ? NOT_FREE : 0)
              | (integer(entity, "notteam") != 0 ? NOT_TEAM : 0)
              | (integer(entity, "notsingle") != 0 ? NOT_SINGLE : 0)
              | (integer(entity, "notbot") != 0 ? NOT_BOT : 0);
      float weight = 0;
      if (info.classname().equals("item_botroam")) {
        flags |= ROAM;
        var numeric = NUMBER_PREFIX.matcher(entity.getOrDefault("weight", "0").stripLeading());
        if (numeric.find()) weight = Float.parseFloat(numeric.group());
        if (!Float.isFinite(weight))
          throw new IllegalArgumentException("Nonfinite bot roam weight");
      }
      loaded.add(
          new LevelItem(
              loaded.size() + 1, index + 1, info, flags, weight, resolved.orElseThrow(), 0, 0));
    }
    Collections.reverse(loaded);
    items = List.copyOf(loaded);
    staticCount = loaded.size();
  }

  /**
   * Associates stationary item entities and retains temporary drops until their observed 30-second
   * expiry. Missing entities do not erase map items; model replacement invalidates the old link.
   */
  public void update(float time, List<WorldEntity> entities, LivePlacementResolver resolver) {
    if (!Float.isFinite(time) || time < 0 || entities.size() > 1023)
      throw new IllegalArgumentException("Invalid bot item update");
    Objects.requireNonNull(resolver);
    var ordered =
        entities.stream().sorted(java.util.Comparator.comparingInt(WorldEntity::number)).toList();
    for (int i = 1; i < ordered.size(); i++)
      if (ordered.get(i - 1).number() == ordered.get(i).number())
        throw new IllegalArgumentException("Duplicate bot entity update");
    var changed = new ArrayList<>(items);
    changed.removeIf(item -> item.timeout() != 0 && item.timeout() < time);
    for (WorldEntity entity : ordered) {
      if (entity.type() != 2
          || entity.modelIndex() == 0
          || !samePosition(entity.origin(), entity.lastVisibleOrigin())) continue;
      changed.removeIf(
          item ->
              item.entity() == entity.number() && item.info().modelIndex() != entity.modelIndex());
      int found = -1;
      for (int index = 0; index < changed.size(); index++) {
        LevelItem item = changed.get(index);
        if (item.info().modelIndex() != entity.modelIndex()) continue;
        if (item.entity() == entity.number()) {
          found = index;
          break;
        }
      }
      if (found < 0)
        for (int index = 0; index < changed.size(); index++) {
          LevelItem item = changed.get(index);
          if (item.info().modelIndex() == entity.modelIndex()
              && item.entity() == 0
              && distanceSquared(item.placement().origin(), entity.origin()) < 900) {
            found = index;
            break;
          }
        }
      if (found >= 0) {
        LevelItem item = changed.get(found);
        Placement next = item.placement();
        if (!samePosition(next.origin(), entity.origin())) {
          next =
              resolver.isJumpPadArea(next.area())
                  ? new Placement(entity.origin(), next.goalOrigin(), next.area())
                  : resolver.resolve(item.info(), entity.origin());
        }
        changed.set(
            found,
            new LevelItem(
                item.number(),
                item.bspEntity(),
                item.info(),
                item.flags(),
                item.weight(),
                next,
                entity.number(),
                item.timeout()));
      } else {
        var metadata =
            config.items().stream()
                .filter(info -> info.modelIndex() == entity.modelIndex())
                .findFirst();
        if (metadata.isEmpty()) continue;
        if (changed.size() == MAX_LEVEL_ITEMS) {
          diagnostics.accept("Bot level item capacity reached; dropped item omitted");
          continue;
        }
        Placement next = resolver.resolve(metadata.orElseThrow(), entity.origin());
        changed.addFirst(
            new LevelItem(
                staticCount + entity.number(),
                0,
                metadata.orElseThrow(),
                0,
                0,
                next,
                entity.number(),
                time + 30));
      }
    }
    items = List.copyOf(changed);
  }

  public List<LevelItem> items() {
    return items;
  }

  public Optional<LevelItem> byNumber(int number) {
    return items.stream().filter(item -> item.number() == number).findFirst();
  }

  /**
   * The original query uses a display name ("Red Flag"), despite an older header's parameter name.
   */
  public Optional<Goal> nextGoal(int cursor, String displayName, int gameType) {
    int start = 0;
    if (cursor >= 0) {
      while (start < items.size() && items.get(start).number() != cursor) start++;
      if (start == items.size()) return Optional.empty();
      start++;
    }
    for (int index = start; index < items.size(); index++) {
      LevelItem item = items.get(index);
      if (permitted(item, gameType) && item.info().name().equalsIgnoreCase(displayName))
        return Optional.of(item.goal());
    }
    return Optional.empty();
  }

  public static boolean permitted(LevelItem item, int gameType) {
    int excluded = NOT_BOT | (gameType == 2 ? NOT_SINGLE : gameType >= 3 ? NOT_TEAM : NOT_FREE);
    return (item.flags() & excluded) == 0;
  }

  public OptionalDouble automaticAvoidDuration(int number) {
    var item = byNumber(number);
    if (item.isEmpty()) return OptionalDouble.empty();
    float respawn = item.get().info().respawnTime();
    return OptionalDouble.of(respawn == 0 ? 30 : Math.max(10, respawn));
  }

  private static Vec3 vector(String text) {
    String[] fields = text.trim().split("\\s+");
    if (fields.length != 3) throw new IllegalArgumentException("Expected three coordinates");
    return new Vec3(
        Float.parseFloat(fields[0]), Float.parseFloat(fields[1]), Float.parseFloat(fields[2]));
  }

  private static boolean samePosition(Vec3 first, Vec3 second) {
    return first.x() == second.x() && first.y() == second.y() && first.z() == second.z();
  }

  private static float distanceSquared(Vec3 first, Vec3 second) {
    float x = (float) first.x() - (float) second.x(),
        y = (float) first.y() - (float) second.y(),
        z = (float) first.z() - (float) second.z();
    return x * x + y * y + z * z;
  }

  private static int integer(Map<String, String> entity, String key) {
    String text = entity.getOrDefault(key, "0").trim();
    int end = text.startsWith("-") || text.startsWith("+") ? 1 : 0;
    while (end < text.length() && text.charAt(end) >= '0' && text.charAt(end) <= '9') end++;
    try {
      return Integer.parseInt(text.substring(0, end));
    } catch (NumberFormatException failure) {
      return 0;
    }
  }
}

package dev.bluevista.craftq3.botlib.goal;

import dev.bluevista.craftq3.botlib.item.ItemRegistry;
import dev.bluevista.craftq3.botlib.item.ItemRegistry.LevelItem;
import dev.bluevista.craftq3.botlib.weight.WeightConfig;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Original-script item ranking with explicit routing and stochastic-weight dependencies. */
public final class BotItemSelector {
  public interface Routing {
    int reachableArea(Vec3 origin, int client);

    boolean hasReachability(int area);

    /** Native AAS travel units; zero means no route. */
    int travelTime(int startArea, Vec3 origin, int goalArea, int travelFlags);
  }

  @FunctionalInterface
  public interface WeightEvaluator {
    float evaluate(WeightConfig config, int weightIndex, int[] inventory);
  }

  private final BotGoals goals;
  private final Supplier<List<LevelItem>> items;
  private final Routing routing;
  private final WeightEvaluator weights;
  private final IntSupplier gameType;
  private final DoubleSupplier droppedWeight;

  public BotItemSelector(
      BotGoals goals,
      Supplier<List<LevelItem>> items,
      Routing routing,
      WeightEvaluator weights,
      IntSupplier gameType,
      DoubleSupplier droppedWeight) {
    this.goals = Objects.requireNonNull(goals);
    this.items = Objects.requireNonNull(items);
    this.routing = Objects.requireNonNull(routing);
    this.weights = Objects.requireNonNull(weights);
    this.gameType = Objects.requireNonNull(gameType);
    this.droppedWeight = Objects.requireNonNull(droppedWeight);
  }

  public boolean chooseLongTerm(int state, Vec3 origin, int[] inventory, int travelFlags) {
    return choose(state, origin, inventory, travelFlags, false, null, 0);
  }

  /** Maximum time uses AAS travel units directly; a null long-term goal omits the onward test. */
  public boolean chooseNearby(
      int state, Vec3 origin, int[] inventory, int travelFlags, Goal longTerm, float maxTime) {
    if (!Float.isFinite(maxTime)) throw new IllegalArgumentException("Nonfinite nearby goal time");
    return choose(state, origin, inventory, travelFlags, true, longTerm, maxTime);
  }

  private boolean choose(
      int state,
      Vec3 origin,
      int[] inventory,
      int travelFlags,
      boolean nearby,
      Goal longTerm,
      float maxTime) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(inventory);
    var snapshot = goals.snapshot(state);
    if (snapshot.isEmpty()) return false;
    var configured = goals.weights(state);
    if (configured.isEmpty()) return false;
    WeightConfig config = configured.orElseThrow();
    if (inventory.length < config.requiredInventorySize())
      throw new IllegalArgumentException("Bot goal inventory too short");
    int area = routing.reachableArea(origin, snapshot.orElseThrow().client());
    if (area < 0) throw new IllegalArgumentException("Invalid reachable bot area");
    if (area != 0 && routing.hasReachability(area)) goals.lastReachableArea(state, area);
    else area = goals.lastReachableArea(state);
    if (area == 0) return false;
    int longTime =
        nearby && longTerm != null ? time(area, origin, longTerm.area(), travelFlags) : 0;
    float dropBoost = (float) droppedWeight.getAsDouble();
    if (!Float.isFinite(dropBoost))
      throw new IllegalArgumentException("Invalid dropped item weight");
    float best = 0;
    LevelItem selected = null;
    int type = gameType.getAsInt();
    for (LevelItem item : items.get()) {
      if (!ItemRegistry.permitted(item, type)
          || item.placement().area() == 0
          || item.entity() == 0 && (item.flags() & ItemRegistry.ROAM) == 0) continue;
      int index = config.find(item.info().classname());
      if (index < 0) continue;
      float weight = weights.evaluate(config, index, inventory);
      if (!Float.isFinite(weight))
        throw new IllegalArgumentException("Invalid evaluated item weight");
      if (item.timeout() != 0) weight += dropBoost;
      if ((item.flags() & ItemRegistry.ROAM) != 0) weight *= item.weight();
      if (weight <= 0) continue;
      int travel = time(area, origin, item.placement().area(), travelFlags);
      if (travel == 0
          || nearby && travel >= maxTime
          || goals.avoidTime(state, item.number()) > travel * .009) continue;
      float score = weight / travel;
      if (score <= best) continue;
      if (nearby
          && longTerm != null
          && item.timeout() == 0
          && time(
                  item.placement().area(),
                  item.placement().goalOrigin(),
                  longTerm.area(),
                  travelFlags)
              > longTime) continue;
      best = score;
      selected = item;
    }
    if (selected == null) return false;
    float duration =
        selected.timeout() != 0
            ? 10
            : selected.info().respawnTime() == 0 ? 30 : Math.max(10, selected.info().respawnTime());
    goals.setAvoidTime(state, selected.number(), duration);
    // Native selection still succeeds when the bounded stack reports overflow.
    goals.push(state, selected.goal());
    return true;
  }

  private int time(int area, Vec3 origin, int goalArea, int flags) {
    int value = routing.travelTime(area, origin, goalArea, flags);
    if (value < 0) throw new IllegalArgumentException("Negative bot travel time");
    return value;
  }
}

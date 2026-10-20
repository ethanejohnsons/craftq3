package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Predicts a route point visible from a goal without changing movement state. */
public final class BotVisiblePosition {
  private static final int MAX_REACHES = 20, VISIBILITY_MASK = 0x10001;
  private final AasMap map;
  private final NextReach nextReach;
  private final TraceWorld world;

  @FunctionalInterface
  interface NextReach {
    int select(
        int area, Vec3 origin, int goal, TravelPolicy policy, AasMovementRoutes.Context context);
  }

  public BotVisiblePosition(AasMap map, AasMovementRoutes routes, TraceWorld world) {
    this(map, selector(routes), world);
  }

  BotVisiblePosition(AasMap map, NextReach nextReach, TraceWorld world) {
    this.map = Objects.requireNonNull(map);
    this.nextReach = Objects.requireNonNull(nextReach);
    this.world = Objects.requireNonNull(world);
  }

  private static NextReach selector(AasMovementRoutes routes) {
    Objects.requireNonNull(routes);
    return (area, origin, goal, policy, context) ->
        routes.select(area, origin, goal, policy, context).reachability();
  }

  /** Empty results preserve the guest's output vector, including the native twenty-link limit. */
  public Optional<Vec3> predict(Vec3 origin, int area, Goal goal, TravelPolicy policy) {
    origin = MovementAbi.vector(origin);
    Objects.requireNonNull(goal);
    Objects.requireNonNull(policy);
    if (area <= 0 || goal.area() <= 0 || area == goal.area()) return Optional.empty();
    int previousArea = 0;
    for (int step = 0; step < MAX_REACHES; step++) {
      var context = new AasMovementRoutes.Context(goal.area(), previousArea, 0, List.of());
      int index = nextReach.select(area, origin, goal.area(), policy, context);
      if (index < 0 || index >= map.reachabilities().size())
        throw new IllegalStateException("Visible-position route returned invalid reachability");
      if (index == 0) return Optional.empty();
      var reach = map.reachabilities().get(index);
      if (visible(goal, reach.start())) return Optional.of(reach.start());
      // The native operation still traces this endpoint before accepting arrival in the goal area.
      if (visible(goal, reach.end()) || reach.area() == goal.area())
        return Optional.of(reach.end());
      previousArea = area;
      area = reach.area();
      origin = reach.end();
    }
    return Optional.empty();
  }

  private boolean visible(Goal goal, Vec3 target) {
    return world
            .trace(TraceRequest.ray(goal.origin(), target, VISIBILITY_MASK).ignoring(goal.entity()))
            .fraction()
        == 1;
  }
}

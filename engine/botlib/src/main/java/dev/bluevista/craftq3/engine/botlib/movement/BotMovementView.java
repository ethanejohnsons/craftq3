package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.engine.botlib.goal.Goal;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;
import java.util.Optional;

/** Route look-ahead using borrowed movement history; never changes movement state. */
public final class BotMovementView {
  /** A failed query can still have written an intermediate target. */
  public record Result(boolean success, Optional<Vec3f> target) {
    public Result {
      target = Objects.requireNonNull(target);
      if (success && target.isEmpty())
        throw new IllegalArgumentException("Successful view query requires a target");
    }
  }

  private final AasWorld map;

  @FunctionalInterface
  interface NextReach {
    int select(
        int area, Vec3f origin, int goal, TravelPolicy policy, AasMovementRoutes.Context context);
  }

  private final NextReach nextReach;
  private final int maxLinks;

  public BotMovementView(AasWorld map, AasMovementRoutes routes) {
    this(map, selector(routes), 100_000);
  }

  BotMovementView(AasWorld map, NextReach nextReach, int maxLinks) {
    this.map = Objects.requireNonNull(map);
    this.nextReach = Objects.requireNonNull(nextReach);
    if (maxLinks < 1 || maxLinks > 1_000_000)
      throw new IllegalArgumentException("Invalid movement view work limit");
    this.maxLinks = maxLinks;
  }

  private static NextReach selector(AasMovementRoutes routes) {
    Objects.requireNonNull(routes);
    return (area, origin, goal, policy, context) ->
        routes.select(area, origin, goal, policy, context).reachability();
  }

  public Result target(
      Vec3f origin,
      int lastReachability,
      Goal goal,
      int travelFlags,
      float lookAhead,
      AasMovementRoutes.Context context) {
    return target(
        origin, lastReachability, goal, TravelPolicy.ofFlags(travelFlags), lookAhead, context);
  }

  public Result target(
      Vec3f origin,
      int lastReachability,
      Goal goal,
      TravelPolicy policy,
      float lookAhead,
      AasMovementRoutes.Context context) {
    origin = Objects.requireNonNull(origin);
    Objects.requireNonNull(goal);
    Objects.requireNonNull(context);
    Objects.requireNonNull(policy);
    if (!Float.isFinite(lookAhead))
      throw new IllegalArgumentException("Nonfinite movement lookahead");
    if (lastReachability < 0 || lastReachability >= map.reachabilities().size())
      throw new IllegalArgumentException("Invalid movement reachability");
    if (lastReachability == 0 || lookAhead <= 0) return new Result(false, Optional.empty());
    Vec3f position = origin;
    Vec3f output;
    int index = lastReachability;
    float distance = 0;
    for (int iteration = 0; iteration < maxLinks; iteration++) {
      var reach = map.reachabilities().get(index);
      var first = advance(position, reach.start(), lookAhead, distance);
      output = first.position();
      if (first.finished()) return new Result(true, Optional.of(output));
      distance = first.distance();
      var type = reach.travel();
      if (type == TravelType.TELEPORT
          || type == TravelType.ROCKET_JUMP
          || type == TravelType.BFG_JUMP) return new Result(true, Optional.of(output));
      // Movers and jump pads carry the bot; only the approach to their start is walked.
      if (type != TravelType.ELEVATOR
          && type != TravelType.JUMP_PAD
          && type != TravelType.FUNC_BOB) {
        var second = advance(reach.start(), reach.end(), lookAhead, distance);
        output = second.position();
        if (second.finished()) return new Result(true, Optional.of(output));
        distance = second.distance();
      }
      position = reach.end();
      if (reach.area() == goal.area())
        return new Result(
            true, Optional.of(advance(position, goal.origin(), lookAhead, distance).position()));
      int selected = nextReach.select(reach.area(), position, goal.area(), policy, context);
      context =
          new AasMovementRoutes.Context(
              context.previousGoalArea(), reach.area(), context.time(), context.avoided());
      index = selected;
      if (index < 0 || index >= map.reachabilities().size())
        throw new IllegalStateException("Movement route returned invalid reachability");
      if (index == 0) return new Result(false, Optional.of(output));
    }
    throw new IllegalStateException("Movement view route work budget exhausted");
  }

  private record Step(Vec3f position, float distance, boolean finished) {}

  private static Step advance(Vec3f start, Vec3f end, float lookAhead, float walked) {
    float x = end.x() - start.x();
    float y = end.y() - start.y();
    float z = end.z() - start.z();
    float squared = x * x + y * y + z * z;
    float root = (float) Math.sqrt(squared);
    float inverse = root == 0 ? 0 : 1 / root;
    // The native normalizer returns squared length times its rounded inverse root.
    float length = squared * inverse;
    if (!Float.isFinite(length))
      throw new IllegalArgumentException("Movement view segment exceeds range");
    float total = walked + length;
    if (total <= lookAhead) return new Step(end, total, total == lookAhead);
    float remaining = lookAhead - walked;
    var point =
        new Vec3f(
            start.x() + remaining * (x * inverse),
            start.y() + remaining * (y * inverse),
            start.z() + remaining * (z * inverse));
    return new Step(point, 0, true);
  }
}

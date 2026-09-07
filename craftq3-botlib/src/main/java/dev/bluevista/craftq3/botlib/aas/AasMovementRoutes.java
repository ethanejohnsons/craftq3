package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.movement.BotMovement.AvoidSpot;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Objects;

/** Native-observed movement reach selection, with history, timed avoidance and spherical spots. */
public final class AasMovementRoutes {
  public static final int BLOCKED_BY_AVOID_SPOT = 256;

  public record AvoidReach(int reachability, float expiresAt, int tries) {
    public AvoidReach {
      if (reachability < 1 || !Float.isFinite(expiresAt) || tries < 0)
        throw new IllegalArgumentException("Invalid avoided reachability");
    }
  }

  public record Context(
      int previousGoalArea, int previousArea, float time, List<AvoidReach> avoided) {
    public static final Context EMPTY = new Context(0, 0, 0, List.of());

    public Context {
      avoided = List.copyOf(avoided);
      if (previousGoalArea < 0
          || previousArea < 0
          || !Float.isFinite(time)
          || time < 0
          || avoided.size() > 1)
        throw new IllegalArgumentException(
            "Invalid movement route context (one native avoidance slot)");
    }
  }

  /**
   * Global reachability index and candidate score; both are zero when no candidate is available.
   * Flags can still report a candidate blocked by an avoid spot when another route is selected.
   */
  public record Selection(int reachability, int travelTime, int flags) {
    public Selection(int reachability, int travelTime) {
      this(reachability, travelTime, 0);
    }

    public Selection {
      if (reachability < 0
          || travelTime < 0
          || (reachability == 0) != (travelTime == 0)
          || (flags & ~BLOCKED_BY_AVOID_SPOT) != 0)
        throw new IllegalArgumentException("Invalid movement route selection");
    }
  }

  private static final Selection NONE = new Selection(0, 0);
  private final AasMap map;
  private final AasRouteTimes routes;
  private final int maxCandidates;

  public AasMovementRoutes(AasMap map) {
    this(new AasRouteTimes(map));
  }

  public AasMovementRoutes(AasRouteTimes routes) {
    this(routes, 4096);
  }

  public AasMovementRoutes(AasRouteTimes routes, int maxCandidates) {
    this.routes = Objects.requireNonNull(routes);
    this.map = routes.map();
    if (maxCandidates < 1 || maxCandidates > 1_048_576)
      throw new IllegalArgumentException("Invalid movement candidate limit");
    this.maxCandidates = maxCandidates;
  }

  public Selection select(int area, Vec3 origin, int goal, int flags, Context context) {
    return select(area, origin, goal, TravelPolicy.ofFlags(flags), context);
  }

  public Selection select(
      int area, Vec3 origin, int goal, int flags, Context context, List<AvoidSpot> spots) {
    return select(area, origin, goal, TravelPolicy.ofFlags(flags), context, spots);
  }

  /**
   * Origin-to-first-link distance is deliberately absent from the native candidate score. Physical
   * travel execution and movement-state updates are separate operations.
   */
  public Selection select(int area, Vec3 origin, int goal, TravelPolicy policy, Context context) {
    return select(area, origin, goal, policy, context, List.of());
  }

  public Selection select(
      int area,
      Vec3 origin,
      int goal,
      TravelPolicy policy,
      Context context,
      List<AvoidSpot> spots) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(policy);
    Objects.requireNonNull(context);
    var avoidSpots = new AasAvoidSpots(spots);
    if (!Float.isFinite((float) origin.x())
        || !Float.isFinite((float) origin.y())
        || !Float.isFinite((float) origin.z()))
      throw new IllegalArgumentException("Movement route origin exceeds float range");
    if (area <= 0 || area >= map.areas().size() || goal <= 0 || goal >= map.areas().size())
      return NONE;
    var settings = map.areaSettings().get(area);
    int first = settings.firstReachability();
    if (first <= 0 || first >= map.reachabilities().size()) return NONE;
    // Native AAS_NextAreaReachability exposes a valid stored first link even for count zero.
    int candidateCount = Math.max(1, settings.reachabilityCount());
    if (candidateCount > maxCandidates)
      throw new IllegalStateException("Movement route candidate limit exceeded");
    if (((settings.contents() | map.areaSettings().get(goal).contents()) & 256) != 0)
      policy =
          new TravelPolicy(
              policy.travelFlags() | TravelFlags.DO_NOT_ENTER,
              policy.presenceTypes(),
              policy.team(),
              policy.disabledAreas());
    int best = 0, bestTime = 0, resultFlags = 0;
    for (int r = first; r < first + candidateCount; r++) {
      var reach = map.reachabilities().get(r);
      if (context.previousGoalArea() == goal && context.previousArea() == reach.area()) continue;
      boolean avoided = false;
      for (var entry : context.avoided())
        if (entry.reachability() == r && entry.tries() >= 5 && entry.expiresAt() >= context.time())
          avoided = true;
      if (avoided
          || !policy.permitsReachability(reach)
          || !policy.permitsArea(reach.area(), map.areaSettings().get(reach.area()))) continue;
      int remainder = routes.travelTime(reach.area(), reach.end(), goal, policy);
      if (remainder == 0) continue;
      if (avoidSpots.type(origin, reach) != 0) {
        resultFlags |= BLOCKED_BY_AVOID_SPOT;
        continue;
      }
      int time = Math.addExact(remainder, reach.travelTime());
      if (best == 0 || time < bestTime) {
        best = r;
        bestTime = time;
      }
    }
    return new Selection(best, bestTime, resultFlags);
  }
}

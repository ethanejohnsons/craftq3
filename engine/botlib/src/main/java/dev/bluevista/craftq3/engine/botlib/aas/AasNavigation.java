package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Independent spatial and stored-cost graph queries over an AasReader-validated immutable map. */
public final class AasNavigation {
  private final AasWorld map;

  public AasNavigation(AasWorld map) {
    this.map = Objects.requireNonNull(map);
  }

  public AasWorld map() {
    return map;
  }

  /**
   * Zero is solid/no area. Float32 classification chooses the back child for a point exactly on a
   * splitting plane.
   */
  public int pointArea(Vec3f point) {
    return AasSpatialQueries.point(map, point);
  }

  /**
   * The number of areas with reachabilities, summed over clusters ({@code
   * AAS_PointReachabilityAreaIndex(NULL)}).
   */
  public int reachabilityAreaCount() {
    int count = 0;
    for (var cluster : map.clusters()) count += cluster.reachabilityAreaCount();
    return count;
  }

  /**
   * A dense index of the routable area containing {@code point}: the reachability areas of all
   * lower-numbered clusters plus the area's number within its own cluster ({@code
   * AAS_PointReachabilityAreaIndex}). Zero when the point is in solid or in an area without
   * reachabilities. A portal area counts in its front cluster; the original reads that cluster
   * through an out-of-range portal index here, which this implementation does not reproduce.
   */
  public int pointReachabilityAreaIndex(Vec3f point) {
    int area = pointArea(point);
    if (area <= 0 || area >= map.areaSettings().size()) return 0;
    var settings = map.areaSettings().get(area);
    if (settings.reachabilityCount() == 0) return 0;
    int cluster = settings.cluster(), clusterArea = settings.clusterArea();
    if (cluster < 0) {
      if (-cluster >= map.portals().size()) return 0;
      var portal = map.portals().get(-cluster);
      cluster = portal.frontCluster();
      clusterArea = portal.frontClusterArea();
    }
    int index = 0;
    for (int i = 0; i < cluster && i < map.clusters().size(); i++)
      index += map.clusters().get(i).reachabilityAreaCount();
    return index + clusterArea;
  }

  public AreaQuery boxAreas(Vec3f min, Vec3f max, int maxAreas) {
    return boxAreas(min, max, QueryBudget.defaults(maxAreas));
  }

  /** Conservative BSP/AABB overlap query, with unique area numbers in ascending order. */
  public AreaQuery boxAreas(Vec3f min, Vec3f max, QueryBudget budget) {
    return AasSpatialQueries.box(map, min, max, budget);
  }

  public AreaTrace traceAreas(Vec3f start, Vec3f end, int maxAreas) {
    return traceAreas(start, end, QueryBudget.defaults(maxAreas));
  }

  /** Ordered area intervals along a point segment, continuing through solid gaps. Not collision. */
  public AreaTrace traceAreas(Vec3f start, Vec3f end, QueryBudget budget) {
    return AasSpatialQueries.trace(map, start, end, budget);
  }

  public AasPresenceTrace.Result presenceTrace(Vec3f start, Vec3f end, int presenceMask) {
    return new AasPresenceTrace(map).trace(start, end, presenceMask);
  }

  public List<ReachLink> reachabilities(int area, TravelPolicy policy) {
    requireArea(area);
    Objects.requireNonNull(policy);
    if (!policy.permitsArea(area, map.areaSettings().get(area))) return List.of();
    var settings = map.areaSettings().get(area);
    var result = new ArrayList<ReachLink>();
    for (int i = settings.firstReachability();
        i < settings.firstReachability() + settings.reachabilityCount();
        i++) {
      Reachability reach = map.reachabilities().get(i);
      if (policy.permitsReachability(reach)
          && policy.permitsArea(reach.area(), map.areaSettings().get(reach.area())))
        result.add(new ReachLink(i, area, reach));
    }
    return List.copyOf(result);
  }

  /**
   * Dijkstra over stored inter-area times only; no intra-area time, physics, or generated links.
   */
  public RouteResult route(int startArea, int goalArea, TravelPolicy policy, SearchBudget budget) {
    requireArea(startArea);
    requireArea(goalArea);
    return AasRouter.route(
        map, startArea, goalArea, Objects.requireNonNull(policy), Objects.requireNonNull(budget));
  }

  private void requireArea(int area) {
    if (area <= 0 || area >= map.areas().size())
      throw new IllegalArgumentException("Invalid AAS area " + area);
  }

  public record QueryBudget(int maxResults, int maxNodeVisits) {
    public QueryBudget {
      if (maxResults < 1
          || maxResults > 1_048_576
          || maxNodeVisits < 1
          || maxNodeVisits > 2_000_000)
        throw new IllegalArgumentException("AAS query budget out of range");
    }

    public static QueryBudget defaults(int maxResults) {
      return new QueryBudget(maxResults, 1_000_000);
    }
  }

  public record AreaQuery(List<Integer> areas, boolean complete, int nodeVisits) {
    public AreaQuery {
      areas = List.copyOf(areas);
    }
  }

  public record AreaSpan(
      int area, double enterFraction, double exitFraction, Vec3f entry, Vec3f exit) {}

  public record AreaTrace(
      List<AreaSpan> spans, boolean complete, boolean startSolid, int endArea, int nodeVisits) {
    public AreaTrace {
      spans = List.copyOf(spans);
    }
  }

  public record ReachLink(int index, int sourceArea, Reachability reachability) {}

  public record SearchBudget(
      int maxSettledAreas, int maxExaminedReachabilities, long maxTravelTime) {
    public SearchBudget {
      if (maxSettledAreas < 1
          || maxSettledAreas > 65_536
          || maxExaminedReachabilities < 1
          || maxExaminedReachabilities > 1_048_576
          || maxTravelTime < 0) throw new IllegalArgumentException("AAS route budget out of range");
    }

    public static SearchBudget defaults() {
      return new SearchBudget(65_536, 1_048_576, Long.MAX_VALUE);
    }
  }

  public enum RouteStatus {
    FOUND,
    UNREACHABLE,
    BUDGET_EXCEEDED
  }

  public record RouteResult(
      RouteStatus status,
      List<ReachLink> links,
      long travelTime,
      int settledAreas,
      int examinedReachabilities) {
    public RouteResult {
      links = List.copyOf(links);
    }

    public boolean found() {
      return status == RouteStatus.FOUND;
    }
  }
}

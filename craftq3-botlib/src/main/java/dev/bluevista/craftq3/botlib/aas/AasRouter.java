package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasNavigation.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class AasRouter {
  private AasRouter() {}

  static RouteResult route(
      AasMap map, int start, int goal, TravelPolicy policy, SearchBudget budget) {
    if (!policy.permitsArea(start, map.areaSettings().get(start))
        || !policy.permitsArea(goal, map.areaSettings().get(goal)))
      return failure(RouteStatus.UNREACHABLE, 0, 0);
    int size = map.areas().size(), settledCount = 0, examined = 0;
    long[] distance = new long[size];
    Arrays.fill(distance, Long.MAX_VALUE);
    int[] previousArea = new int[size], previousReach = new int[size];
    boolean[] settled = new boolean[size];
    var pending =
        new PriorityQueue<Candidate>(
            Comparator.comparingLong(Candidate::time).thenComparingInt(Candidate::area));
    distance[start] = 0;
    pending.add(new Candidate(start, 0));
    boolean timeLimited = false;
    while (!pending.isEmpty()) {
      Candidate current = pending.remove();
      int area = current.area();
      if (settled[area] || distance[area] != current.time()) continue;
      if (settledCount == budget.maxSettledAreas())
        return failure(RouteStatus.BUDGET_EXCEEDED, settledCount, examined);
      settled[area] = true;
      settledCount++;
      if (area == goal) {
        var path = new ArrayList<ReachLink>();
        int cursor = goal;
        while (cursor != start) {
          int source = previousArea[cursor], link = previousReach[cursor];
          path.add(new ReachLink(link, source, map.reachabilities().get(link)));
          cursor = source;
        }
        Collections.reverse(path);
        return new RouteResult(RouteStatus.FOUND, path, current.time(), settledCount, examined);
      }
      var settings = map.areaSettings().get(area);
      for (int index = settings.firstReachability();
          index < settings.firstReachability() + settings.reachabilityCount();
          index++) {
        if (examined == budget.maxExaminedReachabilities())
          return failure(RouteStatus.BUDGET_EXCEEDED, settledCount, examined);
        examined++;
        var reach = map.reachabilities().get(index);
        int next = reach.area();
        if (settled[next]
            || !policy.permitsReachability(reach)
            || !policy.permitsArea(next, map.areaSettings().get(next))) continue;
        // Subtraction avoids overflow even with an unbounded caller time limit.
        if (reach.travelTime() > budget.maxTravelTime() - current.time()) {
          timeLimited = true;
          continue;
        }
        long time = current.time() + reach.travelTime();
        if (time < distance[next]) {
          distance[next] = time;
          previousArea[next] = area;
          previousReach[next] = index;
          pending.add(new Candidate(next, time));
        }
      }
    }
    return failure(
        timeLimited ? RouteStatus.BUDGET_EXCEEDED : RouteStatus.UNREACHABLE,
        settledCount,
        examined);
  }

  private static RouteResult failure(RouteStatus status, int settled, int examined) {
    return new RouteResult(status, List.of(), 0, settled, examined);
  }

  private record Candidate(int area, long time) {}
}

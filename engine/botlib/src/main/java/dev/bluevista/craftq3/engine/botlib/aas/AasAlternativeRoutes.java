package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Alternative route goals ({@code AAS_AlternativeRouteGoals}), which the CTF AI uses to approach a
 * flag along different paths. An area is a mid-range area when it is reachable from the start no
 * slower than 1.1 times the direct route and reaches the goal no slower than 0.8 times it. Each
 * face-connected group of mid-range areas yields one goal: the area closest to the group's mean
 * center.
 */
public final class AasAlternativeRoutes {
  /** {@code ALTROUTEGOAL_ALL}: consider every area. */
  public static final int ALL = 1;

  /** {@code ALTROUTEGOAL_CLUSTERPORTALS}: consider cluster portal areas. */
  public static final int CLUSTER_PORTALS = 2;

  /** {@code ALTROUTEGOAL_VIEWPORTALS}: consider view portal areas. */
  public static final int VIEW_PORTALS = 4;

  /**
   * One goal. Times are in hundredths of a second; {@code extraTravelTime} is how much longer the
   * detour is than the direct route. Times are truncated to 16 bits as the original stores them.
   */
  public record Goal(
      Vec3f origin, int area, int startTravelTime, int goalTravelTime, int extraTravelTime) {
    public Goal {
      Objects.requireNonNull(origin);
    }
  }

  private final AasWorld map;
  private final AasRouteTimes routes;

  public AasAlternativeRoutes(AasRouteTimes routes) {
    this.routes = Objects.requireNonNull(routes);
    this.map = routes.map();
  }

  public List<Goal> goals(
      Vec3f start,
      int startArea,
      Vec3f goal,
      int goalArea,
      TravelPolicy policy,
      int maxGoals,
      int type) {
    Objects.requireNonNull(start);
    Objects.requireNonNull(policy);
    int areaCount = map.areas().size();
    if (maxGoals <= 0
        || startArea <= 0
        || startArea >= areaCount
        || goalArea <= 0
        || goalArea >= areaCount) return List.of();
    int direct = routes.travelTime(startArea, start, goalArea, policy);
    boolean[] midRange = new boolean[areaCount];
    int[] startTimes = new int[areaCount], goalTimes = new int[areaCount];
    for (int area = 1; area < areaCount; area++) {
      var settings = map.areaSettings().get(area);
      if ((type & ALL) == 0) {
        boolean portal =
            (type & CLUSTER_PORTALS) != 0
                && (settings.contents() & Contents.AREA_CLUSTERPORTAL) != 0;
        boolean view =
            (type & VIEW_PORTALS) != 0 && (settings.contents() & Contents.AREA_VIEWPORTAL) != 0;
        if (!portal && !view) continue;
      }
      if (settings.reachabilityCount() == 0) continue;
      int fromStart = routes.travelTime(startArea, start, area, policy);
      if (fromStart == 0 || fromStart > 1.1f * direct) continue;
      int toGoal = routes.travelTime(area, null, goalArea, policy);
      if (toGoal == 0 || toGoal > .8f * direct) continue;
      midRange[area] = true;
      startTimes[area] = fromStart;
      goalTimes[area] = toGoal;
    }
    var result = new ArrayList<Goal>();
    var group = new ArrayList<Integer>();
    for (int area = 1; area < areaCount && result.size() < maxGoals; area++) {
      if (!midRange[area]) continue;
      group.clear();
      flood(area, midRange, group);
      float x = 0, y = 0, z = 0;
      for (int member : group) {
        Vec3f center = map.areas().get(member).center();
        x += center.x();
        y += center.y();
        z += center.z();
      }
      // The original scales by the double reciprocal of the area count.
      double scale = 1.0 / group.size();
      Vec3f mean = new Vec3f((float) (x * scale), (float) (y * scale), (float) (z * scale));
      int best = 0;
      float bestDistance = 999999;
      for (int member : group) {
        float distance = mean.subtract(map.areas().get(member).center()).length();
        if (distance < bestDistance) {
          bestDistance = distance;
          best = member;
        }
      }
      result.add(
          new Goal(
              map.areas().get(best).center(),
              best,
              startTimes[best] & 0xffff,
              goalTimes[best] & 0xffff,
              (startTimes[best] + goalTimes[best] - direct) & 0xffff));
    }
    return List.copyOf(result);
  }

  /**
   * Collects the mid-range areas face-connected to {@code first}, clearing their marks, in the
   * original's depth-first order.
   */
  private void flood(int first, boolean[] midRange, List<Integer> group) {
    // An explicit stack of (area, next face) frames reproduces the recursive visiting order.
    var stack = new ArrayDeque<int[]>();
    midRange[first] = false;
    group.add(first);
    stack.push(new int[] {first, 0});
    while (!stack.isEmpty()) {
      int[] frame = stack.peek();
      int area = frame[0];
      var geometry = map.areas().get(area);
      if (frame[1] >= geometry.faceCount()) {
        stack.pop();
        continue;
      }
      int index = geometry.firstFace() + frame[1]++;
      if (index < 0 || index >= map.faceIndices().size()) continue;
      int faceNumber = Math.abs(map.faceIndices().get(index));
      if (faceNumber >= map.faces().size()) continue;
      var face = map.faces().get(faceNumber);
      int other = face.frontArea() == area ? face.backArea() : face.frontArea();
      if (other <= 0 || other >= midRange.length || !midRange[other]) continue;
      midRange[other] = false;
      group.add(other);
      stack.push(new int[] {other, 0});
    }
  }
}

package dev.bluevista.craftq3.engine.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Alternative route goals over an authored one-cluster map: from area 1 to area 4 either through
 * area 2 (two reaches of 10) or through area 3 (two reaches of 11). Reach end and start points meet
 * at area centers, so every walk inside an area costs the minimum of 1.
 */
final class AasAlternativeRoutesTest {
  private static final TravelPolicy POLICY = TravelPolicy.ofFlags(TravelFlags.DEFAULT);

  @Test
  void everyMidRangeAreaWithoutSharedFacesIsItsOwnGoal() {
    var map = map(List.of());
    var routes = new AasRouteTimes(map);
    Vec3f start = center(1);
    assertEquals(23, routes.travelTime(1, start, 4, POLICY));
    var goals =
        new AasAlternativeRoutes(routes)
            .goals(start, 1, center(4), 4, POLICY, 8, AasAlternativeRoutes.ALL);
    // Area 1 itself is too far from the goal (22 > 0.8 * 23); areas 2, 3 and the goal qualify.
    assertEquals(List.of(2, 3, 4), goals.stream().map(AasAlternativeRoutes.Goal::area).toList());
    var viaThree = goals.get(1);
    assertEquals(center(3), viaThree.origin());
    assertEquals(13, viaThree.startTravelTime());
    assertEquals(12, viaThree.goalTravelTime());
    assertEquals(13 + 12 - 23, viaThree.extraTravelTime());
    assertEquals(
        List.of(2),
        new AasAlternativeRoutes(routes)
            .goals(start, 1, center(4), 4, POLICY, 1, AasAlternativeRoutes.ALL).stream()
                .map(AasAlternativeRoutes.Goal::area)
                .toList());
  }

  @Test
  void faceConnectedMidRangeAreasMergeIntoTheAreaNearestTheirMeanCenter() {
    // A face joins areas 2 and 4: they form one group whose mean center lies between them.
    var goals =
        new AasAlternativeRoutes(new AasRouteTimes(map(List.of(new int[] {2, 4}))))
            .goals(center(1), 1, center(4), 4, POLICY, 8, AasAlternativeRoutes.ALL);
    assertEquals(List.of(2, 3), goals.stream().map(AasAlternativeRoutes.Goal::area).toList());
  }

  @Test
  void portalFiltersAndInvalidRequestsYieldNoGoals() {
    var routes = new AasRouteTimes(map(List.of()));
    var alternatives = new AasAlternativeRoutes(routes);
    assertEquals(
        List.of(),
        alternatives.goals(
            center(1), 1, center(4), 4, POLICY, 8, AasAlternativeRoutes.CLUSTER_PORTALS));
    assertEquals(List.of(), alternatives.goals(center(1), 0, center(4), 4, POLICY, 8, 1));
    assertEquals(List.of(), alternatives.goals(center(1), 1, center(4), 9, POLICY, 8, 1));
    assertEquals(List.of(), alternatives.goals(center(1), 1, center(4), 4, POLICY, 0, 1));
  }

  private static Vec3f center(int area) {
    return new Vec3f(area * 100, 0, 0);
  }

  /** Four areas in cluster 1; {@code links} lists area pairs sharing a face. */
  private static AasWorld map(List<int[]> links) {
    var zero = Vec3f.ZERO;
    var areas = new ArrayList<AasWorld.Area>();
    var settings = new ArrayList<AasWorld.AreaSettings>();
    areas.add(new AasWorld.Area(0, 0, 0, zero, zero, zero));
    settings.add(new AasWorld.AreaSettings(0, 0, 0, 0, 0, 0, 0));
    int[] firstReach = {0, 1, 3, 4, 5};
    int[] reachCount = {0, 2, 1, 1, 1};
    var faces = new ArrayList<AasWorld.Face>();
    faces.add(new AasWorld.Face(0, 0, 0, 0, 0, 0));
    for (int[] link : links) faces.add(new AasWorld.Face(0, 0, 0, 0, link[0], link[1]));
    var faceIndices = new ArrayList<Integer>();
    for (int area = 1; area <= 4; area++) {
      int first = faceIndices.size(), count = 0;
      for (int face = 1; face < faces.size(); face++) {
        if (faces.get(face).frontArea() != area && faces.get(face).backArea() != area) continue;
        faceIndices.add(face);
        count++;
      }
      areas.add(new AasWorld.Area(area, count, first, center(area), center(area), center(area)));
      settings.add(
          new AasWorld.AreaSettings(0, 1, 2, 1, area - 1, reachCount[area], firstReach[area]));
    }
    var reaches =
        List.of(
            new AasWorld.Reachability(0, 0, 0, zero, zero, 0, 0, 0),
            reach(1, 2, 10),
            reach(1, 3, 11),
            reach(2, 4, 10),
            reach(3, 4, 11),
            reach(4, 1, 30));
    var indices = new AasWorld.Indices(faceIndices.stream().mapToInt(Integer::intValue).toArray());
    var empty = new AasWorld.Indices(new int[0]);
    return new AasWorld(
        5,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        empty,
        faces,
        indices,
        areas,
        settings,
        reaches,
        List.of(),
        List.of(new AasWorld.Portal(0, 0, 0, 0, 0)),
        empty,
        List.of(new AasWorld.Cluster(0, 0, 0, 0), new AasWorld.Cluster(4, 4, 0, 0)));
  }

  private static AasWorld.Reachability reach(int from, int to, int time) {
    return new AasWorld.Reachability(to, 0, 0, center(from), center(to), 2, time, 0);
  }
}

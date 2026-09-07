package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.botlib.movement.BotMovement.AvoidSpot;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AasMovementRoutesTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final AasMovementRoutes.Context EMPTY = AasMovementRoutes.Context.EMPTY;

  @Test
  void choosesNativeCandidateScoreWithoutOriginApproachDistance() {
    var route = new AasMovementRoutes(map(false, 0, 10, 12));
    var expected = new AasMovementRoutes.Selection(1, 11);
    assertEquals(expected, route.select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY));
    assertEquals(
        expected, route.select(1, new Vec3(-10000, 500, 1000), 2, TravelFlags.DEFAULT, EMPTY));
    // Link 1 starts 1000 units away; link 2 starts at the origin but has a worse remainder.
  }

  @Test
  void previousAreaIsExcludedOnlyWhilePursuingTheSameGoal() {
    var route = new AasMovementRoutes(map(false, 0, 10, 12));
    assertEquals(
        2,
        route
            .select(
                1, ZERO, 2, TravelFlags.DEFAULT, new AasMovementRoutes.Context(2, 2, 0, List.of()))
            .reachability());
    assertEquals(
        1,
        route
            .select(
                1, ZERO, 2, TravelFlags.DEFAULT, new AasMovementRoutes.Context(3, 2, 0, List.of()))
            .reachability());
  }

  @Test
  void fifthAttemptAndInclusiveExpiryMatchNativeAvoidance() {
    var route = new AasMovementRoutes(map(false, 0, 10, 12));
    assertEquals(1, route.select(1, ZERO, 2, TravelFlags.DEFAULT, avoid(10, 4)).reachability());
    assertEquals(2, route.select(1, ZERO, 2, TravelFlags.DEFAULT, avoid(10, 5)).reachability());
    assertEquals(
        1,
        route.select(1, ZERO, 2, TravelFlags.DEFAULT, avoid(Math.nextDown(10f), 5)).reachability());
    assertEquals(2, route.select(1, ZERO, 2, TravelFlags.DEFAULT, avoid(11, 5)).reachability());
  }

  @Test
  void permissionsFilterCandidatesAndEndpointDoNotEnterEnablesEscape() {
    assertEquals(
        1,
        new AasMovementRoutes(map(false, 256, 100, 12))
            .select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY)
            .reachability());
    assertEquals(
        2,
        new AasMovementRoutes(map(true, 256, 100, 12))
            .select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY)
            .reachability());
    var route = new AasMovementRoutes(map(false, 0, 10, 12));
    var disabled = new TravelPolicy(TravelFlags.DEFAULT, 6, TravelPolicy.Team.ANY, Set.of(2));
    assertEquals(new AasMovementRoutes.Selection(0, 0), route.select(1, ZERO, 2, disabled, EMPTY));
    assertEquals(0, route.select(1, ZERO, 2, 0, EMPTY).reachability());
  }

  @Test
  void equalScoresKeepTheFirstStoredOutgoingLink() {
    assertEquals(
        new AasMovementRoutes.Selection(1, 11),
        new AasMovementRoutes(map(false, 0, 10, 8)).select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY));
  }

  @Test
  void blockedCandidatesSetFlagsEvenWhenAnAlternativeIsSelected() {
    for (int type : new int[] {1, 2}) {
      var spots = List.of(new AvoidSpot(new Vec3(500, 0, 0), 1, type));
      assertEquals(
          new AasMovementRoutes.Selection(2, 15, 256),
          new AasMovementRoutes(map(false, 0, 10, 12))
              .select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY, spots));
      // The blocked candidate can have a worse score than an already acceptable route.
      assertEquals(
          new AasMovementRoutes.Selection(2, 15, 256),
          new AasMovementRoutes(map(false, 0, 100, 12))
              .select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY, spots));
    }
  }

  @Test
  void blockedFlagRequiresARoutableCandidateAndDoesNotPromiseFallback() {
    var route = new AasMovementRoutes(map(false, 0, 10, 12));
    var spots = List.of(new AvoidSpot(new Vec3(500, 0, 0), 1, 2));
    var excludeVia = new AasMovementRoutes.Context(2, 3, 0, List.of());
    assertEquals(
        new AasMovementRoutes.Selection(0, 0, 256),
        route.select(1, ZERO, 2, TravelFlags.DEFAULT, excludeVia, spots));
    // The remaining candidate cannot route from destination 2 to goal 3.
    excludeVia = new AasMovementRoutes.Context(3, 3, 0, List.of());
    assertEquals(
        new AasMovementRoutes.Selection(0, 0),
        route.select(1, ZERO, 3, TravelFlags.DEFAULT, excludeVia, spots));
  }

  @Test
  void aZeroCountStillExposesExactlyTheValidStoredFirstLink() {
    var route = new AasMovementRoutes(map(false, 0, 10, 12, 0, 1));
    assertEquals(
        new AasMovementRoutes.Selection(1, 11),
        route.select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY));
    assertEquals(
        new AasMovementRoutes.Selection(0, 0),
        route.select(1, ZERO, 2, TravelFlags.DEFAULT, avoid(10, 5)));
    assertEquals(
        new AasMovementRoutes.Selection(0, 0, 256),
        route.select(
            1,
            ZERO,
            2,
            TravelFlags.DEFAULT,
            EMPTY,
            List.of(new AvoidSpot(new Vec3(500, 0, 0), 1, 1))));
  }

  @Test
  void zeroAndEndSentinelsDoNotExposeAnOutOfBoundsCandidate() {
    for (int first : new int[] {0, 5}) {
      assertEquals(
          new AasMovementRoutes.Selection(0, 0),
          new AasMovementRoutes(map(false, 0, 10, 12, 0, first))
              .select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY));
    }
  }

  @Test
  void contextIsImmutableAndInvalidInputsAndBudgetsAreExplicit() {
    var input = new ArrayList<AasMovementRoutes.AvoidReach>();
    input.add(new AasMovementRoutes.AvoidReach(1, 11, 5));
    var context = new AasMovementRoutes.Context(0, 0, 10, input);
    input.clear();
    var routes = new AasRouteTimes(map(false, 0, 10, 12));
    var movement = new AasMovementRoutes(routes);
    assertEquals(2, movement.select(1, ZERO, 2, TravelFlags.DEFAULT, context).reachability());
    assertThrows(UnsupportedOperationException.class, () -> context.avoided().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AasMovementRoutes.Context(
                0,
                0,
                0,
                List.of(
                    new AasMovementRoutes.AvoidReach(1, 0, 0),
                    new AasMovementRoutes.AvoidReach(2, 0, 0))));
    assertThrows(
        IllegalArgumentException.class,
        () -> movement.select(1, new Vec3(1e100, 0, 0), 2, TravelFlags.DEFAULT, EMPTY));
    assertThrows(
        IllegalStateException.class,
        () -> new AasMovementRoutes(routes, 1).select(1, ZERO, 2, TravelFlags.DEFAULT, EMPTY));
    assertEquals(0, movement.select(0, ZERO, 2, TravelFlags.DEFAULT, EMPTY).reachability());
    assertEquals(0, movement.select(1, ZERO, 99, TravelFlags.DEFAULT, EMPTY).reachability());
  }

  private static AasMovementRoutes.Context avoid(float expires, int tries) {
    return new AasMovementRoutes.Context(
        0, 0, 10, List.of(new AasMovementRoutes.AvoidReach(1, expires, tries)));
  }

  private static AasMap map(
      boolean startForbidden, int middleContents, int directCost, int viaCost) {
    return map(startForbidden, middleContents, directCost, viaCost, 2, 1);
  }

  private static AasMap map(
      boolean startForbidden,
      int middleContents,
      int directCost,
      int viaCost,
      int sourceCount,
      int sourceFirst) {
    var areas = new ArrayList<Area>();
    for (int i = 0; i < 4; i++) areas.add(new Area(i, 0, 0, ZERO, ZERO, ZERO));
    var settings =
        List.of(
            new AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AreaSettings(startForbidden ? 256 : 0, 1, 6, 1, 0, sourceCount, sourceFirst),
            new AreaSettings(0, 1, 6, 1, 1, 1, 3),
            new AreaSettings(middleContents, 1, 6, 1, 2, 1, 4));
    var reaches =
        List.of(
            new Reachability(0, 0, 0, ZERO, ZERO, 1, 0, 0),
            new Reachability(2, 0, 0, new Vec3(1000, 0, 0), ZERO, 2, directCost, 0),
            new Reachability(3, 0, 0, ZERO, ZERO, 2, 1, 0),
            new Reachability(2, 0, 0, ZERO, ZERO, 2, 1, 0),
            new Reachability(2, 0, 0, ZERO, ZERO, 2, viaCost, 0));
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Indices(new int[0]),
        List.of(),
        new Indices(new int[0]),
        areas,
        settings,
        reaches,
        List.of(),
        List.of(new Portal(0, 0, 0, 0, 0)),
        new Indices(new int[0]),
        List.of(new Cluster(0, 0, 0, 0), new Cluster(3, 3, 0, 0)));
  }
}

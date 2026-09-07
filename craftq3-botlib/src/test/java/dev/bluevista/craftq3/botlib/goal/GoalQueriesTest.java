package dev.bluevista.craftq3.botlib.goal;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GoalQueriesTest {
  private static Goal goal(int entity, int flags) {
    return new Goal(
        new Vec3(100, 0, 0), 7, new Vec3(-15, -15, -15), new Vec3(15, 15, 15), entity, 2, flags, 3);
  }

  @Test
  void standingHullTouchesClosedBoundsIncludingVerticalExtent() {
    Goal goal = goal(40, Goal.ITEM);
    for (Vec3 origin :
        new Vec3[] {
          new Vec3(70, 0, 0),
          new Vec3(130, 0, 0),
          new Vec3(100, -30, 0),
          new Vec3(100, 30, 0),
          new Vec3(100, 0, -47),
          new Vec3(100, 0, 39)
        }) assertTrue(GoalQueries.touching(origin, goal));
    for (Vec3 origin :
        new Vec3[] {
          new Vec3(Math.nextDown(70f), 0, 0),
          new Vec3(Math.nextUp(130f), 0, 0),
          new Vec3(100, Math.nextDown(-30f), 0),
          new Vec3(100, Math.nextUp(30f), 0),
          new Vec3(100, 0, Math.nextDown(-47f)),
          new Vec3(100, 0, Math.nextUp(39f))
        }) assertFalse(GoalQueries.touching(origin, goal));
    assertTrue(GoalQueries.touching(new Vec3(100, 0, 0), goal(0, 0)));
  }

  @Test
  void missingItemRequiresClearLowerCornerRayAndStrictHalfSecondAge() {
    var world = new World();
    assertTrue(
        GoalQueries.visibleButMissing(
            3, new Vec3(0, 0, 32), new Vec3(0, 180, 0), goal(40, 1), 5, world, n -> 4.49));
    assertEquals(new Vec3(85, -15, -15), world.request.end());
    assertEquals(3, world.request.ignoreEntity());
    assertEquals(1, world.request.contentsMask());
    assertTrue(world.request.isPoint());
    assertFalse(
        GoalQueries.visibleButMissing(
            3, new Vec3(0, 0, 32), new Vec3(0, 0, 0), goal(40, 1), 5, world, n -> 4.5));
    world.fraction = .5;
    assertFalse(
        GoalQueries.visibleButMissing(
            3,
            new Vec3(0, 0, 32),
            new Vec3(0, 0, 0),
            goal(40, 1),
            5,
            world,
            n -> {
              fail("Occluded item must not query entity");
              return 0;
            }));
  }

  @Test
  void expandsLocalBoundsBeforeAddingWorldOrigin() {
    var goal =
        new Goal(
            new Vec3(889.9717f, 0, 0),
            7,
            new Vec3(-9.903813f, -15, -15),
            new Vec3(13.440642f, 15, 15),
            40,
            3,
            1,
            2);
    assertTrue(GoalQueries.touching(new Vec3(918.41235f, 0, 0), goal));
    assertFalse(GoalQueries.touching(new Vec3(Math.nextUp(918.41235f), 0, 0), goal));
  }

  @Test
  void nonItemsShortCircuitAndUnlinkedItemsDoNotQueryEntity() {
    var world = new World();
    assertFalse(
        GoalQueries.visibleButMissing(
            3,
            new Vec3(0, 0, 0),
            new Vec3(0, 0, 0),
            goal(40, Goal.DROPPED),
            5,
            world,
            n -> {
              fail();
              return 0;
            }));
    assertNull(world.request);
    assertFalse(
        GoalQueries.visibleButMissing(
            3,
            new Vec3(0, 0, 0),
            new Vec3(0, 0, 0),
            goal(0, 1),
            5,
            world,
            n -> {
              fail();
              return 0;
            }));
    assertNotNull(world.request);
  }

  private static class World implements TraceWorld {
    TraceRequest request;
    double fraction = 1;

    @Override
    public TraceResult trace(TraceRequest value) {
      request = value;
      return new TraceResult(fraction, value.end(), false, false, Optional.empty());
    }

    @Override
    public int pointContents(Vec3 point, int mask, int ignore) {
      return 0;
    }
  }
}

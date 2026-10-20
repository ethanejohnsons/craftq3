package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.testing.*;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class LiquidReachMovementTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void swimTargetsTheStartInThreeDimensionsAndAlwaysPublishesSwimView() {
    var world = new World();
    var service = service(world, 1, 0);
    var reach = reach(8, new Vec3f(90, 180, 270), new Vec3f(-100, -200, -300));
    var output = service.execute(input(2, ZERO), 0, 1, reach);
    assertEquals(
        new Vec3f(.26726123690605164f, .5345224738121033f, .8017836809158325f),
        output.result().direction());
    assertEquals(
        new Vec3f(-53.30077362060547f, 63.4349479675293f, 0), output.result().idealViewAngles());
    assertEquals(400, output.movement().orElseThrow().speed());
    assertEquals(output.result().direction(), output.movement().orElseThrow().direction());
    assertEquals(2, output.result().flags());
    assertEquals(0, output.result().travelType());
    assertEquals(0, output.actionFlags());
    assertEquals(output, service.execute(input(2, ZERO), 1023, 1, reach));
    assertEquals(new Vec3f(-15, -15, -24), world.queries.getFirst().mins());
  }

  @Test
  void zeroSwimStillMovesAndUsesNativeVerticalZeroAngleWithoutConsumingRandom() {
    var world = new World();
    var service =
        new LiquidReachMovement(
            new MovementObstruction(world, a -> 1),
            () -> {
              throw new AssertionError("Unexpected random draw");
            });
    var output = service.execute(input(4, ZERO), 514, 1, reach(8, ZERO, new Vec3f(10, 20, 30)));
    assertEquals(ZERO, output.movement().orElseThrow().direction());
    assertEquals(400, output.movement().orElseThrow().speed());
    assertEquals(new Vec3f(-270, 0, 0), output.result().idealViewAngles());
    assertEquals(new Vec3f(15, 15, -2), world.queries.getFirst().maxs());
  }

  @Test
  void swimCombinesEntityObstructionMetadataAndKeepsIssuingTheCommand() {
    var world = new World();
    world.entities = new int[] {1023, 15};
    var output =
        service(world, 0, 0).execute(input(2, ZERO), 4, 0, reach(8, new Vec3f(100, 0, 0), ZERO));
    assertEquals(2, world.queries.size());
    assertEquals(1, output.result().blocked());
    assertEquals(15, output.result().blockEntity());
    assertEquals(34, output.result().flags());
    assertEquals(new Vec3f(1, 0, 0), output.movement().orElseThrow().direction());
    assertEquals(
        Float.floatToRawIntBits(-0f),
        Float.floatToRawIntBits(output.result().idealViewAngles().x()));
  }

  @Test
  void waterJumpAimsWithOneSampleAndPreservesAnAbsentMoveWithForwardAndUpActions() {
    var world = new World();
    var draws = new AtomicInteger();
    var service =
        new LiquidReachMovement(
            new MovementObstruction(world, a -> 1),
            () -> {
              draws.incrementAndGet();
              return 0;
            });
    var output =
        service.execute(
            input(2, ZERO), 16, 1, reach(9, new Vec3f(200, 0, 30), new Vec3f(10, 0, 60)));
    assertEquals(new Vec3f(.2747211158275604f, 0, .9615239500999451f), output.result().direction());
    assertEquals(new Vec3f(-74.05460357666016f, 0, 0), output.result().idealViewAngles());
    assertEquals(1, output.result().flags());
    assertEquals(544, output.actionFlags());
    assertTrue(output.movement().isEmpty());
    assertEquals(1, draws.get());
    assertTrue(world.queries.isEmpty());
  }

  @Test
  void waterJumpUpThresholdUsesHorizontalDistanceAndKeepsNativeRounding() {
    var service = service(new World(), 1, 0);
    assertEquals(
        544,
        service
            .execute(input(2, ZERO), 0, 1, reach(9, ZERO, new Vec3f(Math.nextDown(40f), 0, -1000)))
            .actionFlags());
    assertEquals(
        512,
        service
            .execute(input(2, ZERO), 0, 1, reach(9, ZERO, new Vec3f(40, 0, 1000)))
            .actionFlags());
    var output =
        service(new World(), 1, 28220 / 32767.0f)
            .execute(
                input(2, new Vec3f(69.50941f, -12.7044525f, -214.5564f)),
                2,
                1,
                reach(
                    9,
                    new Vec3f(89, -65.50001f, -210),
                    new Vec3f(76.74549f, -56.84933f, -213.24998f)));
    assertEquals(
        new Vec3f(.11377964168787003f, -.6941307783126831f, .7107999920845032f),
        output.result().direction());
  }

  @Test
  void waterJumpCompletionClearsResultAndLeavesCommandsRandomAndCollisionUntouched() {
    var world = new World();
    var service =
        new LiquidReachMovement(
            new MovementObstruction(world, a -> 1),
            () -> {
              throw new AssertionError("Unexpected random draw");
            });
    for (int flags : new int[] {0, 2, 4, 8, 16, 20, 32, 512, 1023}) {
      var output =
          service.finish(
              input(2, new Vec3f(100, 200, -300)),
              flags,
              0,
              reach(9, ZERO, new Vec3f(800, 900, 1200)));
      assertEquals(new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), output.result());
      assertEquals(0, output.actionFlags());
      assertTrue(output.movement().isEmpty());
    }
    assertTrue(world.queries.isEmpty());
  }

  @Test
  void sameAreaSwimUsesThreeDimensionalDistanceAndTheNativeSlowingDeadband() {
    var world = new World();
    var service =
        new LiquidReachMovement(
            new MovementObstruction(world, a -> 1),
            () -> {
              throw new AssertionError("Unexpected random draw");
            });
    var vertical = service.moveInGoalArea(input(2, ZERO), 4, 1, new Vec3f(0, 0, 100));
    assertEquals(new Vec3f(0, 0, 1), vertical.movement().orElseThrow().direction());
    assertEquals(400, vertical.movement().orElseThrow().speed());
    assertEquals(8, vertical.result().travelType());
    assertEquals(2, vertical.result().flags());
    assertEquals(
        0,
        service
            .moveInGoalArea(input(2, ZERO), 4, 1, new Vec3f(2.49f, 0, 0))
            .movement()
            .orElseThrow()
            .speed());
    assertEquals(
        10,
        service
            .moveInGoalArea(input(2, ZERO), 4, 1, new Vec3f(2.5f, 0, 0))
            .movement()
            .orElseThrow()
            .speed());
    assertEquals(
        10.040008544921875f,
        service
            .moveInGoalArea(input(2, ZERO), 516, 1, new Vec3f(2.51f, 0, 0))
            .movement()
            .orElseThrow()
            .speed());
    assertThrows(
        IllegalArgumentException.class, () -> service.moveInGoalArea(input(2, ZERO), 2, 1, ZERO));
  }

  @Test
  void invalidKindsSamplesAndExcessiveVectorsFailExplicitly() {
    var world = new World();
    var service = service(world, 1, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(input(2, ZERO), 0, 1, reach(2, ZERO, ZERO)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.finish(input(2, ZERO), 0, 1, reach(8, ZERO, ZERO)));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(input(2, ZERO), 0, 1, reach(8, new Vec3f(1e30f, 0, 0), ZERO)));
    for (double value : new double[] {Double.NaN, -1, 1.01}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> service(world, 1, value).execute(input(2, ZERO), 0, 1, reach(9, ZERO, ZERO)));
    }
    assertTrue(world.queries.isEmpty());
  }

  private static MovementInit input(int presence, Vec3f origin) {
    return new MovementInit(
        origin,
        new Vec3f(200, -100, 500),
        new Vec3f(0, 0, 26),
        0,
        0,
        .1f,
        presence,
        new Vec3f(25, 190, 0),
        128);
  }

  private static Reachability reach(int type, Vec3f start, Vec3f end) {
    return new Reachability(1, 0, 0, start, end, type, 1, 0);
  }

  private static LiquidReachMovement service(World world, int outgoing, double random) {
    return new LiquidReachMovement(new MovementObstruction(world, a -> outgoing), () -> random);
  }

  private static final class World implements TraceWorld {
    final ArrayList<TraceRequest> queries = new ArrayList<>();
    int[] entities = {};

    public TraceResult trace(TraceRequest request) {
      int index = queries.size();
      queries.add(request);
      if (index >= entities.length || entities[index] == 1023) return TraceResult.clear(request);
      return new TraceResult(
          .5,
          request.start(),
          false,
          false,
          Optional.of(
              new TraceResult.Hit(
                  new TraceResult.Plane(new Vec3f(0, 0, 1), 0),
                  1,
                  0,
                  entities[index],
                  -1,
                  -1,
                  -1,
                  -1,
                  "")));
    }

    public int pointContents(Vec3f p, int mask, int ignore) {
      return 0;
    }
  }
}

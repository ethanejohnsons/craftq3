package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AasInitialContactTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void initialSolidContactRetainsTheDownwardRawPlaneAndCanStopOnDamage() {
    var world = world(new Vec3f(0, 0, -1));
    var trace = world.trace(new Vec3f(0, 0, 100), new Vec3f(0, 0, 30), 2, -1);
    assertTrue(trace.startSolid());
    assertEquals(0, trace.planeNumber());
    assertEquals(new Vec3f(0, 0, -1), world.planeNormal(trace.planeNumber()));

    var result = predictor(world).predict(request(-1000, false)).orElseThrow();
    assertEquals(AasMovementPredictor.HIT_GROUND_DAMAGE, result.stopEvent());
    assertEquals(new Vec3f(0, 0, 100.25f), result.endPosition());
    assertEquals(0, result.endArea());
    assertEquals(ZERO, result.velocity());
    assertEquals(0, result.frames());
    assertTrue(result.trace().orElseThrow().startSolid());
    assertEquals(0, result.trace().orElseThrow().fraction());
  }

  @Test
  void obliqueInitialContactChargesTheFallBeforeClippingEvenWhenTheRemainingFallIsBelowThreshold() {
    var world = world(new Vec3f(0, .8f, -.6f));
    var result = predictor(world).predict(request(-620, false)).orElseThrow();
    assertEquals(AasMovementPredictor.HIT_GROUND_DAMAGE, result.stopEvent());
    assertEquals(-33.6000023, result.velocity().y(), 1e-5);
    assertEquals(-60.9280014, result.velocity().z(), 1e-5);
    assertTrue(result.velocity().z() * result.velocity().z() < 4000);
    assertTrue(result.trace().orElseThrow().startSolid());
    // The same requested grounded state retains the smaller change-in-velocity charge.
    assertTrue(predictor(world).predict(request(-620, true)).isEmpty());
  }

  private static AasMovementPredictor.Request request(float verticalVelocity, boolean ground) {
    return new AasMovementPredictor.Request(
        -1,
        new Vec3f(0, 0, 100),
        2,
        ground,
        new Vec3f(0, 0, verticalVelocity),
        ZERO,
        0,
        1,
        .1f,
        AasMovementPredictor.HIT_GROUND_DAMAGE);
  }

  private static AasMovementPredictor predictor(AasMovementWorld world) {
    return new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults());
  }

  /** A structurally valid single AAS partition, with a permitted front and solid back. */
  private static AasMovementWorld world(Vec3f normal) {
    var indices = new AasWorld.Indices(new int[0]);
    var map =
        new AasWorld(
            4,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(new AasWorld.Plane(normal, 0, 3), new AasWorld.Plane(normal.scale(-1), 0, 3)),
            List.of(),
            indices,
            List.of(),
            indices,
            List.of(
                new AasWorld.Area(0, 0, 0, ZERO, ZERO, ZERO),
                new AasWorld.Area(1, 0, 0, ZERO, ZERO, ZERO)),
            List.of(
                new AasWorld.AreaSettings(0, 0, 0, 0, 0, 0, 0),
                new AasWorld.AreaSettings(0, 1, 6, 0, 0, 0, 0)),
            List.of(),
            List.of(new AasWorld.Node(0, 0, 0), new AasWorld.Node(0, -1, 0)),
            List.of(),
            indices,
            List.of());
    return new AasMovementWorld(
        new AasNavigation(map),
        (start, mins, maxs, end, entity, mask) -> {
          throw new AssertionError("No dynamic entity belongs to this fixture");
        },
        point -> 0);
  }
}

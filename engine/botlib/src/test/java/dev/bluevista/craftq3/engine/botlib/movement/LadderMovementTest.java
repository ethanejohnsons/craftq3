package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import org.junit.jupiter.api.Test;

/** Behavioural checks of the ladder executor; no native oracle values. */
final class LadderMovementTest {
  @Test
  void climbingLooksAlongTheLadderWithTripledPitchAndMovesForward() {
    var reach = ladder(new Vec3f(0, 10, 10));
    var output = new LadderMovement().execute(input(Vec3f.ZERO), 0, 1, reach);
    var direction = output.result().direction();
    assertEquals(0, direction.x());
    assertEquals(Math.sqrt(.5), direction.y(), 1e-6);
    assertEquals(Math.sqrt(.5), direction.z(), 1e-6);
    var view = output.result().idealViewAngles();
    assertEquals(-Math.toDegrees(Math.atan2(3, 1)), view.x(), 1e-4);
    assertEquals(90, view.y(), 1e-4);
    assertEquals(MovementResult.MOVEMENT_VIEW, output.result().flags());
    assertEquals(ActionFlags.MOVE_FORWARD, output.actionFlags());
    var move = output.movement().orElseThrow();
    assertEquals(Vec3f.ZERO, move.direction());
    assertEquals(0, move.speed());
  }

  @Test
  void climbingDownLooksDown() {
    var output =
        new LadderMovement()
            .execute(input(new Vec3f(0, 0, 100)), 0, 1, ladder(new Vec3f(10, 0, 0)));
    // Vector2Angles stores -(pitch + 360) for downward views; modulo 360 that looks down.
    float pitch = output.result().idealViewAngles().x();
    assertTrue(pitch < -270 && pitch > -360, "pitch " + pitch);
    assertEquals(88.09, pitch + 360, .01);
  }

  @Test
  void otherTravelTypesAreRejected() {
    var walk = new Reachability(2, 0, 0, Vec3f.ZERO, Vec3f.ZERO, TravelType.WALK.id(), 1, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new LadderMovement().execute(input(Vec3f.ZERO), 0, 1, walk));
  }

  private static Reachability ladder(Vec3f end) {
    return new Reachability(2, 0, 0, Vec3f.ZERO, end, TravelType.LADDER.id(), 10, 0);
  }

  private static MovementInit input(Vec3f origin) {
    return new MovementInit(origin, Vec3f.ZERO, Vec3f.ZERO, 1, 1, .1f, 2, Vec3f.ZERO, 2);
  }
}

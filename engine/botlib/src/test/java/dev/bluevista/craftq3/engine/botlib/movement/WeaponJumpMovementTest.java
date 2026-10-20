package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.ea.ElementaryActions;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

final class WeaponJumpMovementTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);
  private final WeaponJumpMovement service = new WeaponJumpMovement();

  @Test
  void approachPublishesWeaponAndDownwardViewWithoutPrematureFire() {
    var output =
        service.execute(
            input(ZERO, ZERO, ZERO),
            514,
            1,
            reach(12, new Vec3f(1.2345f, 0, 200), new Vec3f(0, 100, 100)),
            55,
            7);
    assertEquals(new Vec3f(1, 0, 0), output.movement().orElseThrow().direction());
    assertEquals(6.1724853515625f, output.movement().orElseThrow().speed());
    assertEquals(0, output.actionFlags());
    assertEquals(7, output.jumpReach());
    assertEquals(24, output.result().flags());
    assertEquals(5, output.result().weapon());
    assertEquals(5, output.weapon().orElseThrow());
    assertEquals(new Vec3f(90, 0, 0), output.view().orElseThrow());
    assertEquals(output.view().orElseThrow(), output.result().idealViewAngles());
  }

  @Test
  void rocketAlignsWithApproachButBfgAlignsWithZeroInputAngles() {
    var rocket = reach(12, new Vec3f(0, 1, 500), new Vec3f(100, 0, 100));
    var bfg = reach(13, rocket.start(), rocket.end());
    var aimedRocket =
        service.execute(input(ZERO, ZERO, new Vec3f(90, 90, 75)), 2, 1, rocket, 55, 7);
    assertEquals(17, aimedRocket.actionFlags());
    assertEquals(55, aimedRocket.jumpReach());
    assertEquals(new Vec3f(1, 0, 0), aimedRocket.result().direction());
    assertEquals(new Vec3f(90, 0, 0), aimedRocket.view().orElseThrow());
    assertEquals(
        0, service.execute(input(ZERO, ZERO, new Vec3f(0, 90, 0)), 2, 1, bfg, 55, 7).actionFlags());
    var aimedBfg = service.execute(input(ZERO, ZERO, ZERO), 2, 1, bfg, 55, 7);
    assertEquals(17, aimedBfg.actionFlags());
    assertEquals(55, aimedBfg.jumpReach());
    assertEquals(9, aimedBfg.weapon().orElseThrow());
    assertEquals(new Vec3f(90, 0, 0), aimedBfg.view().orElseThrow());
    assertEquals(
        0, service.execute(input(ZERO, ZERO, new Vec3f(90, 0, 0)), 2, 1, bfg, 55, 7).actionFlags());
  }

  @Test
  void launchThresholdsAreStrictAndAngleWrappingOccursOnlyOnce() {
    var reach = reach(12, new Vec3f(4.9999f, 0, 0), new Vec3f(0, 100, 100));
    assertEquals(
        17,
        service.execute(input(ZERO, ZERO, new Vec3f(90, 0, 0)), 0, 1, reach, 55, 7).actionFlags());
    assertEquals(
        0,
        service
            .execute(
                input(ZERO, ZERO, new Vec3f(90, 0, 0)),
                0,
                1,
                reach(12, new Vec3f(5, 0, 0), reach.end()),
                55,
                7)
            .actionFlags());
    for (Vec3f view :
        new Vec3f[] {
          new Vec3f(85, 0, 0),
          new Vec3f(95, 0, 0),
          new Vec3f(90, 5, 0),
          new Vec3f(90, -5, 0),
          new Vec3f(90, 720, 0)
        })
      assertEquals(
          0,
          service.execute(input(ZERO, ZERO, view), 0, 1, reach, 55, 7).actionFlags(),
          view.toString());
    for (Vec3f view :
        new Vec3f[] {
          new Vec3f(85.0001f, 4.9999f, 0),
          new Vec3f(94.9999f, -4.9999f, 0),
          new Vec3f(450, -360, 0),
          new Vec3f(-270, 360, 0)
        })
      assertEquals(
          17,
          service.execute(input(ZERO, ZERO, view), 0, 1, reach, 55, 7).actionFlags(),
          view.toString());
    var bfg = reach(13, new Vec3f(0, 1, 0), new Vec3f(100, 0, 100));
    for (Vec3f view :
        new Vec3f[] {
          new Vec3f(5, 0, 0),
          new Vec3f(-5, 0, 0),
          new Vec3f(0, 5, 0),
          new Vec3f(0, -5, 0),
          new Vec3f(0, 720, 0)
        })
      assertEquals(
          0,
          service.execute(input(ZERO, ZERO, view), 0, 1, bfg, 55, 7).actionFlags(),
          view.toString());
    for (Vec3f view :
        new Vec3f[] {
          new Vec3f(4.9999f, -4.9999f, 0), new Vec3f(-4.9999f, 4.9999f, 0), new Vec3f(360, -360, 0)
        })
      assertEquals(
          17,
          service.execute(input(ZERO, ZERO, view), 0, 1, bfg, 55, 7).actionFlags(),
          view.toString());
  }

  @Test
  void launchRequestsSemanticJumpWhilePreservingPriorElementaryActions() {
    var output =
        service.execute(
            input(ZERO, ZERO, new Vec3f(90, 0, 0)),
            2,
            1,
            reach(12, ZERO, new Vec3f(0, 100, 0)),
            55,
            7);
    try (var ea = new ElementaryActions(1, (client, command) -> {})) {
      ea.action(0, 268435472 | 8192);
      ea.action(0, output.actionFlags() & ~16);
      ea.jump(0);
      output.movement().ifPresent(move -> ea.move(0, move.direction(), move.speed()));
      output.view().ifPresent(view -> ea.view(0, view));
      output.weapon().ifPresent(weapon -> ea.selectWeapon(0, weapon));
      var actual = ea.snapshot(0);
      assertEquals(268435456 | 8192 | 1, actual.actionFlags());
      assertEquals(new Vec3f(0, 1, 0), actual.direction());
      assertEquals(400, actual.speed());
      assertEquals(new Vec3f(90, 90, 0), actual.viewAngles());
      assertEquals(5, actual.weapon());
    }
  }

  @Test
  void absentJumpHistoryFinishesWithNoMovementViewWeaponOrActions() {
    var output =
        service.finish(
            input(new Vec3f(70, 0, 0), new Vec3f(200, 200, 300), new Vec3f(90, 10, 0)),
            1023,
            0,
            reach(12, ZERO, new Vec3f(100, 0, 130)),
            55,
            0);
    assertEquals(new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO), output.result());
    assertTrue(output.movement().isEmpty());
    assertTrue(output.view().isEmpty());
    assertTrue(output.weapon().isEmpty());
    assertEquals(0, output.actionFlags());
    assertEquals(0, output.jumpReach());
  }

  @Test
  void activeCompletionUsesVelocityAndSuppliedEndpointWhileKeepingHeldWeaponAndView() {
    var input = input(new Vec3f(70, 0, 0), new Vec3f(200, 200, 300), new Vec3f(90, 10, 0));
    var output =
        service.finish(input, 2, 1, reach(12, new Vec3f(0, 0, 30), new Vec3f(100, 0, 130)), 55, 7);
    assertEquals(new Vec3f(.7196931838989258f, .6942922472953796f, 0), output.result().direction());
    assertEquals(400, output.movement().orElseThrow().speed());
    assertEquals(0, output.result().flags());
    assertEquals(0, output.actionFlags());
    assertEquals(7, output.jumpReach());
    assertTrue(output.view().isEmpty());
    assertTrue(output.weapon().isEmpty());
    assertEquals(
        output,
        service.finish(
            input, 1023, 1, reach(13, new Vec3f(0, 0, 30), new Vec3f(100, 0, 130)), 999, 7));
  }

  @Test
  void invalidTravelStateAndExcessiveArithmeticFailExplicitly() {
    var input = input(ZERO, ZERO, ZERO);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(input, 0, 1, reach(5, ZERO, ZERO), 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(input, 0, -1, reach(12, ZERO, ZERO), 1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.finish(input, 0, 1, reach(12, ZERO, ZERO), 1, -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.execute(input, 0, 1, reach(12, new Vec3f(1e30f, 0, 0), ZERO), 1, 0));
  }

  private static MovementInit input(Vec3f origin, Vec3f velocity, Vec3f view) {
    return new MovementInit(origin, velocity, new Vec3f(0, 0, 26), 0, 0, .1f, 2, view, 128);
  }

  private static Reachability reach(int type, Vec3f start, Vec3f end) {
    return new Reachability(1, 0, 0, start, end, type, 1, 0);
  }
}

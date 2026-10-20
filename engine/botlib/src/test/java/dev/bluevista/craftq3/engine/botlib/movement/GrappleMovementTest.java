package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.BspTrace;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.engine.botlib.movement.BotMovement.GrappleState;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Behavioural checks of the grapple executor; no native oracle values. */
final class GrappleMovementTest {
  private static final Vec3f END = new Vec3f(0, 0, 500);
  private static final Vec3f UP = new Vec3f(-90, 0, 0);
  private static final MovementSettings OFFHAND =
      MovementSettings.from(Map.of("offhandgrapple", "1")::get);

  /** Open space, or a solid ceiling at z = 100 for every trace. */
  private record World(boolean ceiling) implements BotCollision {
    @Override
    public BspTrace trace(
        Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int passEntity, int contentMask) {
      if (!ceiling || end.z() <= 100) return BspTrace.clear(end);
      return BspTrace.hit(.2f, new Vec3f(end.x(), end.y(), 100), new Vec3f(0, 0, -1), 1, 1022);
    }

    @Override
    public int pointContents(Vec3f point) {
      return 0;
    }
  }

  @Test
  void standingAtTheStartAndAimingAtTheEndFiresTheHook() {
    var output =
        grapple(false, 1, MovementSettings.DEFAULT)
            .execute(input(Vec3f.ZERO, UP), 0, 1, 1, REACH, GrappleState.EMPTY, 5);
    assertEquals(ActionFlags.ATTACK, output.actionFlags());
    assertEquals(BotMovement.ACTIVE_GRAPPLE, output.movementFlags());
    assertEquals(new GrappleState(5, 999999), output.grapple());
    assertEquals(
        MovementResult.MOVEMENT_VIEW | MovementResult.MOVEMENT_WEAPON, output.result().flags());
    assertEquals(MovementSettings.DEFAULT.grapple(), output.result().weapon());
    assertEquals(UP, output.result().idealViewAngles());
    assertTrue(output.movement().isEmpty());
    assertFalse(output.clearReachDeadline());
  }

  @Test
  void anOffhandHookIsFiredByCommandWithoutSelectingAWeapon() {
    var output =
        grapple(false, 1, OFFHAND)
            .execute(input(Vec3f.ZERO, UP), 0, 1, 1, REACH, GrappleState.EMPTY, 5);
    assertEquals(List.of("grappleon"), output.commands());
    assertEquals(0, output.actionFlags());
    assertEquals(MovementResult.MOVEMENT_VIEW, output.result().flags());
    assertEquals(0, output.result().weapon());
  }

  @Test
  void aBlockedPathToTheGrapplePointFails() {
    var output =
        grapple(true, 1, MovementSettings.DEFAULT)
            .execute(input(Vec3f.ZERO, UP), 0, 1, 1, REACH, GrappleState.EMPTY, 5);
    assertEquals(1, output.result().failure());
    assertEquals(MovementResult.BAD_GRAPPLE_PATH, output.result().type());
    assertEquals(0, output.movementFlags() & BotMovement.ACTIVE_GRAPPLE);
  }

  @Test
  void awayFromTheStartOrNotYetAimedTheBotWalksToTheStart() {
    var travel = grapple(false, 1, MovementSettings.DEFAULT);
    var far =
        travel.execute(input(new Vec3f(-100, 0, 7), UP), 0, 1, 1, REACH, GrappleState.EMPTY, 5);
    var move = far.movement().orElseThrow();
    assertEquals(new Vec3f(1, 0, 0), move.direction());
    assertEquals(400, move.speed());
    assertEquals(0, far.actionFlags());
    // Inside 70 units the speed is four times the distance.
    var near =
        travel.execute(input(new Vec3f(-10, 0, 0), UP), 0, 1, 1, REACH, GrappleState.EMPTY, 5);
    assertEquals(40, near.movement().orElseThrow().speed());
    // At the start but looking elsewhere: no shot, walk in place while turning.
    var turning =
        travel.execute(input(Vec3f.ZERO, Vec3f.ZERO), 0, 1, 1, REACH, GrappleState.EMPTY, 5);
    assertEquals(0, turning.actionFlags() & ActionFlags.ATTACK);
    assertEquals(UP, turning.result().idealViewAngles());
  }

  @Test
  void leavingTheReachAreaBeforeTheHookIsOutEndsTheReach() {
    var output =
        grapple(false, 9, MovementSettings.DEFAULT)
            .execute(input(new Vec3f(-100, 0, 0), UP), 0, 1, 1, REACH, GrappleState.EMPTY, 5);
    assertTrue(output.clearReachDeadline());
  }

  @Test
  void anActiveHookKeepsPullingWhileItGetsCloser() {
    var travel = grapple(false, 1, MovementSettings.DEFAULT);
    var output =
        travel.execute(
            input(new Vec3f(-200, 0, 0), UP),
            BotMovement.ACTIVE_GRAPPLE,
            1,
            1,
            REACH,
            new GrappleState(1, 999999),
            5);
    assertEquals(ActionFlags.ATTACK, output.actionFlags());
    assertEquals(BotMovement.ACTIVE_GRAPPLE, output.movementFlags());
    assertEquals(new GrappleState(5, 200), output.grapple());
  }

  @Test
  void anArrivedHookIsReleasedAndAsksForANewRoute() {
    var output =
        grapple(false, 1, OFFHAND)
            .execute(
                input(new Vec3f(-20, 0, 0), UP),
                BotMovement.ACTIVE_GRAPPLE | BotMovement.GRAPPLE_PULL,
                1,
                1,
                REACH,
                new GrappleState(4, 20.5f),
                5);
    assertEquals(BotMovement.GRAPPLE_PULL | BotMovement.GRAPPLE_RESET, output.movementFlags());
    assertTrue(output.clearReachDeadline());
    assertEquals(List.of("grappleoff"), output.commands());
  }

  @Test
  void aHookThatIsGoneForTooLongIsReleased() {
    var gone = grapple(false, 1, MovementSettings.DEFAULT, false);
    var recent =
        gone.execute(
            input(new Vec3f(-200, 0, 0), UP),
            BotMovement.ACTIVE_GRAPPLE,
            1,
            1,
            REACH,
            new GrappleState(4.8f, 300),
            5);
    assertEquals(BotMovement.ACTIVE_GRAPPLE, recent.movementFlags());
    var lost =
        gone.execute(
            input(new Vec3f(-200, 0, 0), UP),
            BotMovement.ACTIVE_GRAPPLE,
            1,
            1,
            REACH,
            new GrappleState(4.5f, 300),
            5);
    assertEquals(BotMovement.GRAPPLE_RESET, lost.movementFlags());
  }

  @Test
  void aPendingResetOnlyClearsTheActiveHook() {
    var output =
        grapple(false, 1, OFFHAND)
            .execute(
                input(Vec3f.ZERO, UP),
                BotMovement.ACTIVE_GRAPPLE | BotMovement.GRAPPLE_RESET,
                1,
                1,
                REACH,
                GrappleState.EMPTY,
                5);
    assertEquals(MovementResult.CLEAR, output.result());
    assertEquals(BotMovement.GRAPPLE_RESET, output.movementFlags());
    assertEquals(List.of("grappleoff"), output.commands());
    var wrong = new Reachability(2, 0, 0, Vec3f.ZERO, END, TravelType.WALK.id(), 10, 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            grapple(false, 1, OFFHAND)
                .execute(input(Vec3f.ZERO, UP), 0, 1, 1, wrong, GrappleState.EMPTY, 5));
  }

  private static final Reachability REACH =
      new Reachability(2, 0, 0, Vec3f.ZERO, END, TravelType.GRAPPLE_HOOK.id(), 10, 0);

  private static GrappleMovement grapple(boolean ceiling, int area, MovementSettings settings) {
    return grapple(ceiling, area, settings, true);
  }

  private static GrappleMovement grapple(
      boolean ceiling, int area, MovementSettings settings, boolean hookVisible) {
    var world = new World(ceiling);
    return new GrappleMovement(
        world, new MovementObstruction(world, a -> 1), point -> area, () -> hookVisible, settings);
  }

  private static MovementInit input(Vec3f origin, Vec3f viewAngles) {
    return new MovementInit(origin, Vec3f.ZERO, Vec3f.ZERO, 1, 1, .1f, 2, viewAngles, 2);
  }
}

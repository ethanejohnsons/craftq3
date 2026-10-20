package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.ea.ActionFlags;
import dev.bluevista.craftq3.engine.botlib.movement.BotMovement.GrappleState;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

/**
 * GRAPPLEHOOK travel ({@code BotTravel_Grapple}). Before the hook is out the bot walks to the reach
 * start, aims at the grapple point and fires once it stands still and the path is clear; while the
 * hook is active it keeps firing (or relies on the off-hand command) until it arrives or the hook
 * stops pulling, then releases and asks for a new route.
 */
public final class GrappleMovement {
  private static final float RELEASE_DISTANCE = 48;
  private static final float LOST_HOOK_TIME = .4f;

  public record Move(Vec3f direction, float speed) implements ReachMovementOutput.Move {}

  public record Output(
      MovementResult result,
      Optional<GrappleMovement.Move> movement,
      int actionFlags,
      int movementFlags,
      GrappleState grapple,
      boolean clearReachDeadline,
      List<String> commands)
      implements FlaggedReachMovementOutput {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(movement);
      Objects.requireNonNull(grapple);
      commands = List.copyOf(commands);
    }
  }

  private final BotCollision bsp;
  private final MovementObstruction obstruction;
  private final ToIntFunction<Vec3f> pointArea;
  private final BooleanSupplier hookVisible;
  private final MovementSettings settings;

  /**
   * @param hookVisible whether a missile entity of the grapple weapon is currently in the world
   *     ({@code GrappleState}'s entity scan)
   */
  public GrappleMovement(
      BotCollision bsp,
      MovementObstruction obstruction,
      ToIntFunction<Vec3f> pointArea,
      BooleanSupplier hookVisible,
      MovementSettings settings) {
    this.bsp = Objects.requireNonNull(bsp);
    this.obstruction = Objects.requireNonNull(obstruction);
    this.pointArea = Objects.requireNonNull(pointArea);
    this.hookVisible = Objects.requireNonNull(hookVisible);
    this.settings = Objects.requireNonNull(settings);
  }

  /**
   * @param area the bot's current area, for the obstruction check
   * @param reachArea the area the reachability was selected in
   */
  public Output execute(
      MovementInit input,
      int flags,
      int area,
      int reachArea,
      Reachability reach,
      GrappleState state,
      float time) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(state);
    if (!reach.is(TravelType.GRAPPLE_HOOK))
      throw new IllegalArgumentException("Non-grapple reach execution");
    var commands = new ArrayList<String>();
    if ((flags & BotMovement.GRAPPLE_RESET) != 0) {
      if (settings.offhandGrapple()) commands.add(settings.grappleOff());
      return new Output(
          MovementResult.CLEAR,
          Optional.empty(),
          0,
          flags & ~BotMovement.ACTIVE_GRAPPLE,
          state,
          false,
          commands);
    }
    int weapon = 0, resultFlags = 0;
    if (!settings.offhandGrapple()) {
      weapon = settings.grapple();
      resultFlags |= MovementResult.MOVEMENT_WEAPON;
    }
    if ((flags & BotMovement.ACTIVE_GRAPPLE) != 0) {
      int hook = (flags & BotMovement.GRAPPLE_PULL) != 0 ? 2 : hookVisible.getAsBoolean() ? 1 : 0;
      Vec3f toEnd = reach.end().subtract(input.origin());
      float distance = new Vec3f(toEnd.x(), toEnd.y(), 0).length();
      var result = new MovementResult(0, 0, 0, 0, 0, resultFlags, weapon, Vec3f.ZERO, Vec3f.ZERO);
      boolean release = false;
      // Close to the grapple end and no longer getting closer, or no hook at all for a while.
      if (hook != 0 && distance < RELEASE_DISTANCE) {
        release = state.lastDistance() - distance < 1;
      } else if (hook == 0 || (hook == 2 && state.lastDistance() - distance < 1)) {
        release = state.visibleTime() < time - LOST_HOOK_TIME;
      } else state = new GrappleState(time, state.lastDistance());
      if (release) {
        if (settings.offhandGrapple()) commands.add(settings.grappleOff());
        int released = (flags & ~BotMovement.ACTIVE_GRAPPLE) | BotMovement.GRAPPLE_RESET;
        return new Output(result, Optional.empty(), 0, released, state, true, commands);
      }
      int actions = settings.offhandGrapple() ? 0 : ActionFlags.ATTACK;
      return new Output(
          result,
          Optional.empty(),
          actions,
          flags,
          new GrappleState(state.visibleTime(), distance),
          false,
          commands);
    }

    state = new GrappleState(time, state.lastDistance());
    Vec3f toStart = reach.start().subtract(input.origin());
    if ((flags & BotMovement.SWIMMING) == 0) toStart = new Vec3f(toStart.x(), toStart.y(), 0);
    Vec3f eye = input.origin().add(input.viewOffset());
    Vec3f view = ViewAngles.of(reach.end().subtract(eye));
    float squared = toStart.lengthSquared();
    float inverse = squared == 0 ? 0 : 1 / (float) Math.sqrt(squared);
    float distance = squared * inverse;
    Vec3f direction = toStart.scale(inverse);
    resultFlags |= MovementResult.MOVEMENT_VIEW;
    int actions = 0;
    Optional<Move> movement = Optional.empty();
    int blocked = 0, blockEntity = 0, movementFlags = flags;
    Vec3f resultDirection = Vec3f.ZERO;
    if (distance < 5
        && Math.abs(ViewAngles.difference(view.x(), input.viewAngles().x())) < 2
        && Math.abs(ViewAngles.difference(view.y(), input.viewAngles().y())) < 2) {
      var path = bsp.trace(eye, reach.end(), input.entity(), Contents.SOLID);
      if (reach.end().subtract(path.endPosition()).length() > 16) {
        var failed =
            new MovementResult(
                1, MovementResult.BAD_GRAPPLE_PATH, 0, 0, 0, resultFlags, weapon, Vec3f.ZERO, view);
        return new Output(failed, Optional.empty(), 0, flags, state, false, commands);
      }
      if (settings.offhandGrapple()) commands.add(settings.grappleOn());
      else actions |= ActionFlags.ATTACK;
      movementFlags |= BotMovement.ACTIVE_GRAPPLE;
      state = new GrappleState(state.visibleTime(), 999999);
    } else {
      float speed = distance < 70 ? 300 - (300 - 4 * distance) : 400;
      var check = obstruction.check(input, area, direction);
      blocked = check.blocked();
      blockEntity = check.blockEntity();
      resultFlags |= check.flags();
      movement = Optional.of(new Move(direction, speed));
      resultDirection = direction;
    }
    // Leaving the reachability's area before the hook is out ends the reachability.
    int current = pointArea.applyAsInt(input.origin());
    boolean leftArea = current != 0 && current != reachArea;
    var result =
        new MovementResult(
            0, 0, blocked, blockEntity, 0, resultFlags, weapon, resultDirection, view);
    return new Output(result, movement, actions, movementFlags, state, leftArea, commands);
  }
}

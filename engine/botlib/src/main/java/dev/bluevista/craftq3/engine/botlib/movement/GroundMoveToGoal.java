package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.engine.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.engine.botlib.goal.Goal;
import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Move-to-goal orchestration ({@code BotMoveToGoal}): classify the bot (on ground, on a mover,
 * swimming, against a ladder or airborne), keep or select a reachability towards the goal, and run
 * the travel executor registered for its {@link TravelType}. A travel type without an executor, or
 * an executor that cannot handle the situation, yields a failed result and one log line instead of
 * an exception, so a bot never ends the match.
 */
public final class GroundMoveToGoal {
  private static final Log LOG = Log.get(GroundMoveToGoal.class);

  /** Bytes of the caller's result an early return writes (the six cleared integer fields). */
  public static final int PREFIX_BYTES = 24;

  /** Bytes a travel executor's complete result writes. */
  public static final int FULL_BYTES = 52;

  public record Command(Vec3f direction, float speed, int actionFlags) {
    public Command {
      Objects.requireNonNull(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid ground movement command speed");
    }
  }

  /**
   * One move-to-goal result. {@code writtenBytes} is {@link #PREFIX_BYTES} when the original
   * returns early after clearing only its first six fields, leaving the caller's weapon, direction
   * and view untouched, and {@link #FULL_BYTES} otherwise. An absent command preserves the previous
   * EA_Move. {@code degraded} names the unsupported situation, if any, that produced a failure
   * instead of a travel command.
   */
  public record Output(
      MovementResult result,
      int writtenBytes,
      Optional<Command> command,
      int actionFlags,
      Optional<Vec3f> view,
      OptionalInt weapon,
      List<String> commands,
      Optional<String> degraded) {
    public Output {
      Objects.requireNonNull(result);
      Objects.requireNonNull(command);
      Objects.requireNonNull(view);
      Objects.requireNonNull(weapon);
      Objects.requireNonNull(degraded);
      commands = List.copyOf(commands);
      if (view.isPresent() && !view.orElseThrow().isFinite())
        throw new IllegalArgumentException("Non-finite movement view");
      if (command.isPresent() && command.orElseThrow().actionFlags() != actionFlags)
        throw new IllegalArgumentException("Movement command and output action flags disagree");
      if (writtenBytes != PREFIX_BYTES && writtenBytes != FULL_BYTES)
        throw new IllegalArgumentException("Invalid movement result write size");
    }
  }

  @FunctionalInterface
  public interface GroundContact {
    boolean onGround(Vec3f origin, int presence, int entity);
  }

  @FunctionalInterface
  public interface ReachTravel {
    ReachMovementOutput execute(
        MovementInit input, int flags, int area, AasWorld.Reachability reach);
  }

  /** The area argument is the stored source of the cached reach, not the current point area. */
  @FunctionalInterface
  public interface StatefulReachTravel {
    StatefulReachMovementOutput execute(
        MovementInit input,
        int flags,
        int reachArea,
        AasWorld.Reachability reach,
        int lastReach,
        int jumpReach);
  }

  @FunctionalInterface
  public interface GoalTravel {
    ReachMovementOutput execute(MovementInit input, int flags, int area, Vec3f goal);
  }

  /**
   * Everything move-to-goal borrows. Build it with {@link #builder}; the travel maps default to
   * empty (those travel types then fail softly), movers to {@link MoverModels#NONE}, ladders to
   * never touching one, and the grapple and liquid goal executors to absent.
   */
  public record Config(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3f> fuzzyArea,
      AasMovementRoutes routes,
      BotCollision bsp,
      GroundContact groundContact,
      MoverModels movers,
      IntUnaryOperator entityModel,
      Predicate<Vec3f> againstLadder,
      ReachTravel walk,
      GoalTravel goalTravel,
      Optional<GoalTravel> liquidGoalTravel,
      Map<TravelType, ReachTravel> groundTravel,
      Map<TravelType, ReachTravel> airTravel,
      Map<TravelType, StatefulReachTravel> statefulGroundTravel,
      Map<TravelType, StatefulReachTravel> statefulAirTravel,
      Optional<GrappleMovement> grapple,
      MovementSettings settings) {
    public Config {
      Objects.requireNonNull(movement);
      Objects.requireNonNull(navigation);
      Objects.requireNonNull(fuzzyArea);
      Objects.requireNonNull(routes);
      Objects.requireNonNull(bsp);
      Objects.requireNonNull(groundContact);
      Objects.requireNonNull(movers);
      Objects.requireNonNull(entityModel);
      Objects.requireNonNull(againstLadder);
      Objects.requireNonNull(walk);
      Objects.requireNonNull(goalTravel);
      Objects.requireNonNull(liquidGoalTravel);
      Objects.requireNonNull(grapple);
      Objects.requireNonNull(settings);
      groundTravel = copy(groundTravel);
      airTravel = copy(airTravel);
      statefulGroundTravel = copy(statefulGroundTravel);
      statefulAirTravel = copy(statefulAirTravel);
    }

    private static <T> Map<TravelType, T> copy(Map<TravelType, T> map) {
      return map.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(map));
    }

    public static Builder builder(
        BotMovement movement,
        AasNavigation navigation,
        ToIntFunction<Vec3f> fuzzyArea,
        AasMovementRoutes routes,
        BotCollision bsp,
        GroundContact groundContact,
        ReachTravel walk,
        GoalTravel goalTravel) {
      return new Builder(
          movement, navigation, fuzzyArea, routes, bsp, groundContact, walk, goalTravel);
    }
  }

  public static final class Builder {
    private final BotMovement movement;
    private final AasNavigation navigation;
    private final ToIntFunction<Vec3f> fuzzyArea;
    private final AasMovementRoutes routes;
    private final BotCollision bsp;
    private final GroundContact groundContact;
    private final ReachTravel walk;
    private final GoalTravel goalTravel;
    private MoverModels movers = MoverModels.NONE;
    private IntUnaryOperator entityModel = entity -> 0;
    private Predicate<Vec3f> againstLadder = origin -> false;
    private GoalTravel liquidGoalTravel;
    private final Map<TravelType, ReachTravel> groundTravel = new EnumMap<>(TravelType.class);
    private final Map<TravelType, ReachTravel> airTravel = new EnumMap<>(TravelType.class);
    private final Map<TravelType, StatefulReachTravel> statefulGround =
        new EnumMap<>(TravelType.class);
    private final Map<TravelType, StatefulReachTravel> statefulAir =
        new EnumMap<>(TravelType.class);
    private GrappleMovement grapple;
    private MovementSettings settings = MovementSettings.DEFAULT;

    private Builder(
        BotMovement movement,
        AasNavigation navigation,
        ToIntFunction<Vec3f> fuzzyArea,
        AasMovementRoutes routes,
        BotCollision bsp,
        GroundContact groundContact,
        ReachTravel walk,
        GoalTravel goalTravel) {
      this.movement = movement;
      this.navigation = navigation;
      this.fuzzyArea = fuzzyArea;
      this.routes = routes;
      this.bsp = bsp;
      this.groundContact = groundContact;
      this.walk = walk;
      this.goalTravel = goalTravel;
    }

    /** Mover model types and the entity-to-model lookup ({@code AAS_EntityModelindex}). */
    public Builder movers(MoverModels models, IntUnaryOperator entityModel) {
      this.movers = Objects.requireNonNull(models);
      this.entityModel = Objects.requireNonNull(entityModel);
      return this;
    }

    public Builder againstLadder(Predicate<Vec3f> ladder) {
      this.againstLadder = Objects.requireNonNull(ladder);
      return this;
    }

    public Builder liquidGoalTravel(GoalTravel travel) {
      this.liquidGoalTravel = Objects.requireNonNull(travel);
      return this;
    }

    public Builder ground(TravelType type, ReachTravel travel) {
      groundTravel.put(type, Objects.requireNonNull(travel));
      return this;
    }

    public Builder air(TravelType type, ReachTravel travel) {
      airTravel.put(type, Objects.requireNonNull(travel));
      return this;
    }

    public Builder groundAndAir(TravelType type, ReachTravel travel) {
      return ground(type, travel).air(type, travel);
    }

    public Builder ground(Map<TravelType, ReachTravel> travel) {
      groundTravel.putAll(travel);
      return this;
    }

    public Builder air(Map<TravelType, ReachTravel> travel) {
      airTravel.putAll(travel);
      return this;
    }

    public Builder statefulGround(TravelType type, StatefulReachTravel travel) {
      statefulGround.put(type, Objects.requireNonNull(travel));
      return this;
    }

    public Builder statefulAir(TravelType type, StatefulReachTravel travel) {
      statefulAir.put(type, Objects.requireNonNull(travel));
      return this;
    }

    public Builder grapple(GrappleMovement movement) {
      this.grapple = Objects.requireNonNull(movement);
      return this;
    }

    public Builder settings(MovementSettings value) {
      this.settings = Objects.requireNonNull(value);
      return this;
    }

    public Config config() {
      return new Config(
          movement,
          navigation,
          fuzzyArea,
          routes,
          bsp,
          groundContact,
          movers,
          entityModel,
          againstLadder,
          walk,
          goalTravel,
          Optional.ofNullable(liquidGoalTravel),
          groundTravel,
          airTravel,
          statefulGround,
          statefulAir,
          Optional.ofNullable(grapple),
          settings);
    }

    public GroundMoveToGoal build() {
      return new GroundMoveToGoal(config());
    }
  }

  private final Config config;
  private final AasWorld map;
  private final JumpPadContact jumpPadContacts;

  public GroundMoveToGoal(Config config) {
    this.config = Objects.requireNonNull(config);
    map = config.navigation().map();
    jumpPadContacts = new JumpPadContact(map);
  }

  /**
   * The original {@code BotReachabilityTime}: how long a bot may spend on a reachability before
   * selecting again.
   */
  public static float reachabilityTime(TravelType type) {
    return switch (type) {
      case WALK, CROUCH, BARRIER_JUMP, WALK_OFF_LEDGE, JUMP, SWIM, WATER_JUMP, TELEPORT -> 5;
      case LADDER, ROCKET_JUMP, BFG_JUMP -> 6;
      case ELEVATOR, JUMP_PAD, FUNC_BOB -> 10;
      // The original prints an error for the unused types and allows 8 seconds.
      case GRAPPLE_HOOK, INVALID, DOUBLE_JUMP, RAMP_JUMP, STRAFE_JUMP -> 8;
    };
  }

  public Output execute(int handle, Goal goal, TravelPolicy policy, float time) {
    Objects.requireNonNull(goal);
    Objects.requireNonNull(policy);
    if (!Float.isFinite(time) || time < 0 || time > 1e9 || goal.area() < 0 || goal.area() >= 65536)
      throw new IllegalArgumentException("Invalid movement goal/time");
    var movement = config.movement();
    var snapshot =
        movement
            .snapshot(handle)
            .orElseThrow(() -> new IllegalArgumentException("Invalid movement state"));
    var input =
        snapshot
            .input()
            .orElseThrow(() -> new IllegalStateException("Movement state is not initialized"));
    if ((input.presenceType() != 2 && input.presenceType() != 4)
        || input.entity() < 0
        || input.entity() >= 1024)
      throw new IllegalArgumentException("Invalid ground movement input");
    var frame = new Frame(snapshot);
    Output output;
    try {
      output = evaluate(input, goal, policy, time, snapshot, frame);
    } catch (UnsupportedOperationException unsupported) {
      // An executor met a situation it cannot handle (a mover whose entity was never seen, a
      // run-up prediction that cannot complete): fail this move like a missing executor.
      output = degraded(MovementResult.failed(0), unsupported.getMessage(), List.of());
    }
    if (output.degraded().isPresent()) {
      String reason = output.degraded().orElseThrow();
      LOG.warnOnce(
          "move:" + reason,
          "Bot movement degraded: "
              + reason
              + " (client "
              + input.client()
              + ", area "
              + frame.area
              + ", reach "
              + frame.lastReach
              + ", goal area "
              + goal.area()
              + "); returning a failed move result");
    }
    movement.commitOperation(handle, frame.history(), frame.flags, frame.avoidance, frame.grapple);
    return output;
  }

  private Output evaluate(
      MovementInit input,
      Goal goal,
      TravelPolicy policy,
      float time,
      BotMovement.Snapshot snapshot,
      Frame frame) {
    var commands = new ArrayList<String>();
    resetGrapple(frame, commands);
    int moverFlags = 0;
    if (config.groundContact().onGround(input.origin(), input.presenceType(), input.entity()))
      frame.flags |= BotMovement.ON_GROUND;
    if ((frame.flags & BotMovement.ON_GROUND) != 0) {
      float top = input.presenceType() == 2 ? 32 : 8;
      var below =
          config
              .bsp()
              .trace(
                  input.origin(),
                  new Vec3f(-15, -15, -24),
                  new Vec3f(15, 15, top),
                  vertical(input.origin(), -3),
                  input.entity(),
                  Contents.PLAYER_SOLID);
      int entity = below.entity();
      if (!below.startSolid() && entity >= 0 && entity < 1022) {
        int model = config.entityModel().applyAsInt(entity);
        if (model >= 0 && model < MoverModels.MAX_MODELS) {
          var type = config.movers().type(model);
          switch (type) {
            case FUNC_PLAT, FUNC_BOB -> {
              var ride =
                  type == MoverModels.Type.FUNC_PLAT ? TravelType.ELEVATOR : TravelType.FUNC_BOB;
              var last = reachOrNull(frame.lastReach);
              boolean riding = last != null && last.is(ride) && (last.face() & 0xffff) == model;
              if (!riding) {
                int selected = config.movers().nextModelReachability(0, model);
                if (selected == 0) return prefix(obstacle(entity), commands);
                frame.lastReach = selected;
                frame.deadline =
                    time + reachabilityTime(map.reachabilities().get(selected).travel());
              }
              moverFlags |=
                  type == MoverModels.Type.FUNC_PLAT
                      ? MovementResult.ON_TOP_FUNC_PLAT
                      : MovementResult.ON_TOP_FUNC_BOB;
            }
            case FUNC_DOOR, FUNC_STATIC -> {
              // Doors and static models can bridge gaps; stand on them only inside a routable area.
              int area = config.fuzzyArea().applyAsInt(input.origin());
              if (!hasReachability(area)) return prefix(obstacle(entity), commands);
            }
            case NONE -> {
              return prefix(obstacle(entity), commands);
            }
          }
        }
      }
    }
    frame.flags &= ~(BotMovement.SWIMMING | BotMovement.AGAINST_LADDER);
    int contents = config.bsp().pointContents(vertical(input.origin(), -2));
    if ((contents & Contents.LIQUID) != 0) frame.flags |= BotMovement.SWIMMING;
    if (config.againstLadder().test(input.origin())) frame.flags |= BotMovement.AGAINST_LADDER;
    if ((frame.flags & (BotMovement.ON_GROUND | BotMovement.SWIMMING | BotMovement.AGAINST_LADDER))
        == 0) return airborne(input, frame, time, commands);
    return grounded(input, goal, policy, time, snapshot, frame, moverFlags, commands);
  }

  private Output airborne(MovementInit input, Frame frame, float time, List<String> commands) {
    var contact = jumpPadContacts.find(input.origin(), input.velocity(), 2 * input.thinkTime());
    if (contact.isPresent()) {
      frame.lastArea = contact.orElseThrow().area();
      frame.lastReach = contact.orElseThrow().reachability();
    }
    frame.lastOrigin = input.origin();
    if (frame.lastReach == 0) return prefix(MovementResult.CLEAR, commands);
    if (frame.lastReach < 0 || frame.lastReach >= map.reachabilities().size())
      return degraded(MovementResult.failed(0), "invalid cached airborne reachability", commands);
    var reach = map.reachabilities().get(frame.lastReach);
    Output output =
        switch (reach.travel()) {
          // Crouch and teleport travel need no airborne steering.
          case CROUCH, TELEPORT ->
              prefix(
                  new MovementResult(0, 0, 0, 0, reach.travelType(), 0, 0, Vec3f.ZERO, Vec3f.ZERO),
                  commands);
          case GRAPPLE_HOOK -> executeGrapple(input, frame, reach, 0, time, commands);
          default -> executeReach(input, frame, reach, 0, true, commands);
        };
    if (output.result().blocked() != 0) frame.deadline -= 10 * input.thinkTime();
    return output;
  }

  private Output grounded(
      MovementInit input,
      Goal goal,
      TravelPolicy policy,
      float time,
      BotMovement.Snapshot snapshot,
      Frame frame,
      int moverFlags,
      List<String> commands) {
    frame.area = config.fuzzyArea().applyAsInt(input.origin());
    if (frame.area < 0 || frame.area >= map.areas().size())
      throw new IllegalStateException("Fuzzy localization returned an invalid area");
    if (frame.area == 0)
      return prefix(
          new MovementResult(
              1, MovementResult.IN_SOLID_AREA, 1, 0, 0, moverFlags, 0, Vec3f.ZERO, Vec3f.ZERO),
          commands);
    if (frame.area == goal.area()) {
      boolean swimming = (frame.flags & BotMovement.SWIMMING) != 0;
      var travelInGoal = swimming ? config.liquidGoalTravel() : Optional.of(config.goalTravel());
      frame.lastReach = 0;
      frame.lastArea = 0;
      frame.lastGoal = goal.area();
      frame.lastOrigin = input.origin();
      if (travelInGoal.isEmpty())
        return degraded(MovementResult.failed(0), "swimming within the goal area", commands);
      var travel =
          travelInGoal.orElseThrow().execute(input, frame.flags, frame.area, goal.origin());
      return full(travel, travel.result().travelType(), 0, commands);
    }
    if (frame.lastReach < 0 || frame.lastReach >= map.reachabilities().size()) frame.lastReach = 0;
    boolean retained = false;
    if (frame.lastReach != 0) {
      var cached = map.reachabilities().get(frame.lastReach);
      int baseFlag = TravelFlags.forTravelType(cached.baseTravelType());
      boolean allowed = baseFlag != 0 && (policy.travelFlags() & baseFlag) != 0;
      retained =
          switch (cached.travel()) {
            case GRAPPLE_HOOK ->
                allowed && frame.deadline >= time && (frame.flags & BotMovement.GRAPPLE_RESET) == 0;
            case ELEVATOR, FUNC_BOB -> {
              boolean arrived = cached.area() == frame.area;
              if (moverFlags != 0 && allowed && !arrived) frame.deadline = time + 5;
              yield allowed && !arrived && frame.deadline >= time;
            }
            default ->
                allowed
                    && frame.deadline >= time
                    && frame.lastArea == frame.area
                    && frame.lastGoal == goal.area();
          };
    }
    int routeFlags = 0;
    if (!retained) {
      var old = frame.avoidance;
      var avoided =
          old.reachability() == 0
              ? List.<AasMovementRoutes.AvoidReach>of()
              : List.of(
                  new AasMovementRoutes.AvoidReach(
                      old.reachability(), old.expiresAt(), old.tries()));
      var context = new AasMovementRoutes.Context(frame.lastGoal, frame.lastArea, time, avoided);
      var selection =
          config
              .routes()
              .select(
                  frame.area, input.origin(), goal.area(), policy, context, snapshot.avoidSpots());
      frame.lastReach = selection.reachability();
      routeFlags = selection.flags();
      frame.reachArea = frame.area;
      frame.jumpReach = 0;
      frame.flags &= ~BotMovement.GRAPPLE_RESET;
      if (frame.lastReach != 0) {
        frame.deadline =
            time + reachabilityTime(map.reachabilities().get(frame.lastReach).travel());
        frame.avoidance = BotMovement.afterReachAttempt(old, frame.lastReach, 6, time);
      }
    }
    frame.lastArea = frame.area;
    frame.lastGoal = goal.area();
    frame.lastOrigin = input.origin();
    if (frame.lastReach == 0)
      return prefix(
          new MovementResult(1, 0, 0, 0, 0, routeFlags | moverFlags, 0, Vec3f.ZERO, Vec3f.ZERO),
          commands);
    var reach = map.reachabilities().get(frame.lastReach);
    // Native teleport completion writes a clear full result without an EA_Move call.
    // Preserve that absence: a zero-speed move would overwrite earlier accumulated input.
    if (reach.is(TravelType.TELEPORT) && (frame.flags & BotMovement.TELEPORTED) != 0)
      return output(
          new MovementResult(0, 0, 0, 0, reach.travelType(), routeFlags, 0, Vec3f.ZERO, Vec3f.ZERO),
          FULL_BYTES,
          Optional.empty(),
          0,
          Optional.empty(),
          OptionalInt.empty(),
          commands,
          Optional.empty());
    var output =
        reach.is(TravelType.GRAPPLE_HOOK)
            ? executeGrapple(input, frame, reach, routeFlags, time, commands)
            : executeReach(input, frame, reach, routeFlags, false, commands);
    if (output.result().blocked() != 0) frame.deadline -= 10 * input.thinkTime();
    return output;
  }

  /** {@code BotResetGrapple}: drop a hook that the current reachability no longer uses. */
  private void resetGrapple(Frame frame, List<String> commands) {
    var last = reachOrNull(frame.lastReach);
    if (last != null && last.is(TravelType.GRAPPLE_HOOK)) return;
    if ((frame.flags & BotMovement.ACTIVE_GRAPPLE) == 0 && frame.grapple.visibleTime() == 0) return;
    if (config.settings().offhandGrapple()) commands.add(config.settings().grappleOff());
    frame.flags &= ~BotMovement.ACTIVE_GRAPPLE;
    frame.grapple = new BotMovement.GrappleState(0, frame.grapple.lastDistance());
  }

  private Output executeGrapple(
      MovementInit input,
      Frame frame,
      AasWorld.Reachability reach,
      int routeFlags,
      float time,
      List<String> commands) {
    if (config.grapple().isEmpty())
      return degraded(failure(reach, routeFlags), "grapple hook travel", commands);
    var travel =
        config
            .grapple()
            .orElseThrow()
            .execute(input, frame.flags, frame.area, frame.reachArea, reach, frame.grapple, time);
    frame.flags = travel.movementFlags();
    frame.grapple = travel.grapple();
    if (travel.clearReachDeadline()) frame.deadline = 0;
    commands.addAll(travel.commands());
    return full(travel, reach.travelType(), routeFlags, commands);
  }

  private Output executeReach(
      MovementInit input,
      Frame frame,
      AasWorld.Reachability reach,
      int routeFlags,
      boolean airborne,
      List<String> commands) {
    TravelType type = reach.travel();
    var stateful =
        (airborne ? config.statefulAirTravel() : config.statefulGroundTravel()).get(type);
    if (stateful != null) {
      var travel =
          Objects.requireNonNull(
              stateful.execute(
                  input, frame.flags, frame.reachArea, reach, frame.lastReach, frame.jumpReach));
      if (travel.jumpReach() < 0 || travel.jumpReach() >= map.reachabilities().size())
        throw new IllegalArgumentException("Invalid jump reachability returned by executor");
      frame.jumpReach = travel.jumpReach();
      commands.addAll(travel.commands());
      return full(travel, reach.travelType(), routeFlags, commands);
    }
    var executor = (airborne ? config.airTravel() : config.groundTravel()).get(type);
    if (executor == null && (type == TravelType.WALK || type == TravelType.CROUCH))
      executor = config.walk();
    if (executor == null)
      return degraded(
          failure(reach, routeFlags),
          (airborne ? "airborne " : "") + "travel type " + type.name().toLowerCase(),
          commands);
    var travel = Objects.requireNonNull(executor.execute(input, frame.flags, frame.area, reach));
    if (travel instanceof FlaggedReachMovementOutput flagged) {
      frame.flags = flagged.movementFlags();
      if (flagged.clearReachDeadline()) frame.deadline = 0;
    }
    commands.addAll(travel.commands());
    return full(travel, reach.travelType(), routeFlags, commands);
  }

  private AasWorld.Reachability reachOrNull(int number) {
    return number > 0 && number < map.reachabilities().size()
        ? map.reachabilities().get(number)
        : null;
  }

  private boolean hasReachability(int area) {
    return area > 0
        && area < map.areaSettings().size()
        && map.areaSettings().get(area).reachabilityCount() > 0;
  }

  private static MovementResult obstacle(int entity) {
    return new MovementResult(
        0, 0, 1, entity, 0, MovementResult.ON_TOP_OF_OBSTACLE, 0, Vec3f.ZERO, Vec3f.ZERO);
  }

  private static MovementResult failure(AasWorld.Reachability reach, int routeFlags) {
    return new MovementResult(
        1, 0, 0, 0, reach.travelType(), routeFlags, 0, Vec3f.ZERO, Vec3f.ZERO);
  }

  private static Output prefix(MovementResult result, List<String> commands) {
    return output(
        result,
        PREFIX_BYTES,
        Optional.empty(),
        0,
        Optional.empty(),
        OptionalInt.empty(),
        commands,
        Optional.empty());
  }

  private static Output degraded(MovementResult result, String reason, List<String> commands) {
    return output(
        result,
        PREFIX_BYTES,
        Optional.empty(),
        0,
        Optional.empty(),
        OptionalInt.empty(),
        commands,
        Optional.of(reason));
  }

  private static Output output(
      MovementResult result,
      int bytes,
      Optional<Command> command,
      int actions,
      Optional<Vec3f> view,
      OptionalInt weapon,
      List<String> commands,
      Optional<String> degraded) {
    return new Output(result, bytes, command, actions, view, weapon, commands, degraded);
  }

  private static Output full(
      ReachMovementOutput travel, int travelType, int routeFlags, List<String> commands) {
    var r = travel.result();
    var result =
        new MovementResult(
            r.failure(),
            r.type(),
            r.blocked(),
            r.blockEntity(),
            travelType,
            r.flags() | routeFlags,
            r.weapon(),
            r.direction(),
            r.idealViewAngles());
    int actions = travel.actionFlags();
    var command =
        Objects.requireNonNull(travel.movement())
            .map(move -> new Command(move.direction(), move.speed(), actions));
    return output(
        result,
        FULL_BYTES,
        command,
        actions,
        travel.view(),
        travel.weapon(),
        commands,
        Optional.empty());
  }

  private static Vec3f vertical(Vec3f origin, float offset) {
    return new Vec3f(origin.x(), origin.y(), origin.z() + offset);
  }

  private static final class Frame {
    int area, lastArea, lastGoal, lastReach, reachArea, jumpReach, flags;
    float deadline;
    Vec3f lastOrigin;
    BotMovement.ReachAvoidance avoidance;
    BotMovement.GrappleState grapple;

    Frame(BotMovement.Snapshot snapshot) {
      var h = snapshot.history();
      area = h.area();
      lastArea = h.lastArea();
      lastGoal = h.lastGoalArea();
      lastReach = h.lastReachability();
      reachArea = h.reachArea();
      jumpReach = h.jumpReachability();
      deadline = h.reachDeadline();
      lastOrigin = h.lastOrigin();
      flags = snapshot.movementFlags();
      avoidance = snapshot.reachAvoidance();
      grapple = snapshot.grapple();
    }

    BotMovement.History history() {
      return new BotMovement.History(
          area, lastArea, lastGoal, lastReach, reachArea, jumpReach, deadline, lastOrigin);
    }
  }
}

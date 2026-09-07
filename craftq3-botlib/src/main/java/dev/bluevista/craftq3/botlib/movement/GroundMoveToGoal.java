package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.botlib.aas.TravelPolicy;
import dev.bluevista.craftq3.botlib.goal.Goal;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/** Verified dry-ground move-to-goal orchestration; unsupported travel never invents a command. */
public final class GroundMoveToGoal {
  /** Reproducible host diagnostics with a stable reason for bounded audit grouping. */
  public static final class UnsupportedMovement extends UnsupportedOperationException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    private UnsupportedMovement(
        String reason,
        int handle,
        MovementInit input,
        Goal goal,
        TravelPolicy policy,
        float time,
        BotMovement.Snapshot state,
        int selectedReach,
        int localizedArea,
        Throwable cause) {
      super(
          reason
              + "; handle="
              + handle
              + "; time="
              + time
              + "; selectedReach="
              + selectedReach
              + "; localizedArea="
              + localizedArea
              + "; input="
              + input
              + "; goal="
              + goal
              + "; policy="
              + policy
              + "; history="
              + state.history()
              + "; effectiveFlags="
              + state.movementFlags()
              + "; avoidance="
              + state.reachAvoidance()
              + "; avoidSpots="
              + state.avoidSpots(),
          cause);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }

  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final MovementResult CLEAR = new MovementResult(0, 0, 0, 0, 0, 0, 0, ZERO, ZERO);

  public record Command(Vec3 direction, float speed, int actionFlags) {
    public Command {
      direction = MovementAbi.vector(direction);
      if (!Float.isFinite(speed) || speed < 0 || speed > 400)
        throw new IllegalArgumentException("Invalid ground movement command speed");
    }
  }

  /** Native early returns write six integers and leave the remaining 28 bytes untouched. */
  public record Output(
      MovementResult result,
      int writtenBytes,
      Optional<Command> command,
      int actionFlags,
      Optional<Vec3> view,
      OptionalInt weapon) {
    public Output(
        MovementResult result, int writtenBytes, Optional<Command> command, int actionFlags) {
      this(result, writtenBytes, command, actionFlags, Optional.empty(), OptionalInt.empty());
    }

    /** Compatibility constructor: existing commands already carry all their action bits. */
    public Output(MovementResult result, int writtenBytes, Optional<Command> command) {
      this(
          result,
          writtenBytes,
          command,
          Objects.requireNonNull(command).map(Command::actionFlags).orElse(0));
    }

    public Output {
      Objects.requireNonNull(result);
      command = Objects.requireNonNull(command);
      view = Objects.requireNonNull(view).map(MovementAbi::vector);
      weapon = Objects.requireNonNull(weapon);
      if (command.isPresent() && command.orElseThrow().actionFlags() != actionFlags)
        throw new IllegalArgumentException("Movement command and output action flags disagree");
      if (writtenBytes != 24 && writtenBytes != MovementResult.BYTE_SIZE)
        throw new IllegalArgumentException("Invalid movement result write size");
    }

    /**
     * Validates the full ABI output range without interpreting the caller's previous float bits.
     */
    public void writeTo(ByteBuffer bytes, int offset) {
      var target = MovementAbi.slice(bytes, offset, MovementResult.BYTE_SIZE);
      if (writtenBytes == MovementResult.BYTE_SIZE) result.writeTo(target, 0);
      else {
        target.putInt(0, result.failure());
        target.putInt(4, result.type());
        target.putInt(8, result.blocked());
        target.putInt(12, result.blockEntity());
        target.putInt(16, result.travelType());
        target.putInt(20, result.flags());
      }
    }
  }

  @FunctionalInterface
  public interface GroundContact {
    boolean onGround(Vec3 origin, int presence, int entity);
  }

  @FunctionalInterface
  public interface ReachTravel {
    ReachMovementOutput execute(MovementInit input, int flags, int area, AasMap.Reachability reach);
  }

  /** The area argument is the stored source of the cached reach, not the current point area. */
  @FunctionalInterface
  public interface StatefulReachTravel {
    StatefulReachMovementOutput execute(
        MovementInit input,
        int flags,
        int reachArea,
        AasMap.Reachability reach,
        int lastReach,
        int jumpReach);
  }

  /**
   * Resolves the native-observed reach for an entity identified as a moving platform. Zero means
   * its model has no platform reach and must use the ordinary blocked-entity result.
   */
  @FunctionalInterface
  public interface PlatformReach {
    int select(int entity, int previousReach);
  }

  @FunctionalInterface
  public interface GoalTravel {
    ReachMovementOutput execute(MovementInit input, int flags, int area, Vec3 goal);
  }

  private final BotMovement movement;
  private final AasNavigation navigation;
  private final AasMap map;
  private final ToIntFunction<Vec3> fuzzyArea;
  private final AasMovementRoutes routes;
  private final TraceWorld bsp;
  private final GroundContact groundContact;
  private final IntPredicate movingPlatform;
  private final PlatformReach platformReach;
  private final ReachTravel reachTravel;
  private final GoalTravel goalTravel;
  private final GoalTravel liquidGoalTravel;
  private final Map<Integer, ReachTravel> additionalGroundTravel;
  private final Map<Integer, ReachTravel> additionalAirTravel;
  private final JumpPadContact jumpPadContacts;
  private final Map<Integer, StatefulReachTravel> statefulGroundTravel;
  private final Map<Integer, StatefulReachTravel> statefulAirTravel;

  public GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      GroundReachMovement ground) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        ground,
        Map.of());
  }

  /**
   * Optional verified ground executors: BARRIERJUMP (4), WALKOFFLEDGE (7), SWIM (8), WATERJUMP (9),
   * TELEPORT (10) and JUMPPAD (18).
   */
  public GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      GroundReachMovement ground,
      Map<Integer, ReachTravel> additionalGroundTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        Objects.requireNonNull(ground)::execute,
        ground::moveInGoalArea,
        additionalGroundTravel);
  }

  GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      ReachTravel reachTravel,
      GoalTravel goalTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        reachTravel,
        goalTravel,
        Map.of());
  }

  GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      ReachTravel reachTravel,
      GoalTravel goalTravel,
      Map<Integer, ReachTravel> additionalGroundTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        reachTravel,
        goalTravel,
        additionalGroundTravel,
        Map.of());
  }

  /**
   * Ground and airborne executors are registered independently. Ground accepts 4, 7, 8, 9, 10 and
   * 18; airborne accepts 4, 7, 8, 9 and 18. Teleport airborne completion needs no executor.
   */
  public GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      GroundReachMovement ground,
      Map<Integer, ReachTravel> additionalGroundTravel,
      Map<Integer, ReachTravel> additionalAirTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        Objects.requireNonNull(ground)::execute,
        ground::moveInGoalArea,
        additionalGroundTravel,
        additionalAirTravel);
  }

  GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      ReachTravel reachTravel,
      GoalTravel goalTravel,
      Map<Integer, ReachTravel> additionalGroundTravel,
      Map<Integer, ReachTravel> additionalAirTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        reachTravel,
        goalTravel,
        additionalGroundTravel,
        additionalAirTravel,
        null);
  }

  GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      ReachTravel reachTravel,
      GoalTravel goalTravel,
      Map<Integer, ReachTravel> additionalGroundTravel,
      Map<Integer, ReachTravel> additionalAirTravel,
      GoalTravel liquidGoalTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        reachTravel,
        goalTravel,
        additionalGroundTravel,
        additionalAirTravel,
        liquidGoalTravel,
        Map.of(),
        Map.of());
  }

  GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      ReachTravel reachTravel,
      GoalTravel goalTravel,
      Map<Integer, ReachTravel> additionalGroundTravel,
      Map<Integer, ReachTravel> additionalAirTravel,
      GoalTravel liquidGoalTravel,
      Map<Integer, StatefulReachTravel> statefulGroundTravel,
      Map<Integer, StatefulReachTravel> statefulAirTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        reachTravel,
        goalTravel,
        additionalGroundTravel,
        additionalAirTravel,
        liquidGoalTravel,
        statefulGroundTravel,
        statefulAirTravel,
        null);
  }

  public GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      ReachTravel reachTravel,
      GoalTravel goalTravel,
      Map<Integer, ReachTravel> additionalGroundTravel,
      Map<Integer, ReachTravel> additionalAirTravel,
      GoalTravel liquidGoalTravel,
      Map<Integer, StatefulReachTravel> statefulGroundTravel,
      Map<Integer, StatefulReachTravel> statefulAirTravel,
      PlatformReach platformReach) {
    this.platformReach = platformReach;
    this.statefulGroundTravel = Map.copyOf(statefulGroundTravel);
    this.statefulAirTravel = Map.copyOf(statefulAirTravel);
    if (this.statefulGroundTravel.keySet().stream()
            .anyMatch(type -> type != 5 && type != 12 && type != 13)
        || this.statefulAirTravel.keySet().stream()
            .anyMatch(type -> type != 5 && type != 12 && type != 13))
      throw new IllegalArgumentException("Unverified stateful reach travel type");
    this.movement = Objects.requireNonNull(movement);
    this.navigation = Objects.requireNonNull(navigation);
    map = navigation.map();
    this.fuzzyArea = Objects.requireNonNull(fuzzyArea);
    this.routes = Objects.requireNonNull(routes);
    this.bsp = Objects.requireNonNull(bsp);
    this.groundContact = Objects.requireNonNull(groundContact);
    this.movingPlatform = Objects.requireNonNull(movingPlatform);
    this.reachTravel = Objects.requireNonNull(reachTravel);
    this.goalTravel = Objects.requireNonNull(goalTravel);
    this.liquidGoalTravel = liquidGoalTravel;
    jumpPadContacts = new JumpPadContact(map);
    this.additionalAirTravel = Map.copyOf(additionalAirTravel);
    if (this.additionalAirTravel.keySet().stream()
        .anyMatch(
            type -> type != 4 && type != 7 && type != 8 && type != 9 && type != 18 && type != 19))
      throw new IllegalArgumentException("Unverified additional airborne travel type");
    this.additionalGroundTravel = Map.copyOf(additionalGroundTravel);
    if (this.additionalGroundTravel.keySet().stream()
        .anyMatch(
            type ->
                type != 4
                    && type != 7
                    && type != 8
                    && type != 9
                    && type != 10
                    && type != 18
                    && type != 19))
      throw new IllegalArgumentException("Unverified additional ground travel type");
  }

  /** Adds verified same-area swimming; liquid reach executors remain explicitly registered. */
  public GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      GroundReachMovement ground,
      Map<Integer, ReachTravel> additionalGroundTravel,
      Map<Integer, ReachTravel> additionalAirTravel,
      GoalTravel liquidGoalTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        Objects.requireNonNull(ground)::execute,
        ground::moveInGoalArea,
        additionalGroundTravel,
        additionalAirTravel,
        Objects.requireNonNull(liquidGoalTravel));
  }

  /** Adds independently registered, verified stateful jump entry and completion executors. */
  public GroundMoveToGoal(
      BotMovement movement,
      AasNavigation navigation,
      ToIntFunction<Vec3> fuzzyArea,
      AasMovementRoutes routes,
      TraceWorld bsp,
      GroundContact groundContact,
      IntPredicate movingPlatform,
      GroundReachMovement ground,
      Map<Integer, ReachTravel> additionalGroundTravel,
      Map<Integer, ReachTravel> additionalAirTravel,
      GoalTravel liquidGoalTravel,
      Map<Integer, StatefulReachTravel> statefulGroundTravel,
      Map<Integer, StatefulReachTravel> statefulAirTravel) {
    this(
        movement,
        navigation,
        fuzzyArea,
        routes,
        bsp,
        groundContact,
        movingPlatform,
        Objects.requireNonNull(ground)::execute,
        ground::moveInGoalArea,
        additionalGroundTravel,
        additionalAirTravel,
        liquidGoalTravel,
        statefulGroundTravel,
        statefulAirTravel);
  }

  public Output execute(int handle, Goal goal, TravelPolicy policy, float time) {
    Objects.requireNonNull(goal);
    Objects.requireNonNull(policy);
    if (!Float.isFinite(time) || time < 0 || time > 1e9 || goal.area() < 0 || goal.area() >= 65536)
      throw new IllegalArgumentException("Invalid movement goal/time");
    // Keep state ownership stable across the borrowed queries; none of the native-like state
    // writes are published until supported execution has completed successfully.
    synchronized (movement) {
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
        throw new UnsupportedMovement(
            unsupported.getMessage(),
            handle,
            input,
            goal,
            policy,
            time,
            snapshot,
            frame.lastReach,
            frame.area,
            unsupported);
      }
      movement.commitOperation(handle, frame.history(), frame.flags, frame.avoidance);
      return output;
    }
  }

  private Output evaluate(
      MovementInit input,
      Goal goal,
      TravelPolicy policy,
      float time,
      BotMovement.Snapshot snapshot,
      Frame frame) {
    int platformEntity = -1;
    if (groundContact.onGround(input.origin(), input.presenceType(), input.entity()))
      frame.flags |= 2;
    if ((frame.flags & 2) != 0) {
      float top = input.presenceType() == 2 ? 32 : 8;
      var below =
          bsp.trace(
              new TraceRequest(
                  input.origin(),
                  vertical(input.origin(), -3),
                  new Vec3(-15, -15, -24),
                  new Vec3(15, 15, top),
                  65537,
                  input.entity()));
      int entity = below.hit().map(hit -> hit.entity()).orElse(1023);
      if (!below.startSolid() && entity >= 0 && entity < 1022) {
        if (movingPlatform.test(entity)) {
          if (platformReach == null)
            throw new UnsupportedOperationException(
                "Move-to-goal while standing on a moving platform");
          int selected = platformReach.select(entity, frame.lastReach);
          if (selected == 0)
            return prefix(new MovementResult(0, 0, 1, entity, 0, 32, 0, ZERO, ZERO));
          if (selected < 0
              || selected >= map.reachabilities().size()
              || map.reachabilities().get(selected).baseTravelType() != 19)
            throw new IllegalArgumentException("Invalid bobbing platform reach " + selected);
          if (selected != frame.lastReach) {
            frame.lastReach = selected;
            frame.deadline = time + 10;
          }
          platformEntity = entity;
        } else return prefix(new MovementResult(0, 0, 1, entity, 0, 32, 0, ZERO, ZERO));
      }
    }
    frame.flags &= ~(4 | 8 | 128);
    int contents = bsp.pointContents(vertical(input.origin(), -2), -1, -1);
    if ((contents & 56) != 0) {
      if (liquidGoalTravel == null)
        throw new UnsupportedOperationException("Liquid move-to-goal execution");
      frame.flags |= 4;
    }
    int pointArea = navigation.pointArea(input.origin());
    if (pointArea != 0 && (map.areaSettings().get(pointArea).flags() & 2) != 0)
      throw new UnsupportedOperationException("Move-to-goal ladder contact queries");
    if ((frame.flags & (2 | 4)) == 0) {
      var contact = jumpPadContacts.find(input.origin(), input.velocity());
      if (contact.isPresent()) {
        frame.lastArea = contact.orElseThrow().area();
        frame.lastReach = contact.orElseThrow().reachability();
      }
      if (frame.lastReach != 0) {
        if (frame.lastReach < 0 || frame.lastReach >= map.reachabilities().size())
          throw new IllegalArgumentException("Invalid cached airborne reachability");
        var reach = map.reachabilities().get(frame.lastReach);
        if (reach.baseTravelType() != 10
            && !additionalAirTravel.containsKey(reach.baseTravelType())
            && !statefulAirTravel.containsKey(reach.baseTravelType())) requireBasicGround(reach);
        Output output =
            (reach.baseTravelType() == 3 || reach.baseTravelType() == 10)
                ? prefix(new MovementResult(0, 0, 0, 0, reach.travelType(), 0, 0, ZERO, ZERO))
                : executeReach(input, frame, reach, 0, true);
        if (output.result().blocked() != 0) frame.deadline -= 1;
        frame.lastOrigin = input.origin();
        return output;
      }
      frame.lastOrigin = input.origin();
      return prefix(CLEAR);
    }
    frame.area = fuzzyArea.applyAsInt(input.origin());
    if (frame.area < 0 || frame.area >= map.areas().size())
      throw new IllegalStateException("Fuzzy localization returned an invalid area");
    if (frame.area == 0)
      return prefix(new MovementResult(1, 8, 1, 0, 0, platformEntity >= 0 ? 64 : 0, 0, ZERO, ZERO));
    if (frame.area == goal.area()) {
      var selectedGoalTravel = (frame.flags & 4) == 0 ? goalTravel : liquidGoalTravel;
      var travel = selectedGoalTravel.execute(input, frame.flags, frame.area, goal.origin());
      frame.lastReach = 0;
      frame.lastArea = 0;
      frame.lastGoal = goal.area();
      frame.lastOrigin = input.origin();
      return full(travel, travel.result().travelType(), 0);
    }
    if (frame.lastReach < 0 || frame.lastReach >= map.reachabilities().size())
      throw new IllegalArgumentException("Invalid cached movement reachability");
    boolean retained = false;
    if (frame.lastReach != 0) {
      var cached = map.reachabilities().get(frame.lastReach);
      int baseFlag = TravelFlags.forTravelType(cached.baseTravelType());
      boolean allowed = baseFlag != 0 && (policy.travelFlags() & baseFlag) != 0;
      boolean bobbing = cached.baseTravelType() == 19;
      boolean arrived = bobbing && cached.area() == frame.area;
      if (platformEntity >= 0 && allowed && !arrived) frame.deadline = time + 5;
      retained =
          allowed
              && !arrived
              && frame.deadline >= time
              && (bobbing || (frame.lastArea == frame.area && frame.lastGoal == goal.area()));
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
          routes.select(
              frame.area, input.origin(), goal.area(), policy, context, snapshot.avoidSpots());
      frame.lastReach = selection.reachability();
      routeFlags = selection.flags();
      frame.reachArea = frame.area;
      frame.jumpReach = 0;
      if (frame.lastReach != 0) {
        requireGround(map.reachabilities().get(frame.lastReach));
        frame.deadline =
            time
                + switch (map.reachabilities().get(frame.lastReach).baseTravelType()) {
                  case 18, 19 -> 10;
                  case 12, 13 -> 6;
                  default -> 5;
                };
        frame.avoidance = BotMovement.afterReachAttempt(old, frame.lastReach, 6, time);
      }
    }
    frame.lastArea = frame.area;
    frame.lastGoal = goal.area();
    frame.lastOrigin = input.origin();
    if (frame.lastReach == 0)
      return prefix(
          new MovementResult(
              1, 0, 0, 0, 0, routeFlags | (platformEntity >= 0 ? 64 : 0), 0, ZERO, ZERO));
    var reach = map.reachabilities().get(frame.lastReach);
    requireGround(reach);
    // Native teleport completion writes a clear full result without an EA_Move call.
    // Preserve that absence: a zero-speed move would overwrite earlier accumulated input.
    if (reach.baseTravelType() == 10 && (frame.flags & 32) != 0)
      return new Output(
          new MovementResult(0, 0, 0, 0, reach.travelType(), routeFlags, 0, ZERO, ZERO),
          MovementResult.BYTE_SIZE,
          Optional.empty());
    var output = executeReach(input, frame, reach, routeFlags, false);
    if (output.result().blocked() != 0) frame.deadline -= 1;
    return output;
  }

  private Output executeReach(
      MovementInit input,
      Frame frame,
      AasMap.Reachability reach,
      int routeFlags,
      boolean airborne) {
    var stateful =
        (airborne ? statefulAirTravel : statefulGroundTravel).get(reach.baseTravelType());
    if (stateful != null) {
      var travel =
          Objects.requireNonNull(
              stateful.execute(
                  input, frame.flags, frame.reachArea, reach, frame.lastReach, frame.jumpReach));
      Output output = full(travel, reach.travelType(), routeFlags);
      if (travel.jumpReach() < 0 || travel.jumpReach() >= map.reachabilities().size())
        throw new IllegalArgumentException("Invalid jump reachability returned by executor");
      frame.jumpReach = travel.jumpReach();
      return output;
    }
    var executor =
        (airborne ? additionalAirTravel : additionalGroundTravel)
            .getOrDefault(reach.baseTravelType(), reachTravel);
    var travel = Objects.requireNonNull(executor.execute(input, frame.flags, frame.area, reach));
    var output = full(travel, reach.travelType(), routeFlags);
    if (travel instanceof FlaggedReachMovementOutput flagged) {
      frame.flags = flagged.movementFlags();
      if (flagged.clearReachDeadline()) frame.deadline = 0;
    }
    return output;
  }

  private void requireGround(AasMap.Reachability reach) {
    if (!additionalGroundTravel.containsKey(reach.baseTravelType())
        && !statefulGroundTravel.containsKey(reach.baseTravelType())) requireBasicGround(reach);
  }

  private static void requireBasicGround(AasMap.Reachability reach) {
    if (reach.baseTravelType() != 2 && reach.baseTravelType() != 3)
      throw new UnsupportedOperationException("Move-to-goal travel type " + reach.baseTravelType());
  }

  private static Output prefix(MovementResult result) {
    return new Output(result, 24, Optional.empty());
  }

  private static Output full(ReachMovementOutput travel, int travelType, int routeFlags) {
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
    return new Output(
        result, MovementResult.BYTE_SIZE, command, actions, travel.view(), travel.weapon());
  }

  private static Vec3 vertical(Vec3 origin, float offset) {
    return new Vec3((float) origin.x(), (float) origin.y(), (float) origin.z() + offset);
  }

  private static final class Frame {
    int area, lastArea, lastGoal, lastReach, reachArea, jumpReach, flags;
    float deadline;
    Vec3 lastOrigin;
    BotMovement.ReachAvoidance avoidance;

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
    }

    BotMovement.History history() {
      return new BotMovement.History(
          area, lastArea, lastGoal, lastReach, reachArea, jumpReach, deadline, lastOrigin);
    }
  }
}

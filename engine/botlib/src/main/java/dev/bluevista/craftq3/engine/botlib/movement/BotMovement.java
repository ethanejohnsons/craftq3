package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Bounded movement input, flags and navigation avoidance; travel execution is a separate service.
 */
public final class BotMovement implements AutoCloseable {
  public static final int MAX_HANDLES = 64, MAX_AVOID_SPOTS = 32;
  public static final int AVOID_CLEAR = 0, AVOID_ALWAYS = 1, AVOID_DONT_BLOCK = 2;

  // Movement state flags (MFL_*).
  public static final int BARRIER_JUMP = 1;
  public static final int ON_GROUND = 2;
  public static final int SWIMMING = 4;
  public static final int AGAINST_LADDER = 8;
  public static final int WATER_JUMP = 16;
  public static final int TELEPORTED = 32;
  public static final int GRAPPLE_PULL = 64;
  public static final int ACTIVE_GRAPPLE = 128;
  public static final int GRAPPLE_RESET = 256;
  public static final int WALK = 512;

  /** The flags {@code BotInitMoveState} copies from the caller's input. */
  private static final int INPUT_FLAGS = ON_GROUND | WATER_JUMP | TELEPORTED | GRAPPLE_PULL | WALK;

  /** The single native timed reachability slot. A cleared slot has three zero fields. */
  public record ReachAvoidance(int reachability, float expiresAt, int tries) {
    public static final ReachAvoidance EMPTY = new ReachAvoidance(0, 0, 0);

    public ReachAvoidance {
      if (reachability < 0 || !Float.isFinite(expiresAt) || tries < 0)
        throw new IllegalArgumentException("Invalid movement reach avoidance");
    }
  }

  public record AvoidSpot(Vec3f origin, float radius, int type) {
    public AvoidSpot {
      Objects.requireNonNull(origin);
      if (!Float.isFinite(radius)) throw new IllegalArgumentException("Nonfinite avoid radius");
    }
  }

  /**
   * Navigation history survives input initialization and is cleared by a full state reset. Build
   * values from {@link #EMPTY} with the {@code with*} methods to keep each field named.
   */
  public record History(
      int area,
      int lastArea,
      int lastGoalArea,
      int lastReachability,
      int reachArea,
      int jumpReachability,
      float reachDeadline,
      Vec3f lastOrigin) {
    public static final History EMPTY = new History(0, 0, 0, 0, 0, 0, 0, Vec3f.ZERO);

    public History {
      if (area < 0
          || area >= 65536
          || lastArea < 0
          || lastArea >= 65536
          || lastGoalArea < 0
          || lastGoalArea >= 65536
          || reachArea < 0
          || reachArea >= 65536
          || lastReachability < 0
          || lastReachability >= 1_048_576
          || jumpReachability < 0
          || jumpReachability >= 1_048_576
          || !Float.isFinite(reachDeadline))
        throw new IllegalArgumentException("Invalid movement navigation history");
      Objects.requireNonNull(lastOrigin);
    }

    public History withArea(int value) {
      return new History(
          value,
          lastArea,
          lastGoalArea,
          lastReachability,
          reachArea,
          jumpReachability,
          reachDeadline,
          lastOrigin);
    }

    public History withLastArea(int value) {
      return new History(
          area,
          value,
          lastGoalArea,
          lastReachability,
          reachArea,
          jumpReachability,
          reachDeadline,
          lastOrigin);
    }

    public History withLastGoalArea(int value) {
      return new History(
          area,
          lastArea,
          value,
          lastReachability,
          reachArea,
          jumpReachability,
          reachDeadline,
          lastOrigin);
    }

    public History withLastReachability(int value) {
      return new History(
          area,
          lastArea,
          lastGoalArea,
          value,
          reachArea,
          jumpReachability,
          reachDeadline,
          lastOrigin);
    }

    public History withReachArea(int value) {
      return new History(
          area,
          lastArea,
          lastGoalArea,
          lastReachability,
          value,
          jumpReachability,
          reachDeadline,
          lastOrigin);
    }

    public History withJumpReachability(int value) {
      return new History(
          area,
          lastArea,
          lastGoalArea,
          lastReachability,
          reachArea,
          value,
          reachDeadline,
          lastOrigin);
    }

    public History withReachDeadline(float value) {
      return new History(
          area,
          lastArea,
          lastGoalArea,
          lastReachability,
          reachArea,
          jumpReachability,
          value,
          lastOrigin);
    }

    public History withLastOrigin(Vec3f value) {
      return new History(
          area,
          lastArea,
          lastGoalArea,
          lastReachability,
          reachArea,
          jumpReachability,
          reachDeadline,
          value);
    }
  }

  /**
   * Grapple hook bookkeeping: when the hook or its missile was last seen and the last horizontal
   * distance to the grapple point, used to detect a hook that stopped pulling.
   */
  public record GrappleState(float visibleTime, float lastDistance) {
    public static final GrappleState EMPTY = new GrappleState(0, 0);

    public GrappleState {
      if (!Float.isFinite(visibleTime) || !Float.isFinite(lastDistance))
        throw new IllegalArgumentException("Invalid grapple state");
    }
  }

  public record Snapshot(
      Optional<MovementInit> input,
      List<AvoidSpot> avoidSpots,
      ReachAvoidance reachAvoidance,
      int movementFlags,
      History history,
      GrappleState grapple) {
    public Snapshot {
      input = Objects.requireNonNull(input);
      avoidSpots = List.copyOf(avoidSpots);
      reachAvoidance = Objects.requireNonNull(reachAvoidance);
      history = Objects.requireNonNull(history);
      grapple = Objects.requireNonNull(grapple);
    }
  }

  private static final class State {
    MovementInit input;
    ReachAvoidance reachAvoidance = ReachAvoidance.EMPTY;
    History history = History.EMPTY;
    GrappleState grapple = GrappleState.EMPTY;
    int movementFlags;
    final List<AvoidSpot> spots = new ArrayList<>(MAX_AVOID_SPOTS);
  }

  private final State[] states = new State[MAX_HANDLES + 1];
  private final Consumer<String> diagnostics;
  private boolean closed;

  public BotMovement(Consumer<String> diagnostics) {
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  public int allocate() {
    open();
    for (int handle = 1; handle < states.length; handle++)
      if (states[handle] == null) {
        states[handle] = new State();
        return handle;
      }
    diagnostics.accept("Movement state handle limit reached");
    return 0;
  }

  public void free(int handle) {
    if (state(handle) != null) states[handle] = null;
  }

  public void reset(int handle) {
    if (state(handle) != null) states[handle] = new State();
  }

  public void initialize(int handle, MovementInit input) {
    Objects.requireNonNull(input);
    var state = state(handle);
    if (state != null) {
      state.input = input;
      state.movementFlags =
          (state.movementFlags & ~INPUT_FLAGS) | (input.moveFlags() & INPUT_FLAGS);
    }
  }

  /** Called by verified travel providers; input, timed avoidance and avoid spots remain owned. */
  public void updateHistory(int handle, History history) {
    Objects.requireNonNull(history);
    var state = state(handle);
    if (state != null) state.history = history;
  }

  /** Stores derived movement flags without reapplying the narrower player-input flag mask. */
  public void updateMovementFlags(int handle, int flags) {
    var state = state(handle);
    if (state != null) state.movementFlags = flags;
  }

  /** A repeated link refreshes the slot; another link replaces it only after strict expiry. */
  public void recordReachAttempt(int handle, int reachability, float duration, float time) {
    if (reachability < 1
        || !Float.isFinite(time)
        || time < 0
        || !Float.isFinite(duration)
        || !Float.isFinite(time + duration))
      throw new IllegalArgumentException("Invalid movement reach attempt");
    var state = state(handle);
    if (state == null) return;
    state.reachAvoidance = afterReachAttempt(state.reachAvoidance, reachability, duration, time);
  }

  static ReachAvoidance afterReachAttempt(
      ReachAvoidance old, int reachability, float duration, float time) {
    if (old.reachability() == reachability || old.expiresAt() < time) {
      int tries =
          old.reachability() == reachability && old.expiresAt() > time
              ? Math.incrementExact(old.tries())
              : 1;
      return new ReachAvoidance(reachability, time + duration, tries);
    }
    return old;
  }

  void commitOperation(
      int handle, History history, int flags, ReachAvoidance avoidance, GrappleState grapple) {
    Objects.requireNonNull(history);
    Objects.requireNonNull(avoidance);
    Objects.requireNonNull(grapple);
    var state = state(handle);
    if (state != null) {
      state.history = history;
      state.movementFlags = flags;
      state.reachAvoidance = avoidance;
      state.grapple = grapple;
    }
  }

  public void resetAvoidReach(int handle) {
    var state = state(handle);
    if (state != null) state.reachAvoidance = ReachAvoidance.EMPTY;
  }

  /** Cancels a positive timeout and refunds one attempt; the link number remains available. */
  public void resetLastAvoidReach(int handle) {
    var state = state(handle);
    if (state == null || state.reachAvoidance.expiresAt() <= 0) return;
    var old = state.reachAvoidance;
    state.reachAvoidance = new ReachAvoidance(old.reachability(), 0, Math.max(0, old.tries() - 1));
  }

  public void addAvoidSpot(int handle, AvoidSpot spot) {
    Objects.requireNonNull(spot);
    var state = state(handle);
    if (state == null) return;
    if (spot.type() == AVOID_CLEAR) state.spots.clear();
    else if (state.spots.size() < MAX_AVOID_SPOTS) state.spots.add(spot);
  }

  public void clearAvoidSpots(int handle) {
    var state = state(handle);
    if (state != null) state.spots.clear();
  }

  public Optional<Snapshot> snapshot(int handle) {
    var state = state(handle);
    return state == null
        ? Optional.empty()
        : Optional.of(
            new Snapshot(
                Optional.ofNullable(state.input),
                state.spots,
                state.reachAvoidance,
                state.movementFlags,
                state.history,
                state.grapple));
  }

  public int allocatedCount() {
    open();
    int count = 0;
    for (var state : states) if (state != null) count++;
    return count;
  }

  private State state(int handle) {
    open();
    if (handle < 1 || handle >= states.length || states[handle] == null) {
      diagnostics.accept("Invalid movement state handle " + handle);
      return null;
    }
    return states[handle];
  }

  private void open() {
    if (closed) throw new IllegalStateException("Movement service is closed");
  }

  @Override
  public void close() {
    Arrays.fill(states, null);
    closed = true;
  }
}

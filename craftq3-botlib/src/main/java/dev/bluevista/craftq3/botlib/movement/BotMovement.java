package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;
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
  private static final int INPUT_FLAGS = 2 | 16 | 32 | 64 | 512;

  /** The single native timed reachability slot. A cleared slot has three zero fields. */
  public record ReachAvoidance(int reachability, float expiresAt, int tries) {
    public static final ReachAvoidance EMPTY = new ReachAvoidance(0, 0, 0);

    public ReachAvoidance {
      if (reachability < 0 || !Float.isFinite(expiresAt) || tries < 0)
        throw new IllegalArgumentException("Invalid movement reach avoidance");
    }
  }

  public record AvoidSpot(Vec3 origin, float radius, int type) {
    public AvoidSpot {
      origin = MovementAbi.vector(origin);
      if (!Float.isFinite(radius)) throw new IllegalArgumentException("Nonfinite avoid radius");
    }
  }

  /** Navigation history survives input initialization and is cleared by a full state reset. */
  public record History(
      int area,
      int lastArea,
      int lastGoalArea,
      int lastReachability,
      int reachArea,
      int jumpReachability,
      float reachDeadline,
      Vec3 lastOrigin) {
    public static final History EMPTY = new History(0, 0, 0, 0, 0, 0, 0, new Vec3(0, 0, 0));

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
      lastOrigin = MovementAbi.vector(lastOrigin);
    }
  }

  public record Snapshot(
      Optional<MovementInit> input,
      List<AvoidSpot> avoidSpots,
      ReachAvoidance reachAvoidance,
      int movementFlags,
      History history) {
    public Snapshot {
      input = Objects.requireNonNull(input);
      avoidSpots = List.copyOf(avoidSpots);
      reachAvoidance = Objects.requireNonNull(reachAvoidance);
      history = Objects.requireNonNull(history);
    }
  }

  private static final class State {
    MovementInit input;
    ReachAvoidance reachAvoidance = ReachAvoidance.EMPTY;
    History history = History.EMPTY;
    int movementFlags;
    final List<AvoidSpot> spots = new ArrayList<>(MAX_AVOID_SPOTS);
  }

  private final State[] states = new State[MAX_HANDLES + 1];
  private final Consumer<String> diagnostics;
  private boolean closed;

  public BotMovement(Consumer<String> diagnostics) {
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  public synchronized int allocate() {
    open();
    for (int handle = 1; handle < states.length; handle++)
      if (states[handle] == null) {
        states[handle] = new State();
        return handle;
      }
    diagnostics.accept("Movement state handle limit reached");
    return 0;
  }

  public synchronized void free(int handle) {
    if (state(handle) != null) states[handle] = null;
  }

  public synchronized void reset(int handle) {
    if (state(handle) != null) states[handle] = new State();
  }

  public synchronized void initialize(int handle, MovementInit input) {
    Objects.requireNonNull(input);
    var state = state(handle);
    if (state != null) {
      state.input = input;
      state.movementFlags =
          (state.movementFlags & ~INPUT_FLAGS) | (input.moveFlags() & INPUT_FLAGS);
    }
  }

  /** Called by verified travel providers; input, timed avoidance and avoid spots remain owned. */
  public synchronized void updateHistory(int handle, History history) {
    Objects.requireNonNull(history);
    var state = state(handle);
    if (state != null) state.history = history;
  }

  /** Stores derived movement flags without reapplying the narrower player-input flag mask. */
  public synchronized void updateMovementFlags(int handle, int flags) {
    var state = state(handle);
    if (state != null) state.movementFlags = flags;
  }

  /** A repeated link refreshes the slot; another link replaces it only after strict expiry. */
  public synchronized void recordReachAttempt(
      int handle, int reachability, float duration, float time) {
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

  synchronized void commitOperation(
      int handle, History history, int flags, ReachAvoidance avoidance) {
    Objects.requireNonNull(history);
    Objects.requireNonNull(avoidance);
    var state = state(handle);
    if (state != null) {
      state.history = history;
      state.movementFlags = flags;
      state.reachAvoidance = avoidance;
    }
  }

  public synchronized void resetAvoidReach(int handle) {
    var state = state(handle);
    if (state != null) state.reachAvoidance = ReachAvoidance.EMPTY;
  }

  /** Cancels a positive timeout and refunds one attempt; the link number remains available. */
  public synchronized void resetLastAvoidReach(int handle) {
    var state = state(handle);
    if (state == null || state.reachAvoidance.expiresAt() <= 0) return;
    var old = state.reachAvoidance;
    state.reachAvoidance = new ReachAvoidance(old.reachability(), 0, Math.max(0, old.tries() - 1));
  }

  public synchronized void addAvoidSpot(int handle, AvoidSpot spot) {
    Objects.requireNonNull(spot);
    var state = state(handle);
    if (state == null) return;
    if (spot.type() == AVOID_CLEAR) state.spots.clear();
    else if (state.spots.size() < MAX_AVOID_SPOTS) state.spots.add(spot);
  }

  public synchronized void clearAvoidSpots(int handle) {
    var state = state(handle);
    if (state != null) state.spots.clear();
  }

  public synchronized Optional<Snapshot> snapshot(int handle) {
    var state = state(handle);
    return state == null
        ? Optional.empty()
        : Optional.of(
            new Snapshot(
                Optional.ofNullable(state.input),
                state.spots,
                state.reachAvoidance,
                state.movementFlags,
                state.history));
  }

  public synchronized int allocatedCount() {
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
  public synchronized void close() {
    Arrays.fill(states, null);
    closed = true;
  }
}

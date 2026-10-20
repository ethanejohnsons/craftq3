package dev.bluevista.craftq3.engine.botlib.goal;

import dev.bluevista.craftq3.engine.botlib.weight.WeightConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;

/** Bounded goal stacks/avoid timers. Item selection and game decisions remain separate services. */
public final class BotGoals implements AutoCloseable {
  public static final int MAX_HANDLES = 64, STACK_CAPACITY = 7, MAX_AVOID_GOALS = 256;

  public record AvoidGoal(int number, float expiresAt) {}

  public record Snapshot(
      int client, List<Goal> stack, List<AvoidGoal> avoidGoals, boolean weightsLoaded) {
    public Snapshot {
      stack = List.copyOf(stack);
      avoidGoals = List.copyOf(avoidGoals);
    }
  }

  private static final class State {
    final int client;
    final List<Goal> stack = new ArrayList<>(STACK_CAPACITY);
    final int[] avoidNumbers = new int[MAX_AVOID_GOALS];
    final float[] avoidUntil = new float[MAX_AVOID_GOALS];
    WeightConfig weights;
    int lastReachableArea;

    State(int client) {
      this.client = client;
    }
  }

  private final State[] states = new State[MAX_HANDLES + 1];
  private final DoubleSupplier clock;
  private final IntFunction<OptionalDouble> automaticAvoidDuration;
  private final Consumer<String> diagnostics;
  private boolean closed;

  public BotGoals(DoubleSupplier clock, Consumer<String> diagnostics) {
    this(clock, number -> OptionalDouble.empty(), diagnostics);
  }

  public BotGoals(
      DoubleSupplier clock,
      IntFunction<OptionalDouble> automaticAvoidDuration,
      Consumer<String> diagnostics) {
    this.clock = Objects.requireNonNull(clock);
    this.automaticAvoidDuration = Objects.requireNonNull(automaticAvoidDuration);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  /** Allocates the lowest free positive slot; client is an opaque owner identifier. */
  public int allocate(int client) {
    open();
    for (int handle = 1; handle < states.length; handle++)
      if (states[handle] == null) {
        states[handle] = new State(client);
        return handle;
      }
    diagnostics.accept("Goal state handle limit reached");
    return 0;
  }

  public void free(int handle) {
    if (state(handle) != null) states[handle] = null;
  }

  public int allocatedCount() {
    open();
    int count = 0;
    for (var state : states) if (state != null) count++;
    return count;
  }

  /** Reset preserves the owner's item weight configuration. */
  public void reset(int handle) {
    var state = state(handle);
    if (state == null) return;
    state.stack.clear();
    clearAvoid(state);
  }

  public void resetAvoid(int handle) {
    var state = state(handle);
    if (state != null) clearAvoid(state);
  }

  public boolean push(int handle, Goal goal) {
    Objects.requireNonNull(goal);
    var state = state(handle);
    if (state == null) return false;
    if (state.stack.size() == STACK_CAPACITY) {
      diagnostics.accept("Goal stack overflow");
      return false;
    }
    state.stack.add(goal);
    return true;
  }

  public void pop(int handle) {
    var state = state(handle);
    if (state != null && !state.stack.isEmpty()) state.stack.removeLast();
  }

  public void empty(int handle) {
    var state = state(handle);
    if (state != null) state.stack.clear();
  }

  public Optional<Goal> top(int handle) {
    return fromTop(handle, 1);
  }

  public Optional<Goal> second(int handle) {
    return fromTop(handle, 2);
  }

  private Optional<Goal> fromTop(int handle, int offset) {
    var state = state(handle);
    return state == null || state.stack.size() < offset
        ? Optional.empty()
        : Optional.of(state.stack.get(state.stack.size() - offset));
  }

  public float avoidTime(int handle, int number) {
    var state = state(handle);
    if (state == null) return 0;
    float now = time();
    for (int i = 0; i < MAX_AVOID_GOALS; i++)
      if (state.avoidNumbers[i] == number) return Math.max(0, state.avoidUntil[i] - now);
    return 0;
  }

  public void setAvoidTime(int handle, int number, float duration) {
    var state = state(handle);
    if (state == null) return;
    if (!Float.isFinite(duration)) throw new IllegalArgumentException("Nonfinite avoid duration");
    if (duration < 0) {
      var automatic = automaticAvoidDuration.apply(number);
      if (automatic.isEmpty()) {
        diagnostics.accept("No item respawn time for automatic avoid goal " + number);
        return;
      }
      duration = (float) automatic.getAsDouble();
      if (!Float.isFinite(duration) || duration < 0)
        throw new IllegalArgumentException("Invalid automatic avoid duration");
    }
    float now = time(), expires = now + duration;
    if (!Float.isFinite(expires)) throw new IllegalArgumentException("Avoid expiry overflow");
    // An existing entry for the number wins over any expired slot, wherever it sits; a slot
    // is free only once it has strictly expired, so nothing is stored at time zero.
    int available = -1;
    for (int i = 0; i < MAX_AVOID_GOALS && available < 0; i++)
      if (state.avoidNumbers[i] == number) available = i;
    for (int i = 0; i < MAX_AVOID_GOALS && available < 0; i++)
      if (state.avoidUntil[i] < now) available = i;
    if (available < 0) return;
    state.avoidNumbers[available] = number;
    state.avoidUntil[available] = expires;
  }

  public void removeAvoid(int handle, int number) {
    var state = state(handle);
    if (state == null) return;
    for (int i = 0; i < MAX_AVOID_GOALS; i++)
      if (state.avoidNumbers[i] == number) {
        state.avoidUntil[i] = 0;
        return;
      }
  }

  public void weights(int handle, WeightConfig weights) {
    var state = state(handle);
    if (state != null) state.weights = Objects.requireNonNull(weights);
  }

  public Optional<WeightConfig> weights(int handle) {
    var state = state(handle);
    return state == null ? Optional.empty() : Optional.ofNullable(state.weights);
  }

  public void freeWeights(int handle) {
    var state = state(handle);
    if (state != null) state.weights = null;
  }

  int lastReachableArea(int handle) {
    var state = state(handle);
    return state == null ? 0 : state.lastReachableArea;
  }

  void lastReachableArea(int handle, int area) {
    if (area < 0) throw new IllegalArgumentException("Invalid goal reachability area");
    var state = state(handle);
    if (state != null) state.lastReachableArea = area;
  }

  public Optional<Snapshot> snapshot(int handle) {
    var state = state(handle);
    if (state == null) return Optional.empty();
    var avoids = new ArrayList<AvoidGoal>();
    for (int i = 0; i < MAX_AVOID_GOALS; i++)
      if (state.avoidUntil[i] != 0)
        avoids.add(new AvoidGoal(state.avoidNumbers[i], state.avoidUntil[i]));
    return Optional.of(new Snapshot(state.client, state.stack, avoids, state.weights != null));
  }

  private static void clearAvoid(State state) {
    Arrays.fill(state.avoidNumbers, 0);
    Arrays.fill(state.avoidUntil, 0);
  }

  private State state(int handle) {
    open();
    if (handle < 1 || handle >= states.length || states[handle] == null) {
      diagnostics.accept("Invalid goal state handle " + handle);
      return null;
    }
    return states[handle];
  }

  private float time() {
    float value = (float) clock.getAsDouble();
    if (!Float.isFinite(value) || value < 0)
      throw new IllegalArgumentException("Invalid goal clock");
    return value;
  }

  private void open() {
    if (closed) throw new IllegalStateException("Goal service is closed");
  }

  @Override
  public void close() {
    Arrays.fill(states, null);
    closed = true;
  }
}

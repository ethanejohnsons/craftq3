package dev.bluevista.craftq3.client;

import java.util.OptionalInt;

/**
 * Presentation time for one remote gamestate. The host supplies accepted snapshots in message order
 * and calls frame once per engine frame, after processing arrivals. Realtime is already the
 * engine's clock; this class does not multiply it by timescale. Calls are serialized by the host.
 *
 * <p>Reset on each new gamestate. Demo playback and local-server pause rules belong to other
 * clocks.
 */
public final class RemoteServerClock {
  private static final int SNAPSHOT_NOT_ACTIVE = 2;

  /** Immutable diagnostics; time is usable only after a frame activates the clock. */
  public record State(
      boolean active,
      boolean hasSnapshot,
      int snapshotTime,
      int snapshotFlags,
      boolean newSnapshot,
      int delta,
      int time,
      int previousSnapshotTime,
      boolean extrapolated) {}

  private State state = empty();

  public State state() {
    return state;
  }

  /** Announces a newly accepted, valid snapshot, even when its timestamp repeats. */
  public void snapshot(int serverTime, int flags) {
    if (flags < 0 || flags > 255) throw new IllegalArgumentException("Invalid snapshot flags");
    state =
        new State(
            state.active,
            true,
            serverTime,
            flags,
            true,
            state.delta,
            state.time,
            state.previousSnapshotTime,
            state.extrapolated);
  }

  /**
   * Chooses the current cgame time, then adjusts the offset for subsequent frames. Empty means the
   * connection is still primed. A backward remote snapshot requires a new gamestate/reset and fails
   * explicitly. Unrepresentable signed clock arithmetic and nonfinite timescale fail without
   * changing clock state.
   */
  public OptionalInt frame(int realtime, int timeNudge, float timescale) {
    if (!Float.isFinite(timescale)) throw new IllegalArgumentException("Nonfinite timescale");
    State before = state;
    if (!before.active && !before.newSnapshot) return OptionalInt.empty();
    boolean fresh = before.newSnapshot;
    int delta = before.delta;
    int previousTime = before.time;
    if (!before.active) {
      fresh = false;
      if ((before.snapshotFlags & SNAPSHOT_NOT_ACTIVE) != 0) {
        state =
            new State(
                false,
                before.hasSnapshot,
                before.snapshotTime,
                before.snapshotFlags,
                false,
                delta,
                before.time,
                before.previousSnapshotTime,
                before.extrapolated);
        return OptionalInt.empty();
      }
      delta = Math.subtractExact(before.snapshotTime, realtime);
      previousTime = before.snapshotTime;
    }
    if (before.snapshotTime < before.previousSnapshotTime)
      throw new IllegalStateException(
          "Remote snapshot time moved backward; reset for a new gamestate");
    int raw = Math.addExact(realtime, delta);
    int nudge = Math.clamp(timeNudge, -30, 30);
    int time = Math.max(previousTime, Math.subtractExact(raw, nudge));
    boolean extrapolated = before.extrapolated || (long) raw >= (long) before.snapshotTime - 5;
    if (fresh) {
      int target = Math.subtractExact(before.snapshotTime, realtime);
      long difference = Math.abs((long) target - delta);
      if (difference > 500) {
        delta = target;
        time = before.snapshotTime;
      } else if (difference > 100) {
        delta = Math.addExact(delta, target) >> 1;
      } else if (timescale == 0 || timescale == 1) {
        if (extrapolated) {
          delta = Math.subtractExact(delta, 2);
          extrapolated = false;
        } else {
          delta = Math.addExact(delta, 1);
        }
      }
    }
    state =
        new State(
            true,
            true,
            before.snapshotTime,
            before.snapshotFlags,
            false,
            delta,
            time,
            before.snapshotTime,
            extrapolated);
    return OptionalInt.of(time);
  }

  /** Discards all activation, extrapolation and offset history for a new gamestate. */
  public void reset() {
    state = empty();
  }

  private static State empty() {
    return new State(false, false, 0, 0, false, 0, 0, 0, false);
  }
}

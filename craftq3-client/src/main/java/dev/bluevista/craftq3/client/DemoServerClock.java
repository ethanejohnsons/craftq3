package dev.bluevista.craftq3.client;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;

/** Native-observed demo presentation time and bounded synchronous packet read-ahead. */
public final class DemoServerClock {
  public static final int MAX_READS_PER_FRAME = 4096;
  public static final int MAX_TIMEDEMO_DURATIONS = 4096;

  @FunctionalInterface
  public interface Reader {
    /** Reads one message and synchronously announces its gamestate, snapshots, or end. */
    void read() throws IOException;
  }

  public record State(
      boolean active,
      boolean ended,
      boolean firstFrameSkipped,
      boolean hasSnapshot,
      int snapshotTime,
      int snapshotFlags,
      boolean newSnapshot,
      int delta,
      int time,
      int oldTime,
      int previousSnapshotTime,
      boolean extrapolated) {}

  public record Timedemo(
      int frames,
      int start,
      int baseTime,
      int lastFrame,
      int minimumDuration,
      int maximumDuration) {}

  private boolean active, ended, firstFrameSkipped, hasSnapshot, newSnapshot, extrapolated;
  private int snapshotTime, snapshotFlags, delta, time, oldTime, previousSnapshotTime;
  private int timedemoFrames, timedemoStart, timedemoBase, timedemoLast, timedemoMin, timedemoMax;
  private final byte[] durations = new byte[MAX_TIMEDEMO_DURATIONS];
  private boolean framing;

  public State state() {
    return new State(
        active,
        ended,
        firstFrameSkipped,
        hasSnapshot,
        snapshotTime,
        snapshotFlags,
        newSnapshot,
        delta,
        time,
        oldTime,
        previousSnapshotTime,
        extrapolated);
  }

  public Timedemo timedemo() {
    return new Timedemo(
        timedemoFrames, timedemoStart, timedemoBase, timedemoLast, timedemoMin, timedemoMax);
  }

  /** Owned copy of the native 4,096-byte duration ring, whose first sample is frame two. */
  public byte[] timedemoDurations() {
    return durations.clone();
  }

  public void snapshot(int serverTime, int flags) {
    if (flags < 0 || flags > 255) throw new IllegalArgumentException("Invalid snapshot flags");
    if (ended) throw new IllegalStateException("Demo has ended");
    hasSnapshot = true;
    snapshotTime = serverTime;
    snapshotFlags = flags;
    newSnapshot = true;
  }

  /** Clears map-local time while retaining connection-level skip and timedemo statistics. */
  public void gamestate() {
    active = false;
    ended = false;
    hasSnapshot = false;
    newSnapshot = false;
    extrapolated = false;
    snapshotTime = snapshotFlags = delta = time = oldTime = previousSnapshotTime = 0;
  }

  public void end() {
    active = false;
    ended = true;
  }

  /** Starts a different demo, resetting both map and connection-level history. */
  public void reset() {
    if (framing) throw new IllegalStateException("Cannot reset a demo during its frame callback");
    gamestate();
    firstFrameSkipped = false;
    timedemoFrames = timedemoStart = timedemoBase = timedemoLast = timedemoMin = timedemoMax = 0;
    Arrays.fill(durations, (byte) 0);
  }

  /**
   * Realtime has already been scaled by the host. Wall milliseconds are used only for timedemo
   * statistics. The reader runs synchronously and may call snapshot, gamestate, or end; recursive
   * frames are rejected. Empty means primed, a gamestate transition, or end. I/O failures and
   * arithmetic/work-bound failures propagate; the owner must stop the failed playback.
   */
  public OptionalInt frame(
      int realtime,
      int timeNudge,
      float timescale,
      boolean freeze,
      boolean timedemo,
      int wallMillis,
      Reader reader)
      throws IOException {
    Objects.requireNonNull(reader);
    if (!Float.isFinite(timescale)) throw new IllegalArgumentException("Nonfinite timescale");
    if (framing) throw new IllegalStateException("Recursive demo frame");
    if (ended) return OptionalInt.empty();
    framing = true;
    try {
      int reads = 0;
      if (!active) {
        if (!firstFrameSkipped) {
          firstFrameSkipped = true;
          return OptionalInt.empty();
        }
        reader.read();
        reads++;
        if (ended) return OptionalInt.empty();
        if (newSnapshot) {
          newSnapshot = false;
          if ((snapshotFlags & 2) == 0) {
            delta = Math.subtractExact(snapshotTime, realtime);
            oldTime = snapshotTime;
            timedemoBase = snapshotTime;
            active = true;
          }
        }
        if (!active) return OptionalInt.empty();
      }
      if (!hasSnapshot) throw new IllegalStateException("Active demo has no valid snapshot");
      if (snapshotTime < previousSnapshotTime)
        throw new IllegalStateException(
            "Demo snapshot time moved backward without a new gamestate");
      previousSnapshotTime = snapshotTime;
      if (!freeze) {
        int raw = Math.addExact(realtime, delta);
        int adjusted = Math.subtractExact(raw, Math.clamp(timeNudge, -30, 30));
        time = Math.max(oldTime, adjusted);
        oldTime = time;
        extrapolated |= (long) raw >= (long) snapshotTime - 5;
      }
      // Demo arrivals consume the marker but never apply the live network offset correction.
      newSnapshot = false;
      if (timedemo) timedemoFrame(wallMillis);
      while (time >= snapshotTime) {
        if (reads == MAX_READS_PER_FRAME)
          throw new IllegalStateException("Demo read-ahead exceeded per-frame message limit");
        reader.read();
        reads++;
        if (!active) return OptionalInt.empty();
      }
      return OptionalInt.of(time);
    } finally {
      framing = false;
    }
  }

  private void timedemoFrame(int wallMillis) {
    if (timedemoStart == 0) {
      timedemoStart = wallMillis;
      timedemoLast = wallMillis;
      timedemoMin = Integer.MAX_VALUE;
      timedemoMax = 0;
    }
    if (timedemoFrames > 0) {
      int duration = Math.subtractExact(wallMillis, timedemoLast);
      timedemoMin = Math.min(timedemoMin, duration);
      timedemoMax = Math.max(timedemoMax, duration);
      durations[(timedemoFrames - 1) % MAX_TIMEDEMO_DURATIONS] = (byte) Math.min(duration, 255);
    }
    timedemoLast = wallMillis;
    timedemoFrames = Math.incrementExact(timedemoFrames);
    time = Math.addExact(timedemoBase, Math.multiplyExact(timedemoFrames, 50));
  }
}

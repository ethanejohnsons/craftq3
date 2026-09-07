package dev.bluevista.craftq3.client.demo;

import dev.bluevista.craftq3.client.DemoServerClock;
import dev.bluevista.craftq3.core.demo.DemoReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalInt;

/** Recorded connection, native demo clock, and host-scaled realtime; owns its input stream. */
public final class DemoPlayer implements AutoCloseable {
  private final DemoServerClock clock = new DemoServerClock();
  private final DemoPlayback playback;
  private int realtime;
  private double remainder;
  private boolean closed;

  public DemoPlayer(InputStream input) {
    playback =
        new DemoPlayback(
            input,
            new DemoPlayback.Listener() {
              public void gamestate() {
                clock.gamestate();
              }

              public void snapshot(int time, int flags) {
                clock.snapshot(time, flags);
              }

              public void end(DemoReader.End reason) {
                clock.end();
              }
            });
  }

  /** Locate the first gamestate before allocating level assets or calling original CG_INIT. */
  public void prime() throws IOException {
    ensureOpen();
    for (int i = 0; i < DemoServerClock.MAX_READS_PER_FRAME; i++) {
      playback.read();
      if (playback.gameState().isPresent()) return;
      if (playback.ended()) throw new IOException("Demo ended before its first gamestate");
    }
    throw new IOException("Demo gamestate startup work limit exceeded");
  }

  public OptionalInt frame(
      int elapsedMillis,
      int timeNudge,
      float timescale,
      boolean freeze,
      boolean timedemo,
      int wallMillis)
      throws IOException {
    ensureOpen();
    if (!Float.isFinite(timescale) || timescale < 0 || timescale > 1000)
      throw new IllegalArgumentException("Demo timescale must be finite and within0..1000");
    double scaled = Math.clamp(elapsedMillis, 0, 200) * (double) timescale + remainder;
    int elapsed = (int) scaled;
    realtime = Math.addExact(realtime, elapsed);
    remainder = scaled - elapsed;
    return clock.frame(
        realtime, timeNudge, timescale, freeze, timedemo, wallMillis, playback::read);
  }

  public DemoPlayback playback() {
    return playback;
  }

  public DemoServerClock clock() {
    return clock;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    playback.close();
  }

  private void ensureOpen() throws IOException {
    if (closed) throw new IOException("Demo player closed");
  }
}

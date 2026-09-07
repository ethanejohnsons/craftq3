package dev.bluevista.craftq3.fabric.render;

import java.nio.file.Path;
import java.util.Arrays;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.fabricmc.loader.api.FabricLoader;

/** Opt-in profiling of the real bridge loop, with startup samples excluded from timing totals. */
public final class BridgePerformanceSmoke {
  private static final boolean ENABLED =
      FabricLoader.getInstance().isDevelopmentEnvironment()
          && System.getProperty("craftq3.bridgeSmokeWorld") != null
          && !System.getProperty("craftq3.bridgeProfile", "").isEmpty();
  private static final long[] samples = new long[1500];
  private static int count;
  private static final long[] draws = new long[1500];
  private static int drawCount;
  private static Recording recording;

  private BridgePerformanceSmoke() {}

  public static boolean enabled() {
    return ENABLED;
  }

  public static long begin(int frame) {
    if (!enabled()) return 0;
    if (frame == 0) {
      count = drawCount = 0;
      try {
        recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setDestination(Path.of(System.getProperty("craftq3.bridgeProfile")));
        recording.setDumpOnExit(true);
        recording.start();
      } catch (Exception failure) {
        throw new IllegalStateException("Bridge profiler startup", failure);
      }
    }
    return System.nanoTime();
  }

  public static long beginDraw() {
    return enabled() ? System.nanoTime() : 0;
  }

  public static void endDraw(int frame, long start) {
    if (enabled() && frame >= 100 && drawCount < draws.length)
      draws[drawCount++] = System.nanoTime() - start;
  }

  private static void report(String phase, long[] values, int length) {
    if (length == 0) return;
    long[] sorted = Arrays.copyOf(values, length);
    Arrays.sort(sorted);
    System.out.printf(
        "BRIDGE PERFORMANCE phase=%s samples=%d meanMs=%.3f p50Ms=%.3f p95Ms=%.3f p99Ms=%.3f%n",
        phase,
        length,
        Arrays.stream(sorted).average().orElseThrow() / 1e6,
        sorted[length / 2] / 1e6,
        sorted[length * 95 / 100] / 1e6,
        sorted[length * 99 / 100] / 1e6);
  }

  public static void end(int frame, long start) {
    if (!enabled()) return;
    if (frame >= 100 && count < samples.length) samples[count++] = System.nanoTime() - start;
    if (frame == 1599) {
      report("draw", draws, drawCount);
      recording.stop();
      recording.close();
      report("simulation", samples, count);
    }
  }
}

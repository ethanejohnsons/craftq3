package dev.bluevista.craftq3.engine.headless;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One minute of real-time play on q3dm17 with three bots (server on {@code cq3-sim}), after a
 * warm-up with a map change and a restart. Heap in use after a full collection must not grow beyond
 * noise between the start and the end of the minute.
 */
@RealData
final class RealSoakTest {
  /** Retained heap growth tolerated over the soak. */
  static final long TOLERANCE = 24L << 20;

  @Test
  void aMinuteOfPlayRetainsNoMemory() throws Exception {
    HeadlessClient.Options options =
        HeadlessClient.options(RealDataRoot.require())
            .withManualClock(false)
            .withCommandLine(CommandLine.parse("+set vm_strictSyscalls 1 +set sv_pure 0"));
    try (HeadlessClient client = HeadlessClient.start(options)) {
      client.command("map q3dm1");
      assertTrue(client.runUntil(SessionState.LOCAL, 120_000), client.tail(20));
      client.command("map q3dm17");
      assertTrue(
          client.runUntil(
              () ->
                  client.state() == SessionState.LOCAL
                      && client
                          .session()
                          .serverStatus()
                          .map(LocalServer.Status::map)
                          .orElse("")
                          .equals("q3dm17"),
              120_000),
          client.tail(20));
      client.command("addbot sarge 3; addbot major 3; addbot visor 3");
      play(client, 10_000);
      client.command("map_restart 0");
      play(client, 10_000);
      long before = retained();
      List<Long> samples = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        play(client, 10_000);
        assertEquals(SessionState.LOCAL, client.state(), client.tail(20));
        samples.add(retained() >> 20);
      }
      long after = retained();
      System.out.printf(
          "soak: retained before %d MiB, after %d MiB, samples %s MiB, frames %d, bots %d%n",
          before >> 20,
          after >> 20,
          samples,
          client.frames().frames(),
          client.session().serverStatus().map(LocalServer.Status::bots).orElse(0));
      assertEquals(3, client.session().serverStatus().orElseThrow().bots(), client.tail(40));
      assertEquals(0, client.session().degradations(), client.tail(20));
      assertTrue(
          after - before < TOLERANCE,
          "heap grew by " + ((after - before) >> 20) + " MiB: " + samples);
    }
  }

  private static void play(HeadlessClient client, int millis) {
    long end = System.nanoTime() + millis * 1_000_000L;
    int i = 0;
    while (System.nanoTime() < end) {
      if (i % 120 == 0) client.key(KeyNames.UPARROW, true);
      if (i % 120 == 60) client.key(KeyNames.UPARROW, false);
      if (i % 40 == 0) client.key(KeyNames.MOUSE1, true);
      if (i % 40 == 3) client.key(KeyNames.MOUSE1, false);
      client.mouse(i % 50 < 25 ? 4 : -4, 0);
      client.frame();
      i++;
    }
  }

  private static long retained() throws InterruptedException {
    var memory = ManagementFactory.getMemoryMXBean();
    for (int i = 0; i < 3; i++) {
      System.gc();
      Thread.sleep(100);
    }
    return memory.getHeapMemoryUsage().getUsed();
  }
}

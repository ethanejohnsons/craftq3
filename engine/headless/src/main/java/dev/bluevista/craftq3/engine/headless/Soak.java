package dev.bluevista.craftq3.engine.headless;

import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.SessionState;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A long real-time soak: a local game on one map with bots, the player walking and firing, heap in
 * use after a full collection sampled at a fixed interval ({@code ./gradlew :engine:headless:soak
 * -Psoak.minutes=60 -Psoak.sampleMinutes=5 -Psoak.map=q3dm17 -Psoak.bots=4}). Writes {@code
 * heap.csv} and {@code summary.txt} to {@code --out}; exits 1 when the session left the game or
 * degraded.
 */
public final class Soak {
  private static final String[] BOTS = {"sarge", "major", "visor", "doom", "anarki", "hunter"};

  private Soak() {}

  public static void main(String[] args) throws Exception {
    double minutes = 60;
    double sampleMinutes = 5;
    String map = "q3dm17";
    int bots = 4;
    Path out = Path.of("build", "soak");
    for (int i = 0; i + 1 < args.length; i += 2) {
      switch (args[i]) {
        case "--minutes" -> minutes = Double.parseDouble(args[i + 1]);
        case "--sample-minutes" -> sampleMinutes = Double.parseDouble(args[i + 1]);
        case "--map" -> map = args[i + 1];
        case "--bots" -> bots = Integer.parseInt(args[i + 1]);
        case "--out" -> out = Path.of(args[i + 1]);
        default -> throw new IllegalArgumentException("unknown option " + args[i]);
      }
    }
    String root = System.getenv("CRAFTQ3_Q3ROOT");
    if (root == null || root.isBlank()) {
      System.err.println("soak: set CRAFTQ3_Q3ROOT to a Quake III installation");
      System.exit(2);
    }
    Files.createDirectories(out);
    System.exit(run(Path.of(root), map, bots, minutes, sampleMinutes, out));
  }

  static int run(Path install, String map, int bots, double minutes, double sampleMinutes, Path out)
      throws IOException, InterruptedException {
    HeadlessClient.Options options =
        HeadlessClient.options(install)
            .withManualClock(false)
            .withCommandLine(CommandLine.parse("+set sv_pure 0"));
    List<String> samples = new ArrayList<>();
    try (HeadlessClient client = HeadlessClient.start(options);
        PrintStream csv =
            new PrintStream(
                Files.newOutputStream(out.resolve("heap.csv")), true, StandardCharsets.UTF_8)) {
      client.command("map " + map);
      String wanted = map;
      if (!client.runUntil(
          () ->
              client.state() == SessionState.LOCAL
                  && client
                      .session()
                      .serverStatus()
                      .map(LocalServer.Status::map)
                      .orElse("")
                      .equalsIgnoreCase(wanted),
          180_000)) {
        System.err.println("soak: map never loaded\n" + client.tail(40));
        return 1;
      }
      StringBuilder add = new StringBuilder();
      for (int i = 0; i < bots; i++)
        add.append("addbot ").append(BOTS[i % BOTS.length]).append(" 3;");
      client.command(add.toString());
      csv.println("minute,retainedMiB,frames,bots,state,degradations");
      long start = System.nanoTime();
      long sampleNanos = (long) (sampleMinutes * 60e9);
      long endNanos = (long) (minutes * 60e9);
      long nextSample = 0;
      int frame = 0;
      while (true) {
        long elapsed = System.nanoTime() - start;
        if (elapsed >= nextSample) {
          String line =
              String.format(
                  "%.1f,%d,%d,%d,%s,%d",
                  elapsed / 60e9,
                  retained() >> 20,
                  client.frames().frames(),
                  client.session().serverStatus().map(LocalServer.Status::bots).orElse(0),
                  client.state(),
                  client.session().degradations());
          csv.println(line);
          System.out.println("soak: " + line);
          samples.add(line);
          nextSample += sampleNanos;
        }
        if (elapsed >= endNanos || client.state() != SessionState.LOCAL) break;
        if (frame % 120 == 0) client.key(KeyNames.UPARROW, true);
        if (frame % 120 == 60) client.key(KeyNames.UPARROW, false);
        if (frame % 40 == 0) client.key(KeyNames.MOUSE1, true);
        if (frame % 40 == 3) client.key(KeyNames.MOUSE1, false);
        client.mouse(frame % 50 < 25 ? 4 : -4, 0);
        client.frame();
        frame++;
      }
      boolean ok = client.state() == SessionState.LOCAL && client.session().degradations() == 0;
      String summary =
          (ok ? "PASS" : "FAIL")
              + " map="
              + map
              + " bots="
              + bots
              + " minutes="
              + minutes
              + "\n"
              + String.join("\n", samples)
              + "\n"
              + (ok ? "" : client.tail(40) + "\n");
      Files.writeString(out.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
      System.out.print("soak: " + summary);
      return ok ? 0 : 1;
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

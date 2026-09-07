import dev.bluevista.craftq3.client.RemoteServerClock;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.Random;

/** Authored stateful native differential; no network, game data, VM or window. */
final class AuditRemoteClock {
  private final Process nativeClock;
  private final BufferedReader read;
  private final BufferedWriter write;
  private final RemoteServerClock clock = new RemoteServerClock();
  private long operations;
  private long frames;
  private long rejected;

  AuditRemoteClock(Path binary) throws Exception {
    nativeClock =
        new ProcessBuilder(binary.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    read = new BufferedReader(new InputStreamReader(nativeClock.getInputStream()));
    write = new BufferedWriter(new OutputStreamWriter(nativeClock.getOutputStream()));
    if (!"CONSTANTS primed=7 active=8 notactive=2".equals(read.readLine()))
      throw new AssertionError("Unexpected native clock metadata");
  }

  private int[] ask(String command) throws Exception {
    write.write(command);
    write.newLine();
    write.flush();
    String line = read.readLine();
    if (line == null || !line.startsWith("STATE ")) throw new AssertionError(command + ": " + line);
    int[] result =
        Arrays.stream(line.substring(6).split(" ")).mapToInt(Integer::parseInt).toArray();
    while (!(line = read.readLine()).equals("END")) {
      if (!line.startsWith("ERROR ")) throw new AssertionError(command + ": " + line);
    }
    return result;
  }

  private void compare(String command, int[] actual, OptionalInt time, boolean failed) {
    RemoteServerClock.State s = clock.state();
    int[] expected = {
      s.active() ? 8 : 7,
      s.snapshotTime(),
      s.delta(),
      s.time(),
      s.time(),
      s.previousSnapshotTime(),
      s.newSnapshot() ? 1 : 0,
      s.extrapolated() ? 1 : 0
    };
    int[] observed = {
      actual[0], actual[2], actual[3], actual[4], actual[5], actual[6], actual[7], actual[8]
    };
    if (!Arrays.equals(expected, observed) || failed != (actual[13] != 0))
      throw new AssertionError(
          "Operation "
              + operations
              + " "
              + command
              + "\nJava "
              + Arrays.toString(expected)
              + " failed="
              + failed
              + "\nNative "
              + Arrays.toString(actual));
    if (time != null
        && !failed
        && (time.isPresent() != (actual[0] == 8)
            || time.isPresent() && time.getAsInt() != actual[4]))
      throw new AssertionError("Presentation result differs at " + command);
    operations++;
  }

  private void reset() throws Exception {
    clock.reset();
    compare("reset", ask("reset"), null, false);
  }

  private void snapshot(int time, int flags) throws Exception {
    String command = "snapshot " + time + " " + flags;
    clock.snapshot(time, flags);
    compare(command, ask(command), null, false);
  }

  private void frame(int realtime, int nudge, float scale) throws Exception {
    String command = "tick " + realtime + " " + nudge + " " + scale;
    boolean failed = false;
    OptionalInt time = null;
    try {
      time = clock.frame(realtime, nudge, scale);
    } catch (IllegalStateException expected) {
      failed = true;
      rejected++;
    }
    compare(command, ask(command), time, failed);
    frames++;
  }

  private void run(int sessions, int steps) throws Exception {
    Random random = new Random(0xC10C681L);
    int[] nudges = {
      Integer.MIN_VALUE, -100, -31, -30, -1, 0, 1, 29, 30, 31, 100, Integer.MAX_VALUE
    };
    float[] scales = {0, -0.0f, 1, 0.5f, 2, -1, Math.nextDown(1f), Math.nextUp(1f)};
    for (int session = 0; session < sessions; session++) {
      reset();
      int realtime = 3_000_000 + random.nextInt(1_000_000);
      int offset = 2_000_000 + random.nextInt(100_000);
      if ((session & 1) != 0) offset = -offset;
      frame(realtime, 0, 1);
      snapshot(realtime + offset, 2);
      frame(realtime, 0, 1);
      frame(realtime + 16, 0, 1);
      int latest = realtime + offset;
      snapshot(latest, 0);
      frame(realtime, nudges[random.nextInt(nudges.length)], scales[random.nextInt(scales.length)]);
      for (int step = 0; step < steps; step++) {
        realtime += random.nextInt(35);
        if (random.nextInt(60) == 0) realtime += random.nextInt(5000);
        if (random.nextInt(80) == 0) offset += random.nextInt(2001) - 1000;
        if (random.nextInt(3) == 0) {
          latest = Math.max(latest, realtime + offset - random.nextInt(200));
          snapshot(latest, random.nextInt(256));
          if (random.nextInt(4) == 0) {
            latest += random.nextInt(100);
            snapshot(latest, random.nextInt(256));
          }
        }
        frame(
            realtime, nudges[random.nextInt(nudges.length)], scales[random.nextInt(scales.length)]);
      }
      snapshot(clock.state().previousSnapshotTime() - 1, 0);
      frame(realtime, 0, 1);
    }
    write.close();
    if (nativeClock.waitFor() != 0) throw new AssertionError("Native clock failed");
    System.out.printf(
        "PASS %,d sessions, %,d operations, %,d frames, %,d backward-time rejections%n",
        sessions, operations, frames, rejected);
  }

  public static void main(String[] args) throws Exception {
    Path binary =
        Path.of(args.length > 0 ? args[0] : ".tools/remote-clock-oracle/probe").toAbsolutePath();
    int sessions = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
    int steps = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
    new AuditRemoteClock(binary).run(sessions, steps);
  }
}

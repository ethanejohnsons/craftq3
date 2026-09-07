import dev.bluevista.craftq3.client.DemoServerClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Authored public-operation replay. Reflection only seeds the same diagnostic fields as the C host. */
public final class DemoClockJavaOracle {
  private static final DemoServerClock clock = new DemoServerClock();
  private static final List<int[]> steps = new ArrayList<>();
  private static int cursor, reads, realtime;

  private static void set(String name, Object value) throws Exception {
    Field field = DemoServerClock.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(clock, value);
  }

  private static void read() {
    int[] row = cursor < steps.size() ? steps.get(cursor) : new int[]{3, 0, 0};
    cursor++;
    reads++;
    System.out.printf("READ %d %d %d%n", row[0], row[1], row[2]);
    switch (row[0]) {
      case 1 -> clock.snapshot(row[1], row[2]);
      case 2 -> clock.gamestate();
      case 3 -> clock.end();
      default -> { }
    }
  }

  private static int b(boolean value) { return value ? 1 : 0; }

  private static void show() {
    var s = clock.state();
    var t = clock.timedemo();
    System.out.printf("STATE %d %d %d %d %d %d %d %d %d %d 0 %d 0 0 0%n",
        s.active() ? 8 : s.ended() ? 1 : 7, realtime, s.snapshotTime(), s.delta(), s.time(),
        s.oldTime(), s.previousSnapshotTime(), b(s.newSnapshot()), b(s.extrapolated()), t.baseTime(), reads);
    System.out.printf("DEMO %d %d %d %d %d %d %d %d%n", b(s.firstFrameSkipped()),
        t.frames(), t.start(), t.baseTime(), t.lastFrame(), t.minimumDuration(), t.maximumDuration(), cursor);
    System.out.print("DURATIONS");
    byte[] durations = clock.timedemoDurations();
    for (int i = 0; i < 16; i++) System.out.print(" " + (durations[i] & 255));
    System.out.println("\nEND");
  }

  public static void main(String[] args) throws Exception {
    System.out.println("CONSTANTS primed=7 active=8 notactive=2");
    try (var input = new BufferedReader(new InputStreamReader(System.in))) {
      for (String line; (line = input.readLine()) != null;) {
        String[] words = line.split(" ");
        int[] a = new int[words.length];
        for (int i = 1; i < words.length; i++) {
          if (words[0].equals("run") && i == 6) break;
          a[i] = Integer.parseInt(words[i]);
        }
        reads = 0;
        switch (words[0]) {
          case "reset" -> { clock.reset(); steps.clear(); cursor = realtime = 0; }
          case "snapshot" -> clock.snapshot(a[1], a[2]);
          case "step" -> steps.add(new int[]{a[1], a[2], a[3]});
          case "seed" -> {
            set("active", a[1] == 8); set("ended", a[1] == 1); realtime = a[2];
            set("snapshotTime", a[3]); set("hasSnapshot", a[4] != 0); set("snapshotFlags", a[5]);
            set("newSnapshot", a[6] != 0); set("extrapolated", a[7] != 0); set("delta", a[8]);
            set("time", a[9]); set("oldTime", a[10]); set("previousSnapshotTime", a[11]);
          }
          case "demoseed" -> {
            set("firstFrameSkipped", a[1] != 0); set("timedemoFrames", a[2]);
            set("timedemoStart", a[3]); set("timedemoBase", a[4]); set("timedemoLast", a[5]);
            set("timedemoMin", a[6]); set("timedemoMax", a[7]);
          }
          case "run" -> {
            realtime = a[1];
            clock.frame(a[1], a[3], Float.parseFloat(words[6]), a[4] != 0, a[5] != 0, a[2], DemoClockJavaOracle::read);
          }
          default -> throw new IllegalArgumentException(line);
        }
        show();
      }
    }
  }
}

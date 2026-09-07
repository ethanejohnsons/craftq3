import dev.bluevista.craftq3.botlib.movement.BotAirControl;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Compares production airborne steering with an isolated native development oracle. */
class AuditAirControl {
  record Query(String command, BotAirControl.Result expected) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException("AuditAirControl <PK3> <oracle> [count]");
    int count = args.length == 3 ? Integer.parseInt(args[2]) : 10000;
    if (count < 1 || count > 100000) throw new IllegalArgumentException("Invalid query count");
    long started = System.nanoTime();
    var random = new Random(3194);
    var queries = new ArrayList<Query>();
    var commands = new StringBuilder();
    for (int i = 0; i < count; i++) {
      Vec3 origin = point(random, 1000),
          velocity = point(random, 500),
          target = add(origin, point(random, 300));
      if (i % 3 == 1) {
        origin = new Vec3(0, 0, 0);
        velocity = origin;
        target = new Vec3((float) (random.nextDouble() * 40), 0, -100);
      }
      if (i % 3 == 2) {
        origin = new Vec3(0, 0, 0);
        velocity = new Vec3(100, -50, (i % 8) * 80);
        target = new Vec3((i % 5) * 10, (i % 7) * 20, (i % 3) * 10);
      }
      String command = "air " + text(origin) + text(velocity) + text(target) + "\n";
      queries.add(new Query(command, BotAirControl.control(origin, velocity, target)));
      commands.append(command);
    }
    var process =
        new ProcessBuilder(args[1], args[0], "q3dm1")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    String output;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read =
          executor.submit(
              () -> {
                byte[] bytes = process.getInputStream().readNBytes(32 * 1024 * 1024 + 1);
                if (bytes.length > 32 * 1024 * 1024)
                  throw new IllegalStateException("Oracle output cap");
                return new String(bytes, StandardCharsets.US_ASCII);
              });
      try (var input = process.getOutputStream()) {
        input.write(commands.toString().getBytes(StandardCharsets.US_ASCII));
      }
      if (!process.waitFor(60, TimeUnit.SECONDS)) throw new IllegalStateException("Oracle timeout");
      output = read.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0) throw new IllegalStateException("Oracle failed");
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    var lines = output.lines().filter(s -> s.startsWith("AIRCONTROL ")).toList();
    if (lines.size() != count)
      throw new AssertionError("Oracle count " + lines.size() + "/" + count);
    int differences = 0;
    for (int i = 0; i < count; i++) {
      var tokens = lines.get(i).split(" ");
      var expected = queries.get(i).expected();
      float[] values = {
        (float) expected.direction().x(),
        (float) expected.direction().y(),
        (float) expected.direction().z(),
        expected.speed()
      };
      boolean bad = (Integer.parseInt(tokens[1]) != 0) != expected.success();
      for (int j = 0; j < 4; j++)
        bad |=
            Float.floatToIntBits(values[j])
                != Float.floatToIntBits(Float.parseFloat(tokens[j + 2]));
      if (bad && differences++ < 10)
        System.out.println("DIFF " + queries.get(i) + " native=" + lines.get(i));
    }
    System.out.printf(
        "Air control compared=%d differences=%d seconds=%.3f%n",
        count, differences, (System.nanoTime() - started) / 1e9);
    if (differences != 0) throw new AssertionError("Air control discrepancies");
  }

  private static Vec3 point(Random random, int magnitude) {
    return new Vec3(
        (float) ((random.nextDouble() * 2 - 1) * magnitude),
        (float) ((random.nextDouble() * 2 - 1) * magnitude),
        (float) ((random.nextDouble() * 2 - 1) * magnitude));
  }

  private static Vec3 add(Vec3 a, Vec3 b) {
    return new Vec3(
        (float) a.x() + (float) b.x(),
        (float) a.y() + (float) b.y(),
        (float) a.z() + (float) b.z());
  }

  private static String text(Vec3 point) {
    return (float) point.x() + " " + (float) point.y() + " " + (float) point.z() + " ";
  }
}

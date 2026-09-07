import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Original-map differential for liquid travel; the isolated oracle is never bundled. */
class AuditLiquidTravel {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static int traceCount;
  private static float sample;
  private static final int[] FLAGS = {0, 1, 2, 4, 6, 8, 16, 20, 32, 64, 128, 256, 512, 514, 1023};
  private static int forcedMode;
  private static final float[][] FRACTIONS = {
    {1, 1}, {.5f, 1}, {1, .5f}, {0, .5f}, {.5f, .5f}, {0, 0}
  };
  private static final int[][] ENTITIES = {
    {1023, 1023}, {15, 1023}, {1023, 17}, {15, 17}, {1022, 1022}, {19, 19}
  };
  private static final int[][] SOLIDS = {{0, 0}, {0, 0}, {0, 0}, {1, 0}, {0, 0}, {3, 3}};
  private static final TraceWorld CLEAR =
      new TraceWorld() {
        public TraceResult trace(TraceRequest r) {
          int n = traceCount++;
          if (n >= 2 || FRACTIONS[forcedMode][n] == 1) return TraceResult.clear(r);
          float fraction = FRACTIONS[forcedMode][n];
          return new TraceResult(
              fraction,
              new Vec3(
                  (float) r.start().x() + fraction * ((float) r.end().x() - (float) r.start().x()),
                  (float) r.start().y() + fraction * ((float) r.end().y() - (float) r.start().y()),
                  (float) r.start().z() + fraction * ((float) r.end().z() - (float) r.start().z())),
              (SOLIDS[forcedMode][n] & 1) != 0,
              (SOLIDS[forcedMode][n] & 2) != 0,
              Optional.of(
                  new TraceResult.Hit(
                      new TraceResult.Plane(new Vec3(0, 0, 1), 0),
                      1,
                      0,
                      ENTITIES[forcedMode][n],
                      -1,
                      -1,
                      -1,
                      -1,
                      "")));
        }

        public int pointContents(Vec3 p, int mask, int ignore) {
          return 0;
        }
      };

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 5)
      throw new IllegalArgumentException(
          "AuditLiquidTravel <PK3> <oracle> [queries/map] [map|all] [swim|entry|finish|goal]");
    boolean finishing = args.length == 5 && args[4].equals("finish");
    boolean goalArea = args.length == 5 && args[4].equals("goal");
    int travelType = goalArea || args.length == 5 && args[4].equals("swim") ? 8 : 9;
    if (args.length == 5
        && !finishing
        && !args[4].equals("entry")
        && !args[4].equals("swim")
        && !goalArea) throw new IllegalArgumentException("Unknown liquid travel mode");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 100;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid count");
    int maps = 0, total = 0, mismatches = 0;
    float maxError = 0;
    long begin = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        String name = entry.getName().substring(5, entry.getName().length() - 4);
        if (args.length >= 4 && !args[3].equals("all") && !args[3].equals(name)) continue;
        byte[] data;
        try (var in = zip.getInputStream(entry)) {
          data = in.readNBytes(AasReader.MAX_BYTES + 1);
        }
        var map = AasReader.read(data);
        var nav = new AasNavigation(map);
        var movement =
            new LiquidReachMovement(
                new MovementObstruction(
                    CLEAR,
                    a ->
                        a <= 0 || a >= map.areas().size()
                            ? 0
                            : map.areaSettings().get(a).reachabilityCount()),
                () -> sample);
        var reaches =
            map.reachabilities().stream().filter(r -> r.baseTravelType() == travelType).toList();
        if (reaches.isEmpty()) {
          System.out.println(name + " has no liquid reaches");
          continue;
        }
        var commands = new StringBuilder("clear\neaseed .25 -.5 .125 123\neaactions 8192\n");
        var expected = new ArrayList<LiquidReachMovement.Output>();
        var counts = new ArrayList<Integer>();
        var replays = new ArrayList<String>();
        Random random = new Random(139);
        for (int i = 0; i < count; i++) {
          var r = reaches.get(random.nextInt(reaches.size()));
          var start = r.start();
          Vec3 origin =
              start.add(
                  new Vec3(
                      random.nextFloat() * 128 - 64,
                      random.nextFloat() * 128 - 64,
                      random.nextFloat() * 32 - 16));
          if (i % 5 == 0) origin = start;
          int presence = i % 4 == 0 ? 4 : 2, flags = FLAGS[i % FLAGS.length] | (goalArea ? 4 : 0);
          var input =
              new MovementInit(
                  origin,
                  new Vec3(
                      random.nextFloat() * 600 - 300,
                      random.nextFloat() * 600 - 300,
                      random.nextFloat() * 800 - 400),
                  new Vec3(20, 30, 40),
                  0,
                  0,
                  .1f,
                  presence,
                  new Vec3(90, 180, 45),
                  128);
          int bits = i % 100 == 0 ? 0 : i % 100 == 1 ? 32767 : random.nextInt(32768);
          forcedMode = i % 6;
          sample = bits / 32767.0f;
          traceCount = 0;
          expected.add(
              goalArea
                  ? movement.moveInGoalArea(input, flags, nav.pointArea(input.origin()), r.end())
                  : finishing
                      ? movement.finish(input, flags, nav.pointArea(input.origin()), r)
                      : movement.execute(input, flags, nav.pointArea(input.origin()), r));
          counts.add(traceCount);
          int commandOffset = commands.length();
          commands.append("random ").append(bits).append("\n");
          commands.append("viewoffset 20 30 40\nviewangles 90 180 45\nforce ");
          for (int j = 0; j < 2; j++)
            commands
                .append(FRACTIONS[forcedMode][j])
                .append(' ')
                .append(ENTITIES[forcedMode][j])
                .append(' ')
                .append(SOLIDS[forcedMode][j])
                .append(' ');
          commands.append('\n');
          commands
              .append("reacharea ")
              .append(r.area())
              .append('\n')
              .append("movepresence ")
              .append(presence)
              .append('\n');
          commands
              .append(goalArea ? "ingoal " : finishing ? "finish " : "travel ")
              .append(r.baseTravelType())
              .append(' ');
          point(commands, input.origin());
          point(commands, input.velocity());
          point(commands, r.start());
          point(commands, r.end());
          commands.append(input.thinkTime()).append(' ').append(flags).append('\n');
          replays.add(commands.substring(commandOffset));
        }
        var actual = oracle(args[1], args[0], name, commands.toString());
        if (actual.size() != expected.size()) throw new AssertionError("Native count " + name);
        int different = 0;
        for (int i = 0; i < count; i++) {
          String[] f = actual.get(i).split(" ");
          var e = expected.get(i);
          var r = e.result();
          var move =
              e.movement().orElse(new LiquidReachMovement.Move(new Vec3(.25, -.5, .125), 123));
          int[] ints = {
            r.failure(),
            r.type(),
            r.blocked(),
            r.blockEntity(),
            r.travelType(),
            r.flags(),
            r.weapon()
          };
          boolean diff = false;
          for (int j = 0; j < ints.length; j++) diff |= ints[j] != Integer.parseInt(f[j + 1]);
          diff |= (e.actionFlags() | 8192) != Integer.parseInt(f[19]);
          diff |= Integer.parseInt(f[24].substring(6)) != (travelType == 9 && !finishing ? 1 : 0);
          diff |= (FLAGS[i % FLAGS.length] | (goalArea ? 4 : 0)) != Integer.parseInt(f[21]);
          diff |= Integer.parseInt(f[22]) != 0;
          diff |= counts.get(i) != Integer.parseInt(f[23].substring(7));
          float[] floats = {
            (float) r.direction().x(),
            (float) r.direction().y(),
            (float) r.direction().z(),
            (float) r.idealViewAngles().x(),
            (float) r.idealViewAngles().y(),
            (float) r.idealViewAngles().z(),
            (float) move.direction().x(),
            (float) move.direction().y(),
            (float) move.direction().z(),
            move.speed()
          };
          for (int j = 0; j < floats.length; j++) {
            float n = Float.parseFloat(f[j < 6 ? 8 + j : 15 + j - 6]);
            maxError = Math.max(maxError, Math.abs(floats[j] - n));
            diff |= Float.floatToIntBits(floats[j]) != Float.floatToIntBits(n);
          }
          if (diff) {
            if (mismatches++ < 20)
              System.out.println(
                  "DIFF "
                      + name
                      + " "
                      + i
                      + " Java="
                      + e
                      + " native="
                      + actual.get(i)
                      + "\nREPRO\n"
                      + replays.get(i));
            different++;
          }
        }
        total += count;
        maps++;
        System.out.println(name + " requests=" + count + " differences=" + different);
      }
    }
    System.out.printf(
        "Liquid travel maps=%d requests=%d exactDifferences=%d maxFloatError=%.9g seconds=%.3f%n",
        maps, total, mismatches, maxError, (System.nanoTime() - begin) / 1e9);
    if (maps == 0) throw new IllegalArgumentException("No maps with liquid reaches selected");
    if (mismatches != 0) throw new AssertionError("Liquid travel differences: " + mismatches);
  }

  private static void point(StringBuilder s, Vec3 p) {
    s.append((float) p.x())
        .append(' ')
        .append((float) p.y())
        .append(' ')
        .append((float) p.z())
        .append(' ');
  }

  private static List<String> oracle(String executable, String pk3, String map, String commands)
      throws Exception {
    Process process =
        new ProcessBuilder(executable, pk3, map)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output =
          executor.submit(
              () -> {
                byte[] b = process.getInputStream().readNBytes(8 * 1024 * 1024 + 1);
                if (b.length > 8 * 1024 * 1024)
                  throw new IllegalStateException("Oracle output cap");
                return new String(b, StandardCharsets.US_ASCII);
              });
      try (var input = process.getOutputStream()) {
        input.write(commands.getBytes(StandardCharsets.US_ASCII));
      }
      if (!process.waitFor(60, TimeUnit.SECONDS))
        throw new IllegalStateException("Oracle timed out");
      String text = output.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0)
        throw new IllegalStateException("Oracle failed " + process.exitValue());
      var results = new ArrayList<String>();
      String pending = null;
      for (String line : text.lines().toList()) {
        if (line.startsWith("TRAVEL ")) pending = line;
        else if (line.startsWith("DRAWS ") && pending != null) {
          results.add(pending + " draws=" + line.substring(6));
          pending = null;
        }
      }
      if (pending != null) throw new IllegalStateException("Missing native random draw count");
      return List.copyOf(results);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}

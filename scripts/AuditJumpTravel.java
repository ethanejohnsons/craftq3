import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Original-map differential for injected type-5 travel; the isolated oracle is never bundled. */
class AuditJumpTravel {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static int pointIndex, pointMask;
  private static Vec3 suppliedRunStart;
  private static int reachArea;

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 5)
      throw new IllegalArgumentException(
          "AuditJumpTravel <PK3> <oracle> [queries/map] [map|all] [entry|finish]");
    boolean finishing = args.length == 5 && args[4].equals("finish");
    if (args.length == 5 && !finishing && !args[4].equals("entry"))
      throw new IllegalArgumentException("Unknown jump travel mode");
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
            new JumpReachMovement(
                (start, end) -> suppliedRunStart,
                point ->
                    ((pointMask >> pointIndex++) & 1) == 0 ? reachArea : reachArea == 0 ? 1 : 0);
        var reaches = map.reachabilities().stream().filter(r -> r.baseTravelType() == 5).toList();
        if (reaches.isEmpty()) {
          System.out.println(name + " has no jump reaches");
          continue;
        }
        var commands = new StringBuilder("clear\neaseed .25 -.5 .125 123\n");
        var expected = new ArrayList<JumpReachMovement.Output>();
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
          suppliedRunStart =
              start.add(
                  new Vec3(
                      random.nextFloat() * 200 - 100,
                      random.nextFloat() * 200 - 100,
                      random.nextFloat() * 500 - 250));
          reachArea = i % 3 == 0 ? 0 : i % 3 == 1 ? 7 : 670;
          pointMask = i % 9 == 8 ? 0 : 1 << (i % 9);
          int lastReach = i % 5 == 0 ? 0 : 123;
          int jumpReach = i % 7 == 0 ? 0 : 456;
          pointIndex = 0;
          int presence = i % 4 == 0 ? 4 : 2, flags = (i % 3 == 0 ? 514 : 2);
          var input =
              new MovementInit(
                  origin,
                  new Vec3(
                      random.nextFloat() * 600 - 300,
                      random.nextFloat() * 600 - 300,
                      random.nextFloat() * 800 - 400),
                  ZERO,
                  0,
                  0,
                  .1f,
                  presence,
                  ZERO,
                  0);
          expected.add(
              finishing
                  ? movement.finish(input, flags, reachArea, r, lastReach, jumpReach)
                  : movement.execute(input, flags, reachArea, r, lastReach, jumpReach));
          counts.add(pointIndex);
          int commandOffset = commands.length();
          commands.append("reachsource ").append(reachArea).append("\nrunstart ");
          point(commands, suppliedRunStart);
          commands
              .append("\npointsequence ")
              .append(pointMask)
              .append("\nreachhistory ")
              .append(lastReach)
              .append(' ')
              .append(jumpReach)
              .append('\n');
          commands
              .append("reacharea ")
              .append(r.area())
              .append('\n')
              .append("movepresence ")
              .append(presence)
              .append('\n');
          commands.append(finishing ? "finish " : "travel ").append(r.baseTravelType()).append(' ');
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
          var move = e.movement().orElse(new JumpReachMovement.Move(new Vec3(.25, -.5, .125), 123));
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
          diff |= e.actionFlags() != Integer.parseInt(f[19]);
          diff |= (i % 3 == 0 ? 514 : 2) != Integer.parseInt(f[21]);
          diff |= Integer.parseInt(f[22]) != e.jumpReach();
          diff |= Integer.parseInt(f[23].substring(7)) != 0;
          diff |= counts.get(i) != Integer.parseInt(f[24].substring(7));
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
        "Jump travel maps=%d requests=%d exactDifferences=%d maxFloatError=%.9g seconds=%.3f%n",
        maps, total, mismatches, maxError, (System.nanoTime() - begin) / 1e9);
    if (maps == 0) throw new IllegalArgumentException("No maps with jump reaches selected");
    if (mismatches != 0) throw new AssertionError("Jump travel differences: " + mismatches);
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
      return text.lines().filter(l -> l.startsWith("TRAVEL ")).toList();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}

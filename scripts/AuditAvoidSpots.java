import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.AasMovementRoutes;
import dev.bluevista.craftq3.botlib.aas.AasRouteTimes;
import dev.bluevista.craftq3.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Read-only differential against an isolated unchanged native AAS host, never bundled. */
class AuditAvoidSpots {
  private record Query(
      int start,
      Vec3 origin,
      int goal,
      int flags,
      AasMovementRoutes.Context context,
      int[] expected) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException(
          "AuditAvoidSpots <PK3> <native oracle> [queries/map] [map]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 100;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid query count");
    int maps = 0, queries = 0, mismatches = 0, reachable = 0;
    long started = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        String name = entry.getName().substring(5, entry.getName().length() - 4);
        if (args.length == 4 && !name.equals(args[3])) continue;
        byte[] bytes;
        try (var in = zip.getInputStream(entry)) {
          bytes = in.readNBytes(AasReader.MAX_BYTES + 1);
        }
        var map = AasReader.read(bytes);
        var times = new AasRouteTimes(map);
        var routes = new AasMovementRoutes(times);
        var candidates = new ArrayList<Integer>();
        for (int a = 1; a < map.areas().size(); a++)
          if (map.areaSettings().get(a).reachabilityCount() > 0
              && map.areaSettings().get(a).cluster() != 0) candidates.add(a);
        Random random = new Random(52), points = new Random(93);
        var expected = new ArrayList<Query>();
        var commands = new StringBuilder("frame 10\n");
        int mapReachable = 0, mapMismatches = 0;
        for (int i = 0; i < count; i++) {
          int start = candidates.get(random.nextInt(candidates.size())),
              goal = candidates.get(random.nextInt(candidates.size()));
          if (i < map.portals().size() - 1) goal = map.portals().get(i + 1).area();
          else if (i < 2 * (map.portals().size() - 1))
            start = map.portals().get(i - map.portals().size() + 2).area();
          else if (i % 10 == 8) goal = random.nextInt(map.areas().size());
          else if (i % 10 == 9) start = random.nextInt(map.areas().size());
          if (map.areaSettings().get(start).reachabilityCount() == 0)
            start = candidates.get(random.nextInt(candidates.size()));
          Vec3 origin = map.areas().get(start).center();
          if (i % 5 == 4)
            origin =
                origin.add(
                    new Vec3(
                        points.nextFloat() * 128 - 64,
                        points.nextFloat() * 128 - 64,
                        points.nextFloat() * 128 - 64));
          int flags =
              i % 4 == 3
                  ? TravelFlags.ALL
                  : i % 4 == 2 ? TravelFlags.DEFAULT & ~TravelFlags.JUMP : TravelFlags.DEFAULT;
          var setting = map.areaSettings().get(start);
          var context =
              i % 2 == 0
                  ? AasMovementRoutes.Context.EMPTY
                  : new AasMovementRoutes.Context(
                      i % 3 == 0 ? goal : 0,
                      map.reachabilities()
                          .get(setting.firstReachability() + i % setting.reachabilityCount())
                          .area(),
                      10,
                      List.of(
                          new AasMovementRoutes.AvoidReach(
                              setting.firstReachability() + (i + 1) % setting.reachabilityCount(),
                              9 + i % 3,
                              i % 8)));
          var spots = new ArrayList<dev.bluevista.craftq3.botlib.movement.BotMovement.AvoidSpot>();
          int spotCount = i % 53 == 0 ? 32 : new int[] {0, 1, 2, 8}[i % 4];
          for (int j = 0; j < spotCount; j++) {
            var reach =
                map.reachabilities()
                    .get(setting.firstReachability() + j % setting.reachabilityCount());
            Vec3 center =
                j % 3 == 0
                    ? reach.start()
                    : j % 3 == 1 ? reach.end() : origin.add(reach.start()).scale(.5);
            if (j % 2 == 1)
              center =
                  center.add(
                      new Vec3(
                          points.nextFloat() * 256 - 128,
                          points.nextFloat() * 256 - 128,
                          points.nextFloat() * 256 - 128));
            spots.add(
                new dev.bluevista.craftq3.botlib.movement.BotMovement.AvoidSpot(
                    center,
                    new float[] {0, 8, 32, 128, -32, 512}[(i + j) % 6],
                    new int[] {0, 1, 2, 3, -1}[(i + j) % 5]));
          }
          var selection = routes.select(start, origin, goal, flags, context, spots);
          int[] value = {selection.reachability(), selection.flags()};
          expected.add(new Query(start, origin, goal, flags, context, value));
          commands
              .append("moveavoid ")
              .append(start)
              .append(' ')
              .append((float) origin.x())
              .append(' ')
              .append((float) origin.y())
              .append(' ')
              .append((float) origin.z())
              .append(' ')
              .append(goal)
              .append(' ')
              .append(flags)
              .append(' ')
              .append(context.previousGoalArea())
              .append(' ')
              .append(context.previousArea())
              .append(' ')
              .append(context.avoided().isEmpty() ? 0 : context.avoided().getFirst().reachability())
              .append(' ')
              .append(context.avoided().isEmpty() ? 0 : context.avoided().getFirst().expiresAt())
              .append(' ')
              .append(context.avoided().isEmpty() ? 0 : context.avoided().getFirst().tries())
              .append(' ')
              .append(spots.size());
          for (var spot : spots)
            commands
                .append(' ')
                .append((float) spot.origin().x())
                .append(' ')
                .append((float) spot.origin().y())
                .append(' ')
                .append((float) spot.origin().z())
                .append(' ')
                .append(spot.radius())
                .append(' ')
                .append(spot.type());
          commands.append('\n');
        }
        var actual = oracle(args[1], args[0], name, commands.toString());
        if (actual.size() != expected.size())
          throw new AssertionError("Native result count mismatch " + name);
        for (int i = 0; i < actual.size(); i++) {
          Query query = expected.get(i);
          if (actual.get(i)[0] > 0) {
            reachable++;
            mapReachable++;
          }
          if (!java.util.Arrays.equals(actual.get(i), query.expected())) {
            if (mismatches++ < 20)
              System.out.println(
                  "DIFF "
                      + name
                      + " query="
                      + query
                      + " expected="
                      + java.util.Arrays.toString(query.expected())
                      + " native="
                      + java.util.Arrays.toString(actual.get(i)));
            mapMismatches++;
          }
        }
        maps++;
        queries += count;
        System.out.println(
            name
                + " queries="
                + count
                + " reachable="
                + mapReachable
                + " mismatches="
                + mapMismatches
                + " cache="
                + times.cacheStats());
      }
    }
    System.out.printf(
        "Movement routes maps=%d queries=%d reachable=%d mismatches=%d seconds=%.3f%n",
        maps, queries, reachable, mismatches, (System.nanoTime() - started) / 1e9);
    if (mismatches != 0) throw new AssertionError("Avoid-spot route mismatches: " + mismatches);
  }

  private static List<int[]> oracle(String executable, String pk3, String map, String commands)
      throws Exception {
    Process process =
        new ProcessBuilder(executable, pk3, map)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output =
          executor.submit(
              () -> {
                byte[] bytes = process.getInputStream().readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024)
                  throw new IllegalStateException("Oracle output budget exceeded");
                return new String(bytes, StandardCharsets.US_ASCII);
              });
      try (var input = process.getOutputStream()) {
        input.write(commands.getBytes(StandardCharsets.US_ASCII));
      }
      if (!process.waitFor(60, TimeUnit.SECONDS))
        throw new IllegalStateException("Native routing oracle timed out");
      String text = output.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0)
        throw new IllegalStateException("Native routing oracle failed " + process.exitValue());
      return text.lines()
          .filter(line -> line.startsWith("MOVEAVOID "))
          .map(
              line -> {
                String[] fields = line.split(" ");
                return new int[] {Integer.parseInt(fields[1]), Integer.parseInt(fields[2])};
              })
          .toList();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}

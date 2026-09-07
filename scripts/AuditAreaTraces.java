import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.AasAreaTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Read-only ordered raw area-entry differential against an isolated native oracle. */
class AuditAreaTraces {
  record Query(Vec3 start, Vec3 end, int cap, List<AasAreaTrace.Entry> expected) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException("AuditAreaTraces PK3 nativeOracle [queries/map] [map]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid query count");
    int maps = 0, queries = 0, differences = 0, entries = 0;
    double maximumError = 0;
    long started = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var item :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        String name = item.getName().substring(5, item.getName().length() - 4);
        if (args.length == 4 && !name.equals(args[3])) continue;
        byte[] bytes;
        try (var input = zip.getInputStream(item)) {
          bytes = input.readNBytes(AasReader.MAX_BYTES + 1);
        }
        var map = AasReader.read(bytes);
        var random = new Random(527);
        var commands = new StringBuilder();
        var expected = new ArrayList<Query>();
        for (int i = 0; i < count; i++) {
          Vec3 start = map.areas().get(1 + random.nextInt(map.areas().size() - 1)).center();
          Vec3 end = map.areas().get(1 + random.nextInt(map.areas().size() - 1)).center();
          if (i % 2 == 0)
            end =
                start.add(
                    new Vec3(
                        (random.nextInt(3) - 1) * 8,
                        (random.nextInt(3) - 1) * 8,
                        (random.nextInt(3) - 1) * 12));
          if (i % 10 == 9) end = start;
          int cap = new int[] {1, 10, 32, 128}[i % 4];
          expected.add(
              new Query(start, end, cap, AasAreaTrace.trace(map, start, end, cap, 10_000_000)));
          commands.append("raw ").append(cap);
          for (Vec3 point : List.of(start, end))
            commands
                .append(' ')
                .append((float) point.x())
                .append(' ')
                .append((float) point.y())
                .append(' ')
                .append((float) point.z());
          commands.append('\n');
        }
        var actual = oracle(args[1], args[0], name, commands.toString());
        if (actual.size() != expected.size())
          throw new AssertionError("Native query count mismatch");
        int mapDifferences = 0;
        for (int i = 0; i < actual.size(); i++) {
          var a = actual.get(i);
          var e = expected.get(i).expected();
          entries += a.size();
          if (!a.equals(e)) {
            if (differences++ < 10)
              System.out.println("DIFF " + name + " " + expected.get(i) + " actual=" + a);
            mapDifferences++;
          }
          for (int j = 0; j < Math.min(a.size(), e.size()); j++) {
            Vec3 x = a.get(j).point(), y = e.get(j).point();
            maximumError =
                Math.max(
                    maximumError,
                    Math.max(
                        Math.abs(x.x() - y.x()),
                        Math.max(Math.abs(x.y() - y.y()), Math.abs(x.z() - y.z()))));
          }
        }
        queries += count;
        maps++;
        System.out.println(name + " queries=" + count + " differences=" + mapDifferences);
      }
    }
    System.out.printf(
        "Raw area traces maps=%d queries=%d entries=%d differences=%d maximumCoordinateError=%.9g seconds=%.3f%n",
        maps, queries, entries, differences, maximumError, (System.nanoTime() - started) / 1e9);
    if (maps == 0 || differences != 0)
      throw new AssertionError("Native raw area-trace audit failed");
  }

  static List<List<AasAreaTrace.Entry>> oracle(
      String executable, String pk3, String map, String commands) throws Exception {
    var process =
        new ProcessBuilder(executable, pk3, map)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output =
          executor.submit(
              () -> {
                byte[] bytes = process.getInputStream().readNBytes(32 * 1024 * 1024 + 1);
                if (bytes.length > 32 * 1024 * 1024)
                  throw new IllegalStateException("Oracle output cap");
                return new String(bytes, StandardCharsets.US_ASCII);
              });
      try (var input = process.getOutputStream()) {
        input.write(commands.getBytes(StandardCharsets.US_ASCII));
      }
      if (!process.waitFor(60, TimeUnit.SECONDS))
        throw new IllegalStateException("Raw area oracle timeout");
      if (process.exitValue() != 0) throw new IllegalStateException("Raw area oracle failure");
      return output
          .get(5, TimeUnit.SECONDS)
          .lines()
          .filter(l -> l.startsWith("RAW "))
          .map(
              line -> {
                String[] fields = line.split(" ");
                int count = Integer.parseInt(fields[1]);
                if (fields.length != count + 2)
                  throw new AssertionError("Malformed native raw result");
                var result = new ArrayList<AasAreaTrace.Entry>();
                for (int i = 0; i < count; i++) {
                  String[] split = fields[i + 2].split("@");
                  String[] point = split[1].split(",");
                  result.add(
                      new AasAreaTrace.Entry(
                          Integer.parseInt(split[0]),
                          new Vec3(
                              Float.parseFloat(point[0]),
                              Float.parseFloat(point[1]),
                              Float.parseFloat(point[2]))));
                }
                return List.copyOf(result);
              })
          .toList();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}

import dev.bluevista.craftq3.assets.bsp.BspBoxLeaves;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Original-map box/leaf differential against an isolated public native collision observer. */
class AuditBoxLeaves {
  private record Query(BspMap.Bounds bounds, int capacity, BspBoxLeaves.Result expected) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException("AuditBoxLeaves <PK3> <oracle> [queries/map] [map|all]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
    if (count < 1 || count > 100000) throw new IllegalArgumentException("Invalid query count");
    Path pk3 = Path.of(args[0]).toAbsolutePath(), oracle = Path.of(args[1]).toAbsolutePath();
    Path scratch = Files.createTempDirectory(oracle.getParent(), "corpus-");
    Files.createDirectories(scratch.resolve("assets/baseq3"));
    Files.createDirectories(scratch.resolve("home"));
    Files.createSymbolicLink(scratch.resolve("assets/baseq3/pak0.pk3"), pk3);
    int maps = 0, queries = 0, differences = 0;
    long begin = System.nanoTime();
    boolean succeeded = false;
    try (var zip = new ZipFile(pk3.toFile())) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".bsp"))
              .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        String name = entry.getName().substring(5, entry.getName().length() - 4);
        if (args.length == 4 && !args[3].equals("all") && !args[3].equals(name)) continue;
        BspMap map;
        try (var in = zip.getInputStream(entry)) {
          map = BspReader.read(in.readNBytes(BspReader.MAX_BYTES + 1));
        }
        var rng = new Random(97217);
        var expected = new ArrayList<Query>();
        var commands = new StringBuilder();
        for (int i = 0; i < count; i++) {
          BspMap.Bounds bounds = bounds(map, rng, i);
          if (i == 0 && name.equals("q3tourney4"))
            bounds = new BspMap.Bounds(new Vec3(-1, -329, 255), new Vec3(81, -311, 401));
          int capacity =
              switch (i % 7) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> 2;
                case 3 -> 16;
                case 4 -> 128;
                case 5 -> Math.min(65536, map.leaves().size());
                default -> rng.nextInt(65);
              };
          if (i < 2) capacity = Math.min(65536, map.leaves().size());
          expected.add(new Query(bounds, capacity, BspBoxLeaves.query(map, bounds, capacity)));
          commands.append(i).append(' ').append(capacity).append(' ');
          point(commands, bounds.min());
          point(commands, bounds.max());
          commands.append('\n');
        }
        Path queryFile = scratch.resolve(name + ".txt");
        Files.writeString(queryFile, commands, StandardCharsets.US_ASCII);
        String output = oracle(oracle, scratch, queryFile, name);
        Files.writeString(scratch.resolve(name + ".log"), output, StandardCharsets.UTF_8);
        int seen = 0, mapDifferences = 0;
        for (String line : output.lines().toList()) {
          if (line.startsWith("CMBOX ")) {
            String[] f = line.split(" ");
            int id = Integer.parseInt(f[1]),
                size = Integer.parseInt(f[2]),
                last = Integer.parseInt(f[3]);
            if (id != seen++ || size + 4 != f.length)
              throw new AssertionError("Native result framing " + name);
            var actual = new ArrayList<Integer>();
            for (int j = 0; j < size; j++) actual.add(Integer.parseInt(f[j + 4]));
            Query q = expected.get(id);
            if (!actual.equals(q.expected().leaves()) || last != q.expected().lastLeaf()) {
              if (differences++ < 20)
                System.out.println("DIFF " + name + " #" + id + " " + q + " native=" + line);
              mapDifferences++;
            }
          } else if (line.startsWith("CMLEAF ")) {
            String[] f = line.split(" ");
            var leaf = map.leaves().get(Integer.parseInt(f[1]));
            if (leaf.cluster() != Integer.parseInt(f[2]) || leaf.area() != Integer.parseInt(f[3]))
              throw new AssertionError("Native leaf metadata " + name + " " + line);
          }
        }
        if (seen != count) throw new AssertionError("Missing native queries " + name + " " + seen);
        maps++;
        queries += count;
        System.out.println(name + " queries=" + count + " differences=" + mapDifferences);
      }
      System.out.printf(
          "BSP box leaves maps=%d queries=%d differences=%d seconds=%.3f%n",
          maps, queries, differences, (System.nanoTime() - begin) / 1e9);
      if (maps == 0 || differences != 0)
        throw new AssertionError("Box-leaf differential failed; logs " + scratch);
      succeeded = true;
    } finally {
      if (succeeded)
        try (var paths = Files.walk(scratch)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
  }

  private static BspMap.Bounds bounds(BspMap map, Random rng, int index) {
    if (index == 1)
      return new BspMap.Bounds(
          new Vec3(-100000, -100000, -100000), new Vec3(100000, 100000, 100000));
    var node = map.nodes().get(rng.nextInt(map.nodes().size()));
    var plane = map.planes().get(node.plane());
    Vec3 center = map.vertices().get(rng.nextInt(map.vertices().size())).position();
    if (index % 8 == 3) return new BspMap.Bounds(center, center);
    if (index % 8 == 4) return map.leaves().get(rng.nextInt(map.leaves().size())).bounds();
    if (index % 8 == 5) return node.bounds();
    float[] lo = {(float) center.x(), (float) center.y(), (float) center.z()};
    float[] hi = lo.clone();
    for (int axis = 0; axis < 3; axis++) {
      float offset = rng.nextFloat() * 128 - 64, radius = rng.nextFloat() * 64;
      lo[axis] += offset;
      hi[axis] = lo[axis] + radius;
      lo[axis] -= radius;
    }
    float[] n = {
      (float) plane.normal().x(), (float) plane.normal().y(), (float) plane.normal().z()
    };
    int axis = Math.abs(n[0]) > Math.abs(n[1]) ? 0 : 1;
    if (Math.abs(n[2]) > Math.abs(n[axis])) axis = 2;
    if (index % 8 <= 2 || index % 8 == 7) {
      float other = 0;
      for (int a = 0; a < 3; a++) if (a != axis) other += lo[a] * n[a];
      float coordinate = (plane.distance() - other) / n[axis];
      if (index % 8 == 0 || index % 8 == 7) {
        for (int a = 0; a < 3; a++) hi[a] = lo[a];
        if (index % 8 == 7)
          coordinate = (index / 8) % 2 == 0 ? Math.nextUp(coordinate) : Math.nextDown(coordinate);
        lo[axis] = hi[axis] = coordinate;
      } else if (index % 8 == 1) {
        lo[axis] = coordinate - 16;
        hi[axis] = coordinate;
      } else {
        lo[axis] = coordinate;
        hi[axis] = coordinate + 16;
      }
    }
    return new BspMap.Bounds(new Vec3(lo[0], lo[1], lo[2]), new Vec3(hi[0], hi[1], hi[2]));
  }

  private static void point(StringBuilder s, Vec3 p) {
    s.append((float) p.x())
        .append(' ')
        .append((float) p.y())
        .append(' ')
        .append((float) p.z())
        .append(' ');
  }

  private static String oracle(Path executable, Path scratch, Path queryFile, String map)
      throws Exception {
    var builder =
        new ProcessBuilder(
                executable.toString(),
                "+set",
                "com_basegame",
                "baseq3",
                "+set",
                "fs_basepath",
                scratch.resolve("assets").toString(),
                "+set",
                "fs_homepath",
                scratch.resolve("home").toString(),
                "+set",
                "net_ip",
                "127.0.0.1",
                "+set",
                "net_port",
                "27988",
                "+set",
                "bot_enable",
                "0",
                "+map",
                map)
            .redirectErrorStream(true);
    builder.environment().put("CRAFTQ3_BOX_QUERIES", queryFile.toString());
    builder.environment().remove("CRAFTQ3_BOX_FIXTURES");
    Process process = builder.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output =
          executor.submit(
              () -> {
                byte[] bytes = process.getInputStream().readNBytes(64 * 1024 * 1024 + 1);
                if (bytes.length > 64 * 1024 * 1024)
                  throw new IllegalStateException("Native output cap");
                return new String(bytes, StandardCharsets.UTF_8);
              });
      if (!process.waitFor(30, TimeUnit.SECONDS))
        throw new IllegalStateException("Native box query timeout");
      String result = output.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0)
        throw new IllegalStateException(
            "Native box query failed " + process.exitValue() + " " + result);
      return result;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}

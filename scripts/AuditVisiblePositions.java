import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.goal.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Authored differential for visible route positions; assets remain inside the supplied archive. */
class AuditVisiblePositions {
  record Result(Optional<Vec3> target, int traces, int hash) {}

  record Query(String commands, Result expected) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException(
          "AuditVisiblePositions PK3 oracle [count/map] [map-or-all]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid query count");
    int maps = 0, total = 0, differences = 0;
    long started = System.nanoTime();
    try (var zip = new ZipFile(args[0])) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(Comparator.comparing(ZipEntry::getName))
              .toList()) {
        String name = entry.getName().substring(5, entry.getName().length() - 4);
        if (args.length == 4 && !args[3].equals("all") && !name.equals(args[3])) continue;
        AasMap map;
        try (var input = zip.getInputStream(entry)) {
          map = AasReader.read(input.readNBytes(AasReader.MAX_BYTES + 1));
        }
        var routes = new AasMovementRoutes(map);
        var random = new Random(572);
        var commands = new StringBuilder();
        var queries = new ArrayList<Query>();
        for (int i = 0; i < count; i++) {
          int area = 1 + random.nextInt(map.areas().size() - 1);
          int goalArea = i % 11 == 0 ? area : 1 + random.nextInt(map.areas().size() - 1);
          var origin =
              map.areas()
                  .get(area)
                  .center()
                  .add(
                      new Vec3(
                          random.nextInt(129) - 64,
                          random.nextInt(129) - 64,
                          random.nextInt(129) - 64));
          var goalPosition = map.areas().get(goalArea).center();
          if (i % 17 == 0) area = 0;
          if (i % 19 == 0) goalArea = 0;
          int flags = i % 13 == 0 ? 0 : i % 3 == 0 ? 18616254 : 0xffffff;
          int entity = new int[] {0, 1, 42, 1023, -1}[i % 5];
          var zero = new Vec3(0, 0, 0);
          var goal = new Goal(goalPosition, goalArea, zero, zero, entity, 0, 0, 0);
          int mode = i % 7;
          int clearAt = 1 + random.nextInt(43);
          float height = (float) goalPosition.z() + random.nextInt(81) - 40;
          var world = new World(mode, clearAt, height);
          String settings =
              switch (mode) {
                case 0 -> "world 0 0 0";
                case 1 -> "world 2 0 0";
                case 2 -> "world 3 0 " + clearAt;
                case 3 -> "response 1 1 1 " + entity;
                case 4 -> "response 0.999 0 0 " + entity;
                case 5 -> "world 1 " + height + " 0";
                default -> "world 2 0 0";
              };
          String command =
              "pvs "
                  + (mode == 6 ? 0 : 1)
                  + "\n"
                  + settings
                  + "\nvisible "
                  + xyz(origin)
                  + " "
                  + area
                  + " "
                  + goalArea
                  + " "
                  + xyz(goalPosition)
                  + " "
                  + flags
                  + " "
                  + entity
                  + "\n";
          Optional<Vec3> target =
              new BotVisiblePosition(map, routes, world)
                  .predict(origin, area, goal, TravelPolicy.ofFlags(flags));
          queries.add(new Query(command, new Result(target, world.traces, world.hash)));
          commands.append(command);
        }
        var actual = oracle(args[1], args[0], name, commands.toString());
        if (actual.size() != queries.size())
          throw new AssertionError("Native query count " + actual.size());
        int mapDifferences = 0;
        for (int i = 0; i < queries.size(); i++) {
          var expected = queries.get(i).expected();
          var observed = actual.get(i);
          if (!expected.equals(observed)) {
            if (differences++ < 15)
              System.out.println("DIFF " + name + " " + queries.get(i) + " native=" + observed);
            mapDifferences++;
          }
        }
        total += count;
        maps++;
        System.out.println(name + " queries=" + count + " differences=" + mapDifferences);
      }
    }
    System.out.println(
        "Visible positions maps="
            + maps
            + " queries="
            + total
            + " differences="
            + differences
            + " seconds="
            + (System.nanoTime() - started) / 1e9);
    if (maps == 0 || differences != 0)
      throw new AssertionError("Visible position differential failed");
  }

  static final class World implements TraceWorld {
    final int mode, clearAt;
    final float height;
    int traces, hash = 1;

    World(int mode, int clearAt, float height) {
      this.mode = mode;
      this.clearAt = clearAt;
      this.height = height;
    }

    @Override
    public int pointContents(Vec3 p, int model, int ignored) {
      throw new AssertionError("Unexpected contents query");
    }

    @Override
    public TraceResult trace(TraceRequest request) {
      traces++;
      for (var v : List.of(request.start(), request.mins(), request.maxs(), request.end()))
        for (double n : new double[] {v.x(), v.y(), v.z()})
          hash = hash * 31 + Float.floatToRawIntBits((float) n);
      hash = hash * 31 + request.ignoreEntity();
      hash = hash * 31 + request.contentsMask();
      if (mode == 0 || mode == 3 || mode == 2 && traces == clearAt)
        return new TraceResult(1, request.end(), mode == 3, mode == 3, Optional.empty());
      if (mode == 5) {
        float a = (float) request.start().z() - height, b = (float) request.end().z() - height;
        if (a < 0 && b >= 0 || a >= 0 && b >= 0) return TraceResult.clear(request);
      }
      return new TraceResult(
          mode == 4 ? .999f : 0, request.start(), false, false, Optional.empty());
    }
  }

  static String xyz(Vec3 p) {
    return (float) p.x() + " " + (float) p.y() + " " + (float) p.z();
  }

  static List<Result> oracle(String oracle, String pk3, String map, String commands)
      throws Exception {
    var process =
        new ProcessBuilder(oracle, pk3, map).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read =
          executor.submit(
              () -> {
                byte[] bytes = process.getInputStream().readNBytes(32 * 1024 * 1024 + 1);
                if (bytes.length > 32 * 1024 * 1024)
                  throw new IllegalStateException("Native output cap");
                return new String(bytes, StandardCharsets.US_ASCII);
              });
      try (var write = process.getOutputStream()) {
        write.write(commands.getBytes(StandardCharsets.US_ASCII));
      }
      if (!process.waitFor(60, TimeUnit.SECONDS)) throw new IllegalStateException("Native timeout");
      if (process.exitValue() != 0)
        throw new IllegalStateException("Native exit " + process.exitValue());
      return read.get(5, TimeUnit.SECONDS)
          .lines()
          .filter(l -> l.startsWith("VISIBLE "))
          .map(
              line -> {
                String[] p = line.split(" ");
                if (!p[6].equals("0")) throw new AssertionError("Unexpected native PVS query");
                var target =
                    new Vec3(
                        Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]));
                if (p[1].equals("0") && !target.equals(new Vec3(12345, 23456, 34567)))
                  throw new AssertionError("Unexpected native partial write");
                return new Result(
                    p[1].equals("1") ? Optional.of(target) : Optional.empty(),
                    Integer.parseInt(p[5]),
                    (int) Long.parseLong(p[7]));
              })
          .toList();
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}

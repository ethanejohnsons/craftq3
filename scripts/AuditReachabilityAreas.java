import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.aas.AasReachabilityArea;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Read-only area selection differential; imported BSP collision is explicitly controlled. */
class AuditReachabilityAreas {
  record Query(String command, String mode, int model, int expected) {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 4)
      throw new IllegalArgumentException("AuditReachabilityAreas PK3 nativeOracle [points/map] [map]");
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 500;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid point count");
    int maps = 0, queries = 0, differences = 0, moverModels = 0;
    long started = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var item : zip.stream().filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
          .sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName)).toList()) {
        String name = item.getName().substring(5, item.getName().length() - 4);
        if (args.length == 4 && !name.equals(args[3])) continue;
        byte[] bytes;
        try (var input = zip.getInputStream(item)) { bytes = input.readNBytes(AasReader.MAX_BYTES + 1); }
        var map = AasReader.read(bytes);
        try (var input = zip.getInputStream(zip.getEntry("maps/" + name + ".bsp"))) { bytes = input.readNBytes(128 * 1024 * 1024 + 1); }
        var bsp = BspReader.read(bytes);
        var world = new World();
        int[] liveModel = {0};
        var service = new AasReachabilityArea(new AasNavigation(map), bsp.entities(), world,
            id -> Optional.of(new AasReachabilityArea.Entity(liveModel[0])));
        var models = bsp.entities().stream()
            .filter(e -> e.getOrDefault("classname", "").equalsIgnoreCase("func_plat")
                || e.getOrDefault("classname", "").equalsIgnoreCase("func_bobbing"))
            .map(e -> Integer.parseInt(e.get("model").substring(1))).distinct().toList();
        moverModels += models.size();
        var random = new Random(5482);
        var commands = new StringBuilder();
        var expected = new ArrayList<Query>();
        for (int i = 0; i < count; i++) {
          Vec3 center = map.areas().get(1 + random.nextInt(map.areas().size() - 1)).center();
          Vec3 origin = new Vec3((float) center.x() + random.nextInt(65) - 32,
              (float) center.y() + random.nextInt(65) - 32,
              (float) center.z() + random.nextInt(129) - 32);
          if (i % 4 == 0) origin = center;
          String xyz = xyz(origin);
          expected.add(new Query("fuzzy " + xyz, "fuzzy", 0, service.fuzzyArea(origin)));
          commands.append("fuzzy ").append(xyz).append('\n');
          int client = i % 65 - 1;
          for (int mode = 0; mode < 4; mode++) {
            liveModel[0] = 0;
            world.mode = mode;
            String setting = switch (mode) {
              case 0 -> "clear\n";
              case 1 -> "hitentity 1022\nfloor " + ((float) origin.z() - 25) + "\n";
              case 2 -> "hitentity 15\nfloor " + ((float) origin.z() - 25) + "\nentity 15 0 0 0 -15 -15 -24 15 15 8 0 0 1023 0 0\n";
              default -> "solid\n";
            };
            String query = "reachable " + xyz + " " + client;
            commands.append(setting).append(query).append('\n');
            expected.add(new Query(query, "ground" + mode, 0, service.reachableArea(origin, client)));
          }
          for (int model : models) {
            liveModel[0] = model;
            world.mode = 2;
            commands.append("hitentity 15\nfloor ").append((float) origin.z() - 25)
                .append("\nentity 15 0 0 0 -15 -15 -24 15 15 8 0 0 1023 0 ").append(model).append('\n');
            String query = "reachable " + xyz + " " + client;
            commands.append(query).append('\n');
            expected.add(new Query(query, "mover", model, service.reachableArea(origin, client)));
          }
        }
        var actual = oracle(args[1], args[0], name, commands.toString());
        if (actual.size() != expected.size()) throw new AssertionError("Native query count mismatch " + actual.size() + " != " + expected.size());
        int mapDifferences = 0;
        for (int i = 0; i < actual.size(); i++) {
          if (actual.get(i) != expected.get(i).expected()) {
            if (differences++ < 20) System.out.println("DIFF " + name + " " + expected.get(i) + " actual=" + actual.get(i));
            mapDifferences++;
          }
        }
        maps++; queries += expected.size();
        System.out.println(name + " queries=" + expected.size() + " moverModels=" + models.size() + " differences=" + mapDifferences);
      }
    }
    System.out.printf("Reachability areas maps=%d queries=%d moverModels=%d differences=%d seconds=%.3f%n", maps, queries, moverModels, differences, (System.nanoTime() - started) / 1e9);
    if (maps == 0 || differences != 0) throw new AssertionError("Native reachable-area audit failed");
  }

  static String xyz(Vec3 p) { return (float) p.x() + " " + (float) p.y() + " " + (float) p.z(); }

  static final class World implements TraceWorld {
    int mode;
    public TraceResult trace(TraceRequest request) {
      if (mode == 0) return TraceResult.clear(request);
      float fraction = mode == 3 ? 0 : 1f / 3;
      Vec3 a = request.start(), b = request.end();
      Vec3 end = new Vec3((float) a.x() + fraction * ((float) b.x() - (float) a.x()),
          (float) a.y() + fraction * ((float) b.y() - (float) a.y()),
          (float) a.z() + fraction * ((float) b.z() - (float) a.z()));
      return new TraceResult(fraction, end, mode == 3, mode == 3,
          Optional.of(new TraceResult.Hit(new TraceResult.Plane(new Vec3(0,0,1), 0), 1, 0,
              mode == 1 ? 1022 : 15, 0, -1, -1, -1, "controlled")));
    }
    public int pointContents(Vec3 point, int mask, int ignoreEntity) { return 0; }
  }

  static List<Integer> oracle(String executable, String pk3, String map, String commands) throws Exception {
    var process = new ProcessBuilder(executable, pk3, map).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var output = executor.submit(() -> {
        byte[] bytes = process.getInputStream().readNBytes(32 * 1024 * 1024 + 1);
        if (bytes.length > 32 * 1024 * 1024) throw new IllegalStateException("Oracle output cap");
        return new String(bytes, StandardCharsets.US_ASCII);
      });
      try (var input = process.getOutputStream()) { input.write(commands.getBytes(StandardCharsets.US_ASCII)); }
      if (!process.waitFor(60, TimeUnit.SECONDS)) throw new IllegalStateException("Reachability oracle timeout");
      if (process.exitValue() != 0) throw new IllegalStateException("Reachability oracle failure");
      return output.get(5, TimeUnit.SECONDS).lines()
          .filter(l -> l.startsWith("FUZZY ") || l.startsWith("REACHABLE "))
          .map(l -> Integer.parseInt(l.split(" ")[1])).toList();
    } finally { if (process.isAlive()) process.destroyForcibly(); }
  }
}

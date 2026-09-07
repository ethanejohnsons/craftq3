import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.assets.bsp.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.item.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Production/native suspended-item selection; original assets are read directly, never extracted.
 */
class AuditJumpPadItems {
  static final Vec3 MIN = new Vec3(-15, -15, -15), MAX = new Vec3(15, 15, 15);

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException("AuditJumpPadItems <pak0.pk3> <native-observer> [map]");
    int total = 0, failures = 0, launches = 0, launchFailures = 0, found = 0, maps = 0;
    double maxQueryError = 0;
    var random = new Random(20484421);
    try (var zip = new ZipFile(args[0])) {
      for (var name :
          zip.stream()
              .map(ZipEntry::getName)
              .filter(n -> n.matches("maps/[^/]+\\.aas"))
              .sorted()
              .toList()) {
        String map = name.substring(5, name.length() - 4);
        if (args.length == 3 && !map.equals(args[2])) continue;
        var aas = AasReader.read(zip.getInputStream(zip.getEntry(name)).readAllBytes());
        var bsp =
            BspReader.read(
                zip.getInputStream(zip.getEntry(name.replace(".aas", ".bsp"))).readAllBytes());
        var navigation = new AasNavigation(aas);
        var collision = new BspTraceWorld(bsp);
        var queries = new ArrayList<Vec3>();
        var world =
            new AasMovementWorld(
                navigation,
                (e, r) -> {
                  throw new AssertionError("Unexpected dynamic entity");
                },
                p -> {
                  queries.add(p);
                  return collision.pointContents(p, -1, -1);
                });
        var provider =
            new JumpPadItemAreas(
                navigation, bsp, world, AasMovementPredictor.Settings::defaults, s -> {});
        var launchProvider = new JumpPadLaunch(bsp, world, s -> {});
        if (map.equals("q3dm12")) {
          try (var sources = new ScriptSources(new ZipView(zip, args[0]))) {
            var registry =
                new ItemRegistry(
                    ItemConfig.load(sources, "items.c"),
                    new ItemPlacement(navigation, collision, provider, System.out::println),
                    System.out::println);
            registry.initialize(bsp.entities());
            var armor =
                registry.items().stream()
                    .filter(
                        item ->
                            item.info().classname().equals("item_armor_body")
                                && item.placement().origin().equals(new Vec3(-768, -1128, 160)))
                    .findFirst()
                    .orElseThrow();
            if (armor.placement().area() != 4421
                || !armor.placement().goalOrigin().equals(armor.placement().origin()))
              throw new AssertionError(
                  "Original suspended armor goal was not published faithfully");
            System.out.println("Q3DM12_ARMOR_PUBLISHED " + armor.goal());
          }
        }
        var process =
            new ProcessBuilder(args[1], args[0], map)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        var watchdog =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        Thread.sleep(180000);
                        process.destroyForcibly();
                      } catch (InterruptedException ignored) {
                      }
                    });
        int count = 0, bad = 0, hits = 0, pads = 0, badPads = 0;
        double mapError = 0;
        try (var input = new BufferedReader(new InputStreamReader(process.getInputStream()));
            var output = new PrintWriter(process.getOutputStream(), true)) {
          String row;
          while ((row = input.readLine()) != null && !row.startsWith("READY "))
            reply(row, output, collision, null);
          if (row == null) throw new AssertionError("Native startup EOF");
          for (int index = 0; index < bsp.entities().size(); index++) {
            if (!"trigger_push".equals(bsp.entities().get(index).get("classname"))) continue;
            output.println("padinfo " + (index + 1));
            while ((row = input.readLine()) != null && !row.startsWith("PAD "))
              reply(row, output, collision, null);
            if (row == null) throw new AssertionError("Native launch EOF");
            var expected = launchProvider.resolve(index, 800);
            var fields = row.split(" ");
            boolean ok = fields[1].equals("1");
            boolean mismatch = ok != expected.isPresent();
            if (ok && expected.isPresent()) {
              var value = expected.get();
              Vec3[] vectors = {value.origin(), value.minimum(), value.maximum(), value.velocity()};
              for (int v = 0; v < 4; v++) {
                Vec3 observed = vector(fields, 2 + v * 3);
                mismatch |= !observed.equals(vectors[v]);
              }
            }
            if (mismatch) {
              badPads++;
              if (badPads <= 3)
                System.out.println(
                    "LAUNCH_MISMATCH "
                        + map
                        + " "
                        + index
                        + " native="
                        + row
                        + " java="
                        + expected);
            }
            pads++;
          }
          var points = new ArrayList<Vec3>();
          for (var entity : bsp.entities())
            if (entity.containsKey("origin")) {
              String classname = entity.getOrDefault("classname", "");
              if (classname.startsWith("item_")
                  || classname.startsWith("weapon_")
                  || classname.startsWith("ammo_")
                  || classname.equals("target_position")) {
                try {
                  points.add(vector(entity.get("origin").trim().split("\\s+"), 0));
                } catch (RuntimeException ignored) {
                }
              }
            }
          if (map.equals("q3dm12")) points.addFirst(new Vec3(-768, -1128, 160));
          while (points.size() < 200) {
            var base =
                points.isEmpty()
                    ? aas.areas().get(1).center()
                    : points.get(random.nextInt(points.size()));
            points.add(
                new Vec3(
                    (float) base.x() + random.nextFloat() * 200 - 100,
                    (float) base.y() + random.nextFloat() * 200 - 100,
                    (float) base.z() + random.nextFloat() * 100 - 50));
          }
          if (points.size() > 250) points.subList(250, points.size()).clear();
          for (var point : points) {
            output.println("jump " + text(point) + " " + text(MIN) + " " + text(MAX));
            var nativeQueries = new ArrayList<Vec3>();
            while ((row = input.readLine()) != null && !row.startsWith("JUMP "))
              reply(row, output, collision, nativeQueries);
            if (row == null) throw new AssertionError("Native selection EOF");
            int expected = Integer.parseInt(row.split(" ")[1]);
            queries.clear();
            int actual = provider.bestArea(point, MIN, MAX).orElseThrow();
            boolean mismatch = expected != actual || nativeQueries.size() != queries.size();
            double error = 0;
            if (nativeQueries.size() == queries.size())
              for (int q = 0; q < queries.size(); q++) {
                error = Math.max(error, distance(nativeQueries.get(q), queries.get(q)));
              }
            mismatch |= error > .001;
            mapError = Math.max(mapError, error);
            if (mismatch) {
              bad++;
              if (bad <= 3)
                System.out.println(
                    "MISMATCH "
                        + map
                        + " "
                        + point
                        + " native="
                        + expected
                        + " java="
                        + actual
                        + " contentsQueries="
                        + nativeQueries.size()
                        + "/"
                        + queries.size()
                        + " error="
                        + error);
            }
            if (actual != 0) hits++;
            count++;
          }
        } finally {
          process.destroy();
          watchdog.interrupt();
        }
        System.out.println(
            map
                + " queries="
                + count
                + " mismatches="
                + bad
                + " reachable="
                + hits
                + " launches="
                + pads
                + " launchMismatches="
                + badPads
                + " maxContentsQueryError="
                + mapError);
        maps++;
        total += count;
        failures += bad;
        launches += pads;
        launchFailures += badPads;
        found += hits;
        maxQueryError = Math.max(maxQueryError, mapError);
      }
    }
    System.out.println(
        "TOTAL maps="
            + maps
            + " queries="
            + total
            + " mismatches="
            + failures
            + " reachable="
            + found
            + " launches="
            + launches
            + " launchMismatches="
            + launchFailures
            + " maxContentsQueryError="
            + maxQueryError);
    if (failures + launchFailures != 0)
      throw new AssertionError("Jump-pad item differential mismatch");
  }

  static String text(Vec3 p) {
    return (float) p.x() + " " + (float) p.y() + " " + (float) p.z();
  }

  static Vec3 vector(String[] parts, int offset) {
    return new Vec3(
        Float.parseFloat(parts[offset]),
        Float.parseFloat(parts[offset + 1]),
        Float.parseFloat(parts[offset + 2]));
  }

  static double distance(Vec3 a, Vec3 b) {
    return Math.max(
        Math.abs(a.x() - b.x()), Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
  }

  static void reply(String row, PrintWriter output, BspTraceWorld collision, List<Vec3> queries) {
    if (!row.startsWith("CONTENTS_QUERY ")) return;
    var p = vector(row.split(" "), 1);
    if (queries != null) queries.add(p);
    output.println("CONTENTS_REPLY " + collision.pointContents(p, -1, -1));
  }

  record ZipView(ZipFile zip, String path) implements VirtualFileSystem {
    public byte[] read(VirtualPath virtual) throws IOException {
      var entry = zip.getEntry(virtual.value());
      if (entry == null) throw new FileNotFoundException(virtual.value());
      try (var input = zip.getInputStream(entry)) {
        return input.readAllBytes();
      }
    }

    public Optional<Origin> which(VirtualPath virtual) {
      return zip.getEntry(virtual.value()) == null
          ? Optional.empty()
          : Optional.of(new Origin("baseq3", path, true));
    }

    public List<VirtualPath> list(String directory) {
      return zip.stream()
          .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(directory))
          .map(entry -> new VirtualPath(entry.getName()))
          .toList();
    }

    public List<Origin> searchOrder() {
      return List.of(new Origin("baseq3", path, true));
    }

    public void close() {}
  }
}

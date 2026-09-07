import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

class AuditMoverQueries {
  static MoverQueries.ModelBounds bounds;
  static MoverQueries.Entity first, hit;
  static float fraction;
  static int solids, hitNumber;

  public static void main(String[] args) throws Exception {
    int count = args.length > 2 ? Integer.parseInt(args[2]) : 25000;
    AasMap.Reachability reach;
    try (var zip = new ZipFile(args[0])) {
      var map = AasReader.read(zip.getInputStream(zip.getEntry("maps/q3dm19.aas")).readAllBytes());
      reach = map.reachabilities().get(689);
    }
    int model = reach.face() & 65535;
    System.out.println("Model " + model);
    var world =
        new TraceWorld() {
          public int pointContents(Vec3 p, int m, int e) {
            return 0;
          }

          public TraceResult trace(TraceRequest r) {
            return new TraceResult(
                fraction,
                r.end(),
                (solids & 1) != 0,
                (solids & 2) != 0,
                Optional.of(
                    new TraceResult.Hit(
                        TraceResult.Plane.NONE, 1, 0, hitNumber, 0, -1, -1, -1, "")));
          }
        };
    var query =
        new MoverQueries(
            world,
            m -> bounds,
            e -> e == 17 ? Optional.of(first) : e == 18 ? Optional.of(hit) : Optional.empty(),
            1024);
    Random random = new Random(0x4d4f5645);
    List<Boolean> expected = new ArrayList<>();
    List<String> replays = new ArrayList<>();
    StringBuilder commands = new StringBuilder();
    for (int i = 0; i < count; i++) {
      float scale = i % 2 == 0 ? 128 : 1000000;
      float x = (random.nextFloat() * 2 - 1) * scale, y = (random.nextFloat() * 2 - 1) * scale;
      float dx = random.nextFloat() * 1024, dy = random.nextFloat() * 1024;
      bounds = new MoverQueries.ModelBounds(new Vec3(x, y, -8), new Vec3(x + dx, y + dy, 8));
      float ox = (random.nextFloat() * 2 - 1) * scale, oy = (random.nextFloat() * 2 - 1) * scale;
      first = new MoverQueries.Entity(i % 17 == 0 ? 1 : 4, model, new Vec3(ox, oy, 0));
      hit = new MoverQueries.Entity(i % 3, i % 11 == 0 ? model + 1 : model, new Vec3(0, 0, 0));
      float px = x + ox + (random.nextBoolean() ? -16 : dx + 16), py = y + oy + dy * .5f;
      if (i % 4 == 0) {
        px = x + ox + dx * .5f;
        py = y + oy + (random.nextBoolean() ? -16 : dy + 16);
      }
      if (i % 3 == 0) {
        px = Math.nextDown(px);
        py = Math.nextDown(py);
      } else if (i % 3 == 1) {
        px = Math.nextUp(px);
        py = Math.nextUp(py);
      }
      var origin = new Vec3(px, py, (random.nextFloat() * 2 - 1) * scale);
      fraction = i % 4 == 0 ? 1 : i % 4 == 1 ? 0 : random.nextFloat();
      solids = i % 23 == 0 ? 3 : i % 19 == 0 ? 1 : 0;
      hitNumber = i % 13 == 0 ? 17 : 18;
      int entity = i % 3 == 0 ? 17 : 1;
      expected.add(query.onMover(origin, entity, reach));
      String command =
          "box "
              + point(bounds.min())
              + " "
              + point(bounds.max())
              + "\nentityfull 17 "
              + model
              + " "
              + point(first.origin())
              + " "
              + first.type()
              + " 3\nentityfull 18 "
              + hit.modelIndex()
              + " 0 0 0 "
              + hit.type()
              + " 0\nforce "
              + fraction
              + " "
              + hitNumber
              + " "
              + solids
              + " 1 1022 0\nonmover 689 "
              + entity
              + " "
              + point(origin)
              + "\n";
      replays.add(command);
      commands.append(command);
    }
    var output = Path.of(".tools/mover-query-oracle/native.log");
    var process =
        new ProcessBuilder(
                Path.of(args[1]).toAbsolutePath().toString(),
                Path.of(args[0]).toAbsolutePath().toString(),
                "q3dm19")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(output.toFile())
            .start();
    try (var input = process.getOutputStream()) {
      input.write(commands.toString().getBytes(StandardCharsets.US_ASCII));
    }
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0)
      throw new AssertionError("Native failed");
    var lines = Files.readAllLines(output).stream().filter(s -> s.startsWith("ONMOVER ")).toList();
    if (lines.size() != count) throw new AssertionError(lines.size());
    int differences = 0;
    for (int i = 0; i < count; i++) {
      boolean value = lines.get(i).equals("ONMOVER 1");
      if (value != expected.get(i)) {
        if (differences++ < 10)
          System.out.println(
              "DIFF "
                  + i
                  + " java="
                  + expected.get(i)
                  + " native="
                  + value
                  + "\n"
                  + replays.get(i));
      }
    }
    System.out.println("OnMover requests=" + count + " differences=" + differences);
    if (differences != 0) throw new AssertionError(differences);
  }

  static String point(Vec3 p) {
    return Float.toString((float) p.x())
        + " "
        + Float.toString((float) p.y())
        + " "
        + Float.toString((float) p.z());
  }
}

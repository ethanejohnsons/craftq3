import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Read-only native differential of AAS item localization and ordered presence-expanded links. */
class AuditGoalPlacement {
  public static void main(String[] args) throws Exception {
    Path pack = Path.of(args.length > 0 ? args[0] : "run/craftq3/games/baseq3/pak0.pk3");
    Path oracle = Path.of(args.length > 1 ? args[1] : ".tools/aas-oracle/probe");
    int maps = 0, links = 0, goals = 0;
    double maximumError = 0;
    var random = new Random(0x60a1);
    try (var zip = new ZipFile(pack.toFile())) {
      var entries = zip.stream().filter(entry -> entry.getName().endsWith(".aas")).toList();
      for (var entry : entries) {
        String mapName = entry.getName().substring(5, entry.getName().length() - 4);
        if (args.length > 2 && !args[2].equals(mapName)) continue;
        AasMap map;
        try (var data = zip.getInputStream(entry)) { map = AasReader.read(data.readNBytes(AasReader.MAX_BYTES + 1)); }
        var locator = new AasGoalLocator(new AasNavigation(map));
        var process = new ProcessBuilder(oracle.toAbsolutePath().toString(), pack.toAbsolutePath().toString(), mapName)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var input = process.outputWriter(StandardCharsets.US_ASCII); var output = process.inputReader(StandardCharsets.US_ASCII)) {
          require(("READY 1 " + mapName).equals(output.readLine()), "Native map readiness " + mapName);
          for (int sample = 0; sample < 400; sample++) {
            Vec3 origin = map.areas().get(1 + random.nextInt(map.areas().size() - 1)).center();
            if (sample % 3 != 0) origin = origin.add(new Vec3(random.nextInt(201)-100, random.nextInt(201)-100, random.nextInt(201)-100));
            origin = floats(origin);
            var mins = new Vec3(-15, -15, -15); var maxs = new Vec3(15, 15, 15);
            Vec3 low = floats(origin.add(mins)), high = floats(origin.add(maxs));
            int presence = sample % 2 == 0 ? 4 : 2;
            send(input, "links " + vector(low) + " " + vector(high) + " -1 " + presence);
            String result = output.readLine();
            var actual = locator.linkedAreas(low, high, presence);
            String expected = "LINKS" + actual.stream().map(number -> " " + number).reduce("", String::concat);
            require(expected.equals(result), mapName + " sample " + sample + " links at " + origin + " presence " + presence + "\nnative=" + result + "\njava=" + expected);
            links++;
            send(input, "best " + vector(origin) + " " + vector(mins) + " " + vector(maxs));
            String[] nativeGoal = output.readLine().split(" ");
            var goal = locator.bestReachableArea(origin, mins, maxs);
            require(goal.area() == Integer.parseInt(nativeGoal[1]), mapName + " sample " + sample + " area at " + origin + " native=" + Arrays.toString(nativeGoal) + " java=" + goal);
            for (int axis = 0; axis < 3; axis++) {
              double value = axis == 0 ? goal.origin().x() : axis == 1 ? goal.origin().y() : goal.origin().z();
              double error = Math.abs(value - Float.parseFloat(nativeGoal[axis + 2])); maximumError = Math.max(maximumError, error);
              require(error <= .001, mapName + " sample " + sample + " goal origin at " + origin + " native=" + Arrays.toString(nativeGoal) + " java=" + goal);
            }
            goals++;
          }
        } finally {
          if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
          require(process.exitValue() == 0, "Native map process exit " + mapName);
        }
        maps++; System.out.println("PASS " + mapName);
      }
    }
    System.out.printf("Goal placement maps=%d links=%d goals=%d maximumPositionError=%.9g%n", maps, links, goals, maximumError);
  }
  static Vec3 floats(Vec3 v) { return new Vec3((float)v.x(),(float)v.y(),(float)v.z()); }
  static String vector(Vec3 v) { return (float)v.x() + " " + (float)v.y() + " " + (float)v.z(); }
  static void send(BufferedWriter input, String message) throws IOException { input.write(message);input.newLine();input.flush(); }
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

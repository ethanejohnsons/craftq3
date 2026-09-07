import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Compares the measured run-start wrapper with an unchanged native public-call oracle. */
class AuditJumpRunStart {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException("AuditJumpRunStart <PK3> <native-oracle> [queries/map]");
    int count = args.length == 3 ? Integer.parseInt(args[2]) : 1000;
    if (count < 1 || count > 10000) throw new IllegalArgumentException("Invalid query count");
    int maps = 0, total = 0;
    long begin = System.nanoTime();
    try (var zip = new ZipFile(Path.of(args[0]).toFile())) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        byte[] bytes;
        try (var in = zip.getInputStream(entry)) {
          bytes = in.readNBytes(AasReader.MAX_BYTES + 1);
        }
        var reaches =
            AasReader.read(bytes).reachabilities().stream()
                .filter(r -> r.baseTravelType() == 5)
                .toList();
        if (reaches.isEmpty()) continue;
        String map = entry.getName().substring(5, entry.getName().length() - 4);
        var requests = new ArrayList<AasMovementPredictor.Request>();
        var results = new ArrayList<Vec3>();
        var commands = new StringBuilder("runauto\n");
        Random random = new Random(30513);
        for (int i = 0; i < count; i++) {
          var reach = reaches.get(random.nextInt(reaches.size()));
          int event = i % 256;
          Vec3 predicted =
              new Vec3(
                  random.nextFloat() * 4096 - 2048,
                  random.nextFloat() * 4096 - 2048,
                  random.nextFloat() * 4096 - 2048);
          var run =
              new JumpRunStart(
                  request -> {
                    requests.add(request);
                    return new AasMovementPredictor.Prediction(
                        predicted, 0, ZERO, 2, event, 0, 0, 0, Optional.empty());
                  });
          results.add(run.calculate(reach.start(), reach.end()));
          commands.append("prediction ").append(i % 2).append(' ').append(event).append(' ');
          point(commands, predicted);
          commands.append("\nrun ");
          point(commands, reach.start());
          point(commands, reach.end());
          commands.append('\n');
        }
        var nativeLines = oracle(args[1], args[0], map, commands.toString());
        var nativeRequests = nativeLines.stream().filter(l -> l.startsWith("PREDICT ")).toList();
        var nativeResults = nativeLines.stream().filter(l -> l.startsWith("RUN ")).toList();
        if (nativeRequests.size() != count || nativeResults.size() != count)
          throw new AssertionError("Missing native results for " + map);
        for (int i = 0; i < count; i++) {
          var r = requests.get(i);
          String[] q = nativeRequests.get(i).split(" ");
          boolean ok =
              q[1].equals("e-1")
                  && q[3].equals("presence2")
                  && q[4].equals("ground1")
                  && q[7].equals("cf1")
                  && q[8].equals("f2")
                  && q[10].equals("ev124")
                  && q[11].equals("area0")
                  && q[12].equals("vis0");
          ok &=
              vector(q[2].substring(1)).equals(r.origin())
                  && vector(q[5].substring(1)).equals(r.velocity())
                  && vector(q[6].substring(1)).equals(r.commandMove());
          ok &=
              Float.floatToIntBits(Float.parseFloat(q[9].substring(2)))
                  == Float.floatToIntBits(r.frameTime());
          String[] result = nativeResults.get(i).split(" ");
          ok &=
              new Vec3(
                      Float.parseFloat(result[1]),
                      Float.parseFloat(result[2]),
                      Float.parseFloat(result[3]))
                  .equals(results.get(i));
          if (!ok)
            throw new AssertionError(
                map
                    + " request "
                    + i
                    + " Java="
                    + r
                    + "/"
                    + results.get(i)
                    + " native="
                    + nativeRequests.get(i)
                    + "/"
                    + nativeResults.get(i));
        }
        maps++;
        total += count;
        System.out.println(map + " " + count + " exact");
      }
    }
    System.out.printf(
        Locale.ROOT,
        "RUNSTART %d maps %d requests exact in %.3fs%n",
        maps,
        total,
        (System.nanoTime() - begin) / 1e9);
  }

  private static Vec3 vector(String value) {
    String[] parts = value.split(",");
    return new Vec3(
        Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
  }

  private static void point(StringBuilder out, Vec3 value) {
    out.append((float) value.x())
        .append(' ')
        .append((float) value.y())
        .append(' ')
        .append((float) value.z())
        .append(' ');
  }

  private static List<String> oracle(String executable, String pk3, String map, String commands)
      throws Exception {
    Process process =
        new ProcessBuilder(
                Path.of(executable).toAbsolutePath().toString(),
                Path.of(pk3).toAbsolutePath().toString(),
                map)
            .redirectErrorStream(true)
            .start();
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<List<String>> output =
          executor.submit(
              () -> {
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                  return reader
                      .lines()
                      .filter(l -> l.startsWith("PREDICT ") || l.startsWith("RUN "))
                      .toList();
                }
              });
      try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
        writer.write(commands);
      }
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("Native timeout " + map);
      }
      if (process.exitValue() != 0) throw new AssertionError("Native failure " + map);
      return output.get(5, TimeUnit.SECONDS);
    } finally {
      process.destroyForcibly();
    }
  }
}

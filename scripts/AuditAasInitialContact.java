import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.botlib.movement.AasMovementWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Compares original, authored AAS partitions through native and production prediction.
 *
 * <p>Arguments: native AasSwimmingMapOracle executable, original pak0.pk3. The archive is read
 * directly to initialize the native library; each measured partition is then authored metadata.
 * No engine implementation bodies or user asset modifications are involved.
 */
class AuditAasInitialContact {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final HexFormat HEX = HexFormat.of();
  private static final Set<Integer> FLOAT_WORDS = Set.of(0, 1, 2, 4, 5, 6, 8, 9, 10, 11, 19);
  private static final double FLOAT_TOLERANCE = .000001;

  record Sample(Vec3 normal, boolean front, AasMovementPredictor.Request request) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected native observer executable and original pak0.pk3");
    }
    var samples = samples();
    var input = new StringBuilder();
    for (var sample : samples) {
      var request = sample.request();
      input.append("fixtureplane ").append(vector(sample.normal())).append(" 0 ")
          .append(sample.front() ? 1 : 0).append('\n');
      input.append("predictswim ").append(vector(request.origin())).append(' ')
          .append(vector(request.velocity())).append(' ').append(vector(request.commandMove())).append(' ')
          .append(request.presence()).append(' ').append(request.onGround() ? 1 : 0).append(' ')
          .append(request.commandFrames()).append(' ').append(request.maxFrames()).append(' ')
          .append(request.frameTime()).append(' ').append(request.stopEvents()).append('\n');
    }
    var builder = new ProcessBuilder(args[0], args[1], "q3dm1")
        .redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("DRY_CONTENTS", "1");
    var process = builder.start();
    String[] rows;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var read = executor.submit(() -> new String(
          process.getInputStream().readNBytes(32 * 1024 * 1024), StandardCharsets.US_ASCII));
      try (var stream = process.getOutputStream()) {
        stream.write(input.toString().getBytes(StandardCharsets.US_ASCII));
      }
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("Native timeout");
      }
      if (process.exitValue() != 0) throw new AssertionError("Native exit " + process.exitValue());
      rows = read.get().lines().filter(s -> s.startsWith("SWIM_RESULT ")).toArray(String[]::new);
    }
    if (rows.length != samples.size()) throw new AssertionError("Native row count " + rows.length);
    int mismatches = 0, matchingFailures = 0, startSolid = 0, bitDifferences = 0;
    double worst = 0;
    for (int i = 0; i < samples.size(); i++) {
      var sample = samples.get(i);
      var world = new AasMovementWorld(new AasNavigation(map(sample.normal(), sample.front())),
          (entity, request) -> { throw new AssertionError("No dynamic fixture entities"); }, p -> 0);
      var result = new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults())
          .predict(sample.request());
      var tokens = rows[i].split(" ");
      boolean ok = tokens[1].equals("1");
      if (!ok && result.isEmpty()) matchingFailures++;
      if (result.isPresent() && result.get().trace().orElseThrow().startSolid()) startSolid++;
      boolean mismatch = ok != result.isPresent();
      if (ok && result.isPresent()) {
        byte[] nativeBytes = HEX.parseHex(tokens[2]), javaBytes = pack(result.get());
        if (!Arrays.equals(nativeBytes, javaBytes)) bitDifferences++;
        var nativeWords = ByteBuffer.wrap(nativeBytes).order(ByteOrder.LITTLE_ENDIAN);
        var javaWords = ByteBuffer.wrap(javaBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int word = 0; word < 21; word++) {
          if (FLOAT_WORDS.contains(word)) {
            double error = Math.abs((double) nativeWords.getFloat(word * 4) - javaWords.getFloat(word * 4));
            worst = Math.max(worst, error);
            mismatch |= !Double.isFinite(error) || error > FLOAT_TOLERANCE;
          } else {
            mismatch |= nativeWords.getInt(word * 4) != javaWords.getInt(word * 4);
          }
        }
      }
      if (mismatch) {
        mismatches++;
        if (mismatches < 6) {
          System.out.println("BAD " + i + " " + sample + " native=" + rows[i] + " java=" + result);
        }
      }
    }
    System.out.println("RAW_AAS cases=" + samples.size() + " mismatches=" + mismatches
        + " matching-failures=" + matchingFailures + " returned-startsolid=" + startSolid
        + " float-bit-differences=" + bitDifferences + " maxError=" + worst);
    if (mismatches != 0) throw new AssertionError("Native mismatch");
  }

  private static List<Sample> samples() {
    var random = new Random(333853);
    var samples = new ArrayList<Sample>();
    Vec3[] normals = {
      new Vec3(0, 0, 1), new Vec3(0, 0, -1), new Vec3(1, 0, 0), new Vec3(-1, 0, 0),
      new Vec3(.6f, 0, .8f), new Vec3(.6f, 0, -.8f), new Vec3(0, .8f, .6f), new Vec3(0, .8f, -.6f)
    };
    for (int i = 0; i < 20_000; i++) {
      var normal = normals[random.nextInt(normals.length)];
      boolean front = random.nextBoolean();
      var origin = new Vec3(randomFloat(random, 200), randomFloat(random, 200), randomFloat(random, 200));
      var velocity = new Vec3(randomFloat(random, 1200), randomFloat(random, 1200), randomFloat(random, 1200));
      var command = new Vec3(randomFloat(random, 400), randomFloat(random, 400),
          random.nextBoolean() ? 0 : randomFloat(random, 400));
      int presence = random.nextBoolean() ? 2 : 4, commandFrames = random.nextInt(3);
      int maxFrames = 1 + random.nextInt(3), events = new int[] {0, 1, 2, 32, 60, 63}[random.nextInt(6)];
      boolean ground = random.nextBoolean();
      float duration = new float[] {.05f, .1f, .2f, .5f, 1}[random.nextInt(5)];
      samples.add(new Sample(normal, front, new AasMovementPredictor.Request(-1, origin, presence,
          ground, velocity, command, commandFrames, maxFrames, duration, events)));
    }
    // Durable minimal versions of the initial-solid discrepancy, also covered by unit tests.
    samples.add(new Sample(new Vec3(0, 0, -1), true, contact(-1000)));
    samples.add(new Sample(new Vec3(0, .8f, -.6f), true, contact(-620)));
    return samples;
  }

  private static AasMovementPredictor.Request contact(float verticalVelocity) {
    return new AasMovementPredictor.Request(-1, new Vec3(0, 0, 100), 2, false,
        new Vec3(0, 0, verticalVelocity), ZERO, 0, 1, .1f, AasMovementPredictor.HIT_GROUND_DAMAGE);
  }

  private static float randomFloat(Random random, float scale) {
    return (random.nextFloat() * 2 - 1) * scale;
  }

  private static String vector(Vec3 value) {
    return (float) value.x() + " " + (float) value.y() + " " + (float) value.z();
  }

  private static AasMap map(Vec3 normal, boolean front) {
    var indices = new AasMap.Indices(new int[0]);
    return new AasMap(4, 0, List.of(), List.of(), List.of(),
        List.of(new AasMap.Plane(normal, 0, 3), new AasMap.Plane(normal.scale(-1), 0, 3)),
        List.of(), indices, List.of(), indices,
        List.of(new AasMap.Area(0, 0, 0, ZERO, ZERO, ZERO), new AasMap.Area(1, 0, 0, ZERO, ZERO, ZERO)),
        List.of(new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0), new AasMap.AreaSettings(0, 1, 6, 0, 0, 0, 0)),
        List.of(), List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, front ? -1 : 0, front ? 0 : -1)),
        List.of(), indices, List.of());
  }

  private static byte[] pack(AasMovementPredictor.Prediction result) {
    var trace = result.trace().orElseThrow();
    var bytes = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
    putVector(bytes, result.endPosition());
    bytes.putInt(result.endArea());
    putVector(bytes, result.velocity());
    bytes.putInt(trace.startSolid() ? 1 : 0).putFloat(trace.fraction());
    putVector(bytes, trace.endPosition());
    bytes.putInt(trace.entity()).putInt(trace.lastArea()).putInt(trace.area()).putInt(trace.planeNumber())
        .putInt(result.presence()).putInt(result.stopEvent()).putInt(result.endContents())
        .putFloat(result.time()).putInt(result.frames());
    return bytes.array();
  }

  private static void putVector(ByteBuffer bytes, Vec3 value) {
    bytes.putFloat((float) value.x()).putFloat((float) value.y()).putFloat((float) value.z());
  }
}

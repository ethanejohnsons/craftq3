import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.botlib.item.JumpPadLaunch;
import dev.bluevista.craftq3.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.util.*;

/**
 * Authored metadata and transparent native calls; the supplied PK3 is opened read-only by the host.
 */
class AuditJumpPadLaunch {
  static final class World implements AasMovementPredictor.World {
    int traces;

    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      traces++;
      if (presence != 4 || entity != -1 || start.z() != ((float) end.z() + 64))
        throw new AssertionError("Launch trace ABI");
      var endpoint =
          new Vec3(
              (float) start.x() + ((float) end.x() - (float) start.x()),
              (float) start.y() + ((float) end.y() - (float) start.y()),
              (float) start.z() + ((float) end.z() - (float) start.z()));
      return new AasPresenceTrace.Result(false, 1, endpoint, 0, 0, 0, 0, 0);
    }

    public int contents(Vec3 p) {
      throw new AssertionError();
    }

    public int presence(Vec3 p) {
      throw new AssertionError();
    }

    public Vec3 planeNormal(int n) {
      throw new AssertionError();
    }

    public int area(Vec3 p) {
      throw new AssertionError();
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException(
          "AuditJumpPadLaunch <pak0.pk3> <native-jump-pad-observer>");
    int total = 0, differences = 0;
    for (float gravity : new float[] {800, 237.5f}) {
      var builder =
          new ProcessBuilder(args[1], args[0], "q3dm12")
              .redirectError(ProcessBuilder.Redirect.DISCARD);
      builder.environment().put("DRY_CONTENTS", "1");
      builder.environment().put("PAD_TRACE", "0 1");
      builder.environment().put("PAD_GRAVITY", Float.toString(gravity));
      var process = builder.start();
      var random = new Random(389624);
      try (var input = new BufferedReader(new InputStreamReader(process.getInputStream()));
          var output = new PrintWriter(process.getOutputStream(), true)) {
        String row;
        while ((row = input.readLine()) != null && !row.startsWith("READY ")) {}
        if (row == null) throw new AssertionError("Native launch startup EOF");
        for (int index = 0; index < 10000; index++) {
          float[] lo = new float[3], hi = new float[3], target = new float[3];
          for (int k = 0; k < 3; k++) {
            lo[k] = random.nextFloat() * 20000 - 10000;
            hi[k] = lo[k] + random.nextFloat() * 199 + 1;
            target[k] =
                (lo[k] + hi[k]) * .5f
                    + (k == 2 ? random.nextFloat() * 999 + .1f : random.nextFloat() * 4000 - 2000);
          }
          Vec3 minimum = vec(lo), maximum = vec(hi), destination = vec(target);
          var entities =
              List.of(
                  Map.of("classname", "worldspawn"),
                  Map.of("classname", "trigger_push", "model", "*1", "target", "t"),
                  Map.of("targetname", "t", "origin", text(destination)));
          var model = new BspMap.Model(new BspMap.Bounds(minimum, maximum), 0, 0, 0, 0);
          var bsp =
              new BspMap(
                  entities,
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(model, model),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0])));
          var world = new World();
          var result = new JumpPadLaunch(bsp, world, s -> {}).resolve(1, gravity).orElseThrow();
          output.println("bounds " + text(minimum) + " " + text(maximum));
          output.println(
              "entities { \"classname\" \"worldspawn\" } { \"classname\" \"trigger_push\" \"model\""
                  + " \"*1\" \"target\" \"t\" } { \"targetname\" \"t\" \"origin\" \""
                  + text(destination)
                  + "\" }");
          output.println("padinfo 2");
          while ((row = input.readLine()) != null && !row.startsWith("PAD ")) {}
          if (row == null) throw new AssertionError("Native launch EOF");
          String[] fields = row.split(" ");
          boolean mismatch = !fields[1].equals("1") || world.traces != 1;
          var expected =
              new Vec3[] {result.origin(), result.minimum(), result.maximum(), result.velocity()};
          for (int k = 0; k < 4; k++)
            mismatch |=
                !expected[k].equals(
                    new Vec3(
                        Float.parseFloat(fields[2 + k * 3]),
                        Float.parseFloat(fields[3 + k * 3]),
                        Float.parseFloat(fields[4 + k * 3])));
          if (mismatch) {
            differences++;
            if (differences <= 3)
              System.out.println(
                  "MISMATCH gravity=" + gravity + " native=" + row + " java=" + result);
          }
          total++;
        }
      } finally {
        process.destroy();
      }
    }
    System.out.println("LAUNCH cases=" + total + " exactMismatches=" + differences);
    if (differences != 0) throw new AssertionError("Launch differential failed");
  }

  static Vec3 vec(float[] v) {
    return new Vec3(v[0], v[1], v[2]);
  }

  static String text(Vec3 v) {
    return (float) v.x() + " " + (float) v.y() + " " + (float) v.z();
  }
}

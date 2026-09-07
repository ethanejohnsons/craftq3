import dev.bluevista.craftq3.botlib.movement.AasTargetBox;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.nio.*;
import java.util.*;

/**
 * Production/native target clipping, including untouched native miss outputs. No assets required.
 */
class AuditAasTargetBox {
  record Case(int presence, Vec3 start, Vec3 end, Vec3 min, Vec3 max) {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("AuditAasTargetBox <native-target-box-oracle>");
    var random = new Random(66764);
    var cases = new ArrayList<Case>();
    var low = new Vec3(-10, -10, -10);
    var high = new Vec3(10, 10, 10);
    for (int presence : new int[] {2, 4}) {
      for (Vec3 start :
          List.of(
              new Vec3(-100, 25, 0),
              new Vec3(-100, Math.nextDown(25f), 0),
              new Vec3(0, 0, 0),
              new Vec3(-100, -100, 0)))
        cases.add(new Case(presence, start, new Vec3(100, 100, 0), low, high));
      cases.add(new Case(presence, new Vec3(0, 0, 0), new Vec3(0, 0, 0), low, high));
    }
    for (int i = 0; i < 100000; i++) {
      var start = point(random, 200);
      float[] end = new float[3], min = new float[3], max = new float[3];
      float[] s = {(float) start.x(), (float) start.y(), (float) start.z()};
      for (int axis = 0; axis < 3; axis++) {
        end[axis] = random.nextFloat() < .2 ? s[axis] : (random.nextFloat() * 400 - 200);
        min[axis] = random.nextFloat() * 200 - 100;
        max[axis] = min[axis] + random.nextFloat() * 150;
      }
      cases.add(new Case(random.nextBoolean() ? 2 : 4, start, vec(end), vec(min), vec(max)));
    }
    var process =
        new ProcessBuilder(args[0]).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    int differences = 0, hits = 0;
    try (var input = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var output = new PrintWriter(process.getOutputStream(), true)) {
      var writer =
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (var c : cases)
                      output.println(
                          "clip "
                              + c.presence()
                              + " "
                              + text(c.start())
                              + " "
                              + text(c.end())
                              + " "
                              + text(c.min())
                              + " "
                              + text(c.max())
                              + " 0.625 1");
                    output.close();
                  });
      for (var c : cases) {
        String row = input.readLine();
        if (row == null) throw new AssertionError("Native clipping EOF");
        var fields = row.split(" ");
        var bytes =
            ByteBuffer.wrap(HexFormat.of().parseHex(fields[2])).order(ByteOrder.LITTLE_ENDIAN);
        var result = new AasTargetBox(c.min(), c.max()).clip(c.start(), c.end(), c.presence());
        boolean hit = fields[1].equals("1"), bad = hit != result.isPresent();
        if (hit && result.isPresent()) {
          var trace = result.get();
          hits++;
          bad |=
              bytes.getInt(0) != 0 || bytes.getInt(4) != Float.floatToRawIntBits(trace.fraction());
          bad |=
              bytes.getInt(8) != Float.floatToRawIntBits((float) trace.endPosition().x())
                  || bytes.getInt(12) != Float.floatToRawIntBits((float) trace.endPosition().y())
                  || bytes.getInt(16) != Float.floatToRawIntBits((float) trace.endPosition().z());
          for (int offset = 20; offset < 36; offset += 4) bad |= bytes.getInt(offset) != 0;
        }
        if (bad) {
          differences++;
          if (differences <= 3)
            System.out.println("MISMATCH " + c + " native=" + row + " java=" + result);
        }
      }
      writer.join();
    } finally {
      process.destroy();
    }
    System.out.println(
        "TARGET_CLIP cases=" + cases.size() + " hits=" + hits + " exactMismatches=" + differences);
    if (differences != 0) throw new AssertionError("Target clipping differential failed");
  }

  static Vec3 point(Random r, float range) {
    return new Vec3(
        r.nextFloat() * range * 2 - range,
        r.nextFloat() * range * 2 - range,
        r.nextFloat() * range * 2 - range);
  }

  static Vec3 vec(float[] p) {
    return new Vec3(p[0], p[1], p[2]);
  }

  static String text(Vec3 p) {
    return (float) p.x() + " " + (float) p.y() + " " + (float) p.z();
  }
}

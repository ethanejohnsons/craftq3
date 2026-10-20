package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

final class MovementObstructionTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void rawDirectionsPreserveMagnitudeAndCropAtBothSignedVerticalBoundaries() {
    var calls = new ArrayList<TraceRequest>();
    TraceWorld world =
        new TraceWorld() {
          public TraceResult trace(TraceRequest request) {
            calls.add(request);
            return TraceResult.clear(request);
          }

          public int pointContents(Vec3 point, int mask, int ignore) {
            return 0;
          }
        };
    var obstruction = new MovementObstruction(world, a -> 1);
    var input = new MovementInit(ZERO, ZERO, ZERO, 7, 0, .1f, 2, ZERO, 0);
    for (float z : new float[] {-.7f, .7f, -Math.nextUp(.7f), Math.nextUp(.7f)}) {
      calls.clear();
      obstruction.check(input, 1, new Vec3(100, 0, z));
      assertEquals(1, calls.size());
      var query = calls.getFirst();
      boolean cropped = Math.abs(z) <= .7f;
      assertEquals(new Vec3(-15, -15, cropped ? -6 : -24), query.mins());
      assertEquals(new Vec3(15, 15, cropped ? 22 : 32), query.maxs());
      assertEquals(new Vec3(300, 0, z * 3), query.end());
      assertEquals(33619969, query.contentsMask());
      assertEquals(7, query.ignoreEntity());
    }
  }
}

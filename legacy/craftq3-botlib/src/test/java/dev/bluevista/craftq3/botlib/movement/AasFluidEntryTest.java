package dev.bluevista.craftq3.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AasFluidEntryTest {
  @Test
  void areaAndBspFluidBitsCombineEventsWhileOutputContentsRemainBspOnly() {
    for (int aas : new int[] {0, 1, 2, 4, 7}) {
      for (int bsp : new int[] {0, 8, 16, 32, 56}) {
        for (int mask : new int[] {0, 4, 8, 16, 28}) {
          var world = new World(aas, bsp);
          var result = predict(world, 0, mask);
          int expected =
              ((aas & 1) != 0 || (bsp & 32) != 0 ? 4 : 0)
                  | ((aas & 2) != 0 || (bsp & 8) != 0 ? 16 : 0)
                  | ((aas & 4) != 0 || (bsp & 16) != 0 ? 8 : 0);
          expected &= mask;
          assertEquals(expected, result.stopEvent());
          assertEquals(expected == 0 ? 0 : bsp, result.endContents());
          assertEquals(1, world.areaContentsQueries);
          assertEquals(2, world.contentsQueries);
        }
      }
    }
  }

  @Test
  void internalVelocityThresholdSkipsBothEntrySourcesOnlyAboveTen() {
    for (float velocity : new float[] {180, Math.nextUp(180f)}) {
      var world = new World(1, 0);
      var result = predict(world, velocity, 4);
      boolean qualifies = velocity == 180;
      assertEquals(qualifies ? 4 : 0, result.stopEvent());
      assertEquals(qualifies ? 1 : 0, world.areaContentsQueries);
      assertEquals(qualifies ? 2 : 1, world.contentsQueries);
    }
  }

  @Test
  void bspSamplePrecedesAreaLookupAndFluidResultReusesThatArea() {
    var world = new World(1, 16);
    var result = predict(world, 0, 28);
    assertEquals(12, result.stopEvent());
    assertEquals(16, result.endContents());
    assertEquals(7, result.endArea());
    assertEquals(List.of("contents", "trace", "contents", "area", "areaContents"), world.calls);
    assertEquals(0, result.trace().orElseThrow().fraction());
  }

  private static AasMovementPredictor.Prediction predict(World world, float velocityZ, int mask) {
    var request =
        new AasMovementPredictor.Request(
            3,
            new Vec3(0, 0, 100),
            2,
            false,
            new Vec3(0, 0, velocityZ),
            new Vec3(0, 0, 0),
            0,
            1,
            .1f,
            mask);
    return new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults())
        .predict(request)
        .orElseThrow();
  }

  private static final class World implements AasMovementPredictor.World {
    final int aas, bsp;
    final List<String> calls = new ArrayList<>();
    int contentsQueries, areaContentsQueries;

    World(int aas, int bsp) {
      this.aas = aas;
      this.bsp = bsp;
    }

    @Override
    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      calls.add("trace");
      return new AasPresenceTrace.Result(false, 1, end, 0, 7, 0, 0, 1);
    }

    @Override
    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, 1);
    }

    @Override
    public int area(Vec3 point) {
      calls.add("area");
      return 7;
    }

    @Override
    public int presence(Vec3 point) {
      throw new AssertionError();
    }

    @Override
    public int contents(Vec3 point) {
      calls.add("contents");
      return ++contentsQueries == 1 ? 0 : bsp;
    }

    @Override
    public int areaContents(int area) {
      calls.add("areaContents");
      assertEquals(7, area);
      areaContentsQueries++;
      return aas;
    }
  }
}

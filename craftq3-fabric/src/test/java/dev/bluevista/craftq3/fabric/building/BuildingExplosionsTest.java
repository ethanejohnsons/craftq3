package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import org.junit.jupiter.api.Test;

class BuildingExplosionsTest {
  private static Vec3 p(double x, double y, double z) {
    return new Vec3(x, y, z);
  }

  private static BuildingGeometry wall(int contents) {
    return new BuildingGeometry(
        new BoxTraceWorld(p(0, -32, -32), p(.32, 32, 32), contents, 0, 1022),
        new CoordinateTransform(32, p(4096, 0, 0)));
  }

  @Test
  void thinWallsOccludeInBothDirectionsBetweenNativeSamples() {
    var wall = wall(1);
    assertTrue(wall.occludes(p(4095.9, 0, 0), p(4096.2, 0, 0)));
    assertTrue(wall.occludes(p(4096.2, 0, 0), p(4095.9, 0, 0)));
    assertFalse(wall.occludes(p(4095.9, 0, 0), p(4095.99, 0, 0)));
    assertFalse(wall.occludes(p(4096.02, 0, 0), p(4096.2, 0, 0)));
  }

  @Test
  void solidOriginsAndStationaryInsideSamplesAreOccluded() {
    var wall = wall(1);
    var inside = p(4096.005, 0, 0);
    assertTrue(wall.occludes(inside, p(4097, 0, 0)));
    assertTrue(wall.occludes(inside, inside));
    assertFalse(wall.occludes(p(4095, 0, 0), p(4095, 0, 0)));
  }

  @Test
  void playerClipAndClearPathsDoNotBlockBlast() {
    assertFalse(wall(0x10000).occludes(p(4095, 0, 0), p(4097, 0, 0)));
    assertFalse(wall(1).occludes(p(4095, 2, 0), p(4097, 2, 0)));
  }
}

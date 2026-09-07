package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

class BuildingProjectilesTest {
  private static dev.bluevista.craftq3.core.math.Vec3 q(double x, double y, double z) {
    return new dev.bluevista.craftq3.core.math.Vec3(x, y, z);
  }

  private static BuildingGeometry cube(int contents) {
    return new BuildingGeometry(
        new BoxTraceWorld(q(0, 0, 0), q(32, 32, 32), contents, 0, 1022),
        new CoordinateTransform(32, q(0, 0, 0)));
  }

  private static BlockHitResult miss(Vec3 end) {
    return BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
  }

  @Test
  void impactsKeepAllSixSurfaceDirections() {
    var center = new Vec3(.5, .5, -.5);
    for (var face : Direction.values()) {
      var normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
      var start = center.add(normal.scale(2));
      var result = BuildingProjectiles.nearer(cube(1), start, center, miss(center));
      assertEquals(HitResult.Type.BLOCK, result.getType());
      assertEquals(face, result.getDirection());
      assertEquals(.5, result.getLocation().distanceTo(center), .005);
    }
  }

  @Test
  void nearestNativeBlockWinsAndBspReplacesFartherHit() {
    var start = new Vec3(-2, .5, -.5);
    var end = new Vec3(2, .5, -.5);
    var near =
        new BlockHitResult(new Vec3(-1, .5, -.5), Direction.WEST, new BlockPos(-1, 0, -1), false);
    assertSame(near, BuildingProjectiles.nearer(cube(1), start, end, near));
    var far = new BlockHitResult(end, Direction.WEST, new BlockPos(2, 0, -1), false);
    var hit = BuildingProjectiles.nearer(cube(1), start, end, far);
    assertEquals(0, hit.getLocation().x, .005);
    assertEquals(new BlockPos(0, 0, -1), hit.getBlockPos());
    assertSame(hit, BuildingProjectiles.nearer(cube(1), start, end, hit));
  }

  @Test
  void playerClipDoesNotStopProjectilesAndMissesPreserveNativeResult() {
    var start = new Vec3(-2, .5, -.5);
    var end = new Vec3(2, .5, -.5);
    var miss = miss(end);
    assertSame(miss, BuildingProjectiles.nearer(cube(0x10000), start, end, miss));
    var high = new Vec3(-2, 3, -.5);
    var highEnd = new Vec3(2, 3, -.5);
    var highMiss = miss(highEnd);
    assertSame(highMiss, BuildingProjectiles.nearer(cube(1), high, highEnd, highMiss));
  }

  @Test
  void rayKeepsFractionalHeightAndRegionTransform() {
    var geometry =
        new BuildingGeometry(
            new BoxTraceWorld(q(-32, -32, -32), q(32, 32, 16), 1, 0, 1022),
            new CoordinateTransform(32, q(4096, 10, 0)));
    var ray = geometry.ray(q(4096, 12, 0), q(4096, 9, 0)).orElseThrow();
    assertEquals(10.5, ray.point().y(), .005);
    assertEquals(0, ray.normal().x(), 1e-9);
    assertEquals(1, ray.normal().y(), 1e-9);
    assertEquals(0, ray.normal().z(), 1e-9);
  }

  @Test
  void embeddedArrowsUseSolidOccupancyWithoutPlayerClip() {
    var body = new BuildingGeometry.Box(q(.45, .45, -.55), q(.55, .55, -.45));
    assertFalse(cube(1).solidClear(body));
    assertTrue(cube(0x10000).solidClear(body));
    assertFalse(cube(0x10000).clear(body));
  }
}

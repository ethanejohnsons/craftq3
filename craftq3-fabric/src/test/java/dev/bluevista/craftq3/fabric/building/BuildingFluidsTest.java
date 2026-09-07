package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

class BuildingFluidsTest {
  private static dev.bluevista.craftq3.core.math.Vec3 q(double x, double y, double z) {
    return new dev.bluevista.craftq3.core.math.Vec3(x, y, z);
  }

  @Test
  void bucketDestinationsRemainOutsideAllSixSolidFaces() {
    var geometry =
        new BuildingGeometry(
            new BoxTraceWorld(q(0, 0, 0), q(32, 32, 32), 1, 0, 1022),
            new CoordinateTransform(32, q(0, 0, 0)));
    var center = new Vec3(.5, .5, -.5);
    for (var face : Direction.values()) {
      var start = center.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(2));
      var miss = BlockHitResult.miss(center, face, BlockPos.containing(center));
      var hit = BuildingFluids.nearer(geometry, start, center, miss);
      assertEquals(face, hit.getDirection());
      assertEquals(HitResult.Type.BLOCK, hit.getType());
      var destination = hit.getBlockPos().relative(face);
      var min = q(destination.getX() + .001, destination.getY() + .001, destination.getZ() + .001);
      assertTrue(geometry.solidClear(new BuildingGeometry.Box(min, min.add(q(.998, .998, .998)))));
    }
  }

  @Test
  void fractionalFloorUsesClearCellAndPreservesNearerNativeFluid() {
    var geometry =
        new BuildingGeometry(
            new BoxTraceWorld(q(-32, -32, -32), q(32, 32, 16), 1, 0, 1022),
            new CoordinateTransform(32, q(4096, 10, 0)));
    var start = new Vec3(4096.5, 13, -.5);
    var end = new Vec3(4096.5, 9, -.5);
    var miss = BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
    var hit = BuildingFluids.nearer(geometry, start, end, miss);
    assertEquals(new BlockPos(4096, 11, -1), hit.getBlockPos().above());
    var water =
        new BlockHitResult(
            new Vec3(4096.5, 11.8, -.5), Direction.UP, new BlockPos(4096, 11, -1), false);
    assertSame(water, BuildingFluids.nearer(geometry, start, end, water));
    var clip =
        new BuildingGeometry(
            new BoxTraceWorld(q(-32, -32, -32), q(32, 32, 16), 0x10000, 0, 1022),
            new CoordinateTransform(32, q(4096, 10, 0)));
    assertSame(miss, BuildingFluids.nearer(clip, start, end, miss));
  }
}

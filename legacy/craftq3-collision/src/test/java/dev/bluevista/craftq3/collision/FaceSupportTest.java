package dev.bluevista.craftq3.collision;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FaceSupportTest {
  private static final Vec3 UP = new Vec3(0, 0, 1);
  private static final Vec3 MIN = new Vec3(-16, -16, 0), MAX = new Vec3(16, 16, 0);

  private static CollisionFixtures.Box floor(double x1, double y1, double x2, double y2) {
    return new CollisionFixtures.Box(new Vec3(x1, y1, -16), new Vec3(x2, y2, 0), Contents.SOLID, 0);
  }

  @Test
  void everyCardinalBrushFaceSupportsOnlyItsOwnBoundaryAndOutwardDirection() throws Exception {
    var bsp =
        new BspTraceWorld(
            CollisionFixtures.boxes(
                List.of(
                    new CollisionFixtures.Box(
                        new Vec3(-16, -16, -16), new Vec3(16, 16, 16), Contents.SOLID, 0))));
    for (int axis = 0; axis < 3; axis++)
      for (int sign : new int[] {-1, 1}) {
        double[] lo = {-8, -8, -8}, hi = {8, 8, 8};
        lo[axis] = hi[axis] = sign * 16;
        var min = new Vec3(lo[0], lo[1], lo[2]);
        var max = new Vec3(hi[0], hi[1], hi[2]);
        var normal = CollisionMath.AXES[axis].scale(sign);
        assertTrue(bsp.supportsFace(min, max, normal, List.of()));
        assertFalse(bsp.supportsFace(min, max, normal.scale(-1), List.of()));
        var offset = normal.scale(.125);
        assertFalse(bsp.supportsFace(min.add(offset), max.add(offset), normal, List.of()));
      }
  }

  @Test
  void adjoiningBrushesCanJointlySupportAWholeFace() throws Exception {
    var bsp =
        new BspTraceWorld(
            CollisionFixtures.boxes(List.of(floor(-16, -16, 0, 16), floor(0, -16, 16, 16))));
    assertTrue(bsp.supportsFace(MIN, MAX, UP, List.of()));
  }

  @Test
  void nativePartialSupportAndBspCoverageCombine() throws Exception {
    var bsp = new BspTraceWorld(CollisionFixtures.boxes(List.of(floor(0, -16, 16, 16))));
    assertFalse(bsp.supportsFace(MIN, MAX, UP, List.of()));
    assertTrue(
        bsp.supportsFace(
            MIN, MAX, UP, List.of(new BspMap.Bounds(new Vec3(-16, -16, -1), new Vec3(0, 16, 1)))));
    assertFalse(
        bsp.supportsFace(
            MIN,
            MAX,
            UP,
            List.of(new BspMap.Bounds(new Vec3(-16, -16, -1), new Vec3(-.125, 16, 1)))));
  }

  @Test
  void offCenterHoleIsRejectedEvenWhenCornersAndCenterAreSolid() throws Exception {
    var bsp =
        new BspTraceWorld(
            CollisionFixtures.boxes(
                List.of(
                    floor(-16, -16, 4, 16),
                    floor(5, -16, 16, 16),
                    floor(4, -16, 5, 4),
                    floor(4, 5, 5, 16))));
    assertFalse(bsp.supportsFace(MIN, MAX, UP, List.of()));
    assertTrue(bsp.supportsFace(new Vec3(-2, -2, 0), new Vec3(2, 2, 0), UP, List.of()));
  }

  @Test
  void playerClipAndCurvedPatchesDoNotBecomeBuildSupports() throws Exception {
    var clip =
        new BspTraceWorld(
            CollisionFixtures.boxes(
                List.of(
                    new CollisionFixtures.Box(
                        new Vec3(-32, -32, -16), new Vec3(32, 32, 0), Contents.PLAYERCLIP, 0))));
    assertFalse(clip.supportsFace(MIN, MAX, UP, List.of()));
    assertFalse(
        new BspTraceWorld(CollisionFixtures.patch(0)).supportsFace(MIN, MAX, UP, List.of()));
  }

  @Test
  void obliqueHalfSpacesCoverWithoutMissingNarrowInteriorGaps() {
    var coverage = new FaceCoverage(MIN, MAX, UP);
    var diagonal = new Vec3(Math.sqrt(.5), Math.sqrt(.5), 0);
    coverage.subtract(List.of(new TraceResult.Plane(diagonal, 0)));
    assertFalse(coverage.covered());
    coverage.subtract(List.of(new TraceResult.Plane(diagonal.scale(-1), -.125)));
    assertFalse(coverage.covered());
    coverage.subtract(List.of(new TraceResult.Plane(diagonal.scale(-1), 0)));
    assertTrue(coverage.covered());
  }

  @Test
  void manyBrushSeamsRemainCovered() throws Exception {
    var boxes = new ArrayList<CollisionFixtures.Box>();
    for (int i = 0; i < 32; i++) boxes.add(floor(i - 16, -16, i - 15, 16));
    assertTrue(
        new BspTraceWorld(CollisionFixtures.boxes(boxes)).supportsFace(MIN, MAX, UP, List.of()));
  }

  @Test
  void nonPlanarOrNonCardinalSupportRequestsAreRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> new FaceCoverage(MIN, new Vec3(16, 16, 1), UP));
    assertThrows(
        IllegalArgumentException.class, () -> new FaceCoverage(MIN, MAX, new Vec3(0, .5, 1)));
    assertThrows(IllegalArgumentException.class, () -> new FaceCoverage(MIN, MIN, UP));
  }
}

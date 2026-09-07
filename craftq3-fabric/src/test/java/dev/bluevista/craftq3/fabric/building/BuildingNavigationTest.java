package dev.bluevista.craftq3.fabric.building;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.collision.CompositeTraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuildingNavigationTest {
  private static BuildingGeometry floor(double height) {
    return new BuildingGeometry(
        new BoxTraceWorld(new Vec3(-320, -320, -320), new Vec3(320, 320, height * 32), 1, 0, 1022),
        new CoordinateTransform(32, new Vec3(4096, 20, 0)));
  }

  @Test
  void standingHeightMapsToOneRoundedPathNode() {
    for (double height : new double[] {0, .125, .5, .875, 1}) {
      var geometry = floor(height);
      int node = (int) Math.floor(20 + height + .5);
      assertEquals(20 + height, geometry.navigationFloor(4096.5, node, .5).orElseThrow(), .005);
      assertTrue(geometry.navigationFloor(4096.5, node + 1, .5).isEmpty());
      assertTrue(geometry.navigationFloor(4096.5, node - 1, .5).isEmpty());
    }
  }

  @Test
  void missingFloorAndPointsOutsidePlatformStayUnsupported() {
    assertTrue(floor(0).navigationFloor(4120, 20, 0).isEmpty());
    assertTrue(floor(0).navigationFloor(4096, 24, 0).isEmpty());
  }

  @Test
  void nativeBodyHeadroomIncludesBspCeilingsAndWalls() {
    var geometry =
        new BuildingGeometry(
            new BoxTraceWorld(new Vec3(-320, -320, 48), new Vec3(320, 320, 64), 1, 0, 1022),
            new CoordinateTransform(32, new Vec3(0, 0, 0)));
    assertFalse(
        geometry.clear(new BuildingGeometry.Box(new Vec3(.2, 0, .2), new Vec3(.8, 1.8, .8))));
    assertTrue(
        geometry.clear(new BuildingGeometry.Box(new Vec3(.2, 0, .2), new Vec3(.8, 1.2, .8))));
    assertTrue(geometry.navigationFloor(.5, 0, .5).isEmpty());
  }

  private static BoxTraceWorld obstacle(Vec3 min, Vec3 max) {
    return new BoxTraceWorld(min.scale(32), max.scale(32), 1, 0, 1022);
  }

  @Test
  void smallBodyFitsPassageThatWholeCellAndLargerBodyDoNot() {
    var geometry =
        new BuildingGeometry(
            new CompositeTraceWorld(
                List.of(
                    obstacle(new Vec3(-2, -10, -1), new Vec3(.2, 10, 3)),
                    obstacle(new Vec3(.8, -10, -1), new Vec3(2, 10, 3)))),
            new CoordinateTransform(32, new Vec3(0, 0, 0)));
    var small = new BuildingGeometry.Box(new Vec3(.3, .01, .3), new Vec3(.7, .71, .7));
    assertTrue(geometry.pathClear(small, new Vec3(0, 0, 4)));
    assertFalse(
        geometry.clear(new BuildingGeometry.Box(new Vec3(.01, .01, .01), new Vec3(.99, .99, .99))));
    assertFalse(
        geometry.pathClear(
            new BuildingGeometry.Box(new Vec3(.05, .01, .05), new Vec3(.95, 1.4, .95)),
            new Vec3(0, 0, 4)));
  }

  @Test
  void clearEndpointsDoNotPermitCrossingThinWall() {
    var geometry =
        new BuildingGeometry(
            obstacle(new Vec3(.9, -2, -1), new Vec3(1, 2, 3)),
            new CoordinateTransform(32, new Vec3(0, 0, 0)));
    var box = new BuildingGeometry.Box(new Vec3(.4, .01, -.1), new Vec3(.6, .71, .1));
    assertTrue(geometry.clear(box));
    assertTrue(geometry.clear(box.moved(new Vec3(1, 0, 0))));
    assertFalse(geometry.pathClear(box, new Vec3(1, 0, 0)));
    assertFalse(geometry.pathClear(box.moved(new Vec3(1, 0, 0)), new Vec3(-1, 0, 0)));
  }

  @Test
  void actualHeightCanPassUnderCeilingThatRoundedCellCannot() {
    var geometry =
        new BuildingGeometry(
            obstacle(new Vec3(-2, -10, .85), new Vec3(2, 10, 2)),
            new CoordinateTransform(32, new Vec3(0, 0, 0)));
    assertTrue(
        geometry.pathClear(
            new BuildingGeometry.Box(new Vec3(.3, .01, .3), new Vec3(.7, .71, .7)),
            new Vec3(0, 0, 4)));
    assertFalse(
        geometry.pathClear(
            new BuildingGeometry.Box(new Vec3(.3, .01, .3), new Vec3(.7, 1, .7)),
            new Vec3(0, 0, 4)));
  }
}

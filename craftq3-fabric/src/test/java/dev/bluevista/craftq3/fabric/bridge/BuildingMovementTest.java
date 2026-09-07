package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.building.*;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.util.List;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

final class BuildingMovementTest {
  private static final CoordinateTransform TRANSFORM =
      new CoordinateTransform(32, new Vec3(0, 0, 0));

  private static BuildingGeometry geometry(double step, boolean ceiling) {
    var floor = new BoxTraceWorld(new Vec3(-100, -100, -100), new Vec3(100, 100, 0), 1, 0, 1022);
    var ledge = new BoxTraceWorld(new Vec3(32, -100, 0), new Vec3(100, 100, step * 32), 1, 0, 1022);
    var roof = new BoxTraceWorld(new Vec3(-100, -100, 64), new Vec3(100, 100, 100), 1, 0, 1022);
    return new BuildingGeometry(
        new CompositeTraceWorld(ceiling ? List.of(floor, ledge, roof) : List.of(floor, ledge)),
        TRANSFORM);
  }

  @Test
  void stepCandidateFitsNativeAllowanceAndClearsLedge() {
    var geometry = geometry(.5, false);
    var region = new AABB(0, .004, 0, 1.6, 2.404, .6);
    var shape = new BuildingMovementShape(geometry, region, .6);
    double height = shape.getCoords(Axis.Y).getDouble(1) - region.minY;
    assertEquals(.5, height, .005);
    var body = new AABB(0, .004, 0, .6, 1.804, .6);
    assertTrue(shape.collide(Axis.X, body, 1) < .401);
    double up = shape.collide(Axis.Y, body, height);
    assertEquals(height, up, 1e-6);
    assertEquals(1, shape.collide(Axis.X, body.move(0, up, 0), 1), 1e-6);
  }

  @Test
  void tooHighBspStepHasNoReachableSupportCandidate() {
    var geometry = geometry(.8, false);
    var region = new BuildingGeometry.Box(new Vec3(0, .004, 0), new Vec3(1.6, 2.404, .6));
    assertTrue(geometry.stepSurface(region, .6).isEmpty());
  }

  @Test
  void bspAndNativeHeadroomBothPreventClearingStep() {
    var body = new AABB(0, .004, 0, .6, 1.804, .6);
    var region = new AABB(0, .004, 0, 1.6, 2.404, .6);
    var nativeRoof = Shapes.box(-1, 2, -1, 3, 3, 2);
    for (boolean bspRoof : new boolean[] {true, false}) {
      var bsp = new BuildingMovementShape(geometry(.5, bspRoof), region, .6);
      List<net.minecraft.world.phys.shapes.VoxelShape> shapes =
          bspRoof ? List.of(bsp) : List.of(nativeRoof, bsp);
      double up = Shapes.collide(Axis.Y, body, shapes, .5);
      assertTrue(up < .2);
      assertTrue(Shapes.collide(Axis.X, body.move(0, up, 0), shapes, 1) < .401);
    }
  }

  @Test
  void candidateCoordinatesStaySortedForEntitiesShorterThanTheirStepAllowance() {
    var shape =
        new BuildingMovementShape(geometry(.5, false), new AABB(0, .004, 0, .2, .254, .2), .6);
    var heights = shape.getCoords(Axis.Y);
    for (int i = 1; i < heights.size(); i++)
      assertTrue(heights.getDouble(i) > heights.getDouble(i - 1));
    assertEquals(.604, heights.getDouble(heights.size() - 1), 1e-9);
  }

  @Test
  void nativeWallIsCheckedAtHeightClippedByBspFloor() {
    var geometry =
        new BuildingGeometry(
            new BoxTraceWorld(new Vec3(-1000, -1000, -100), new Vec3(1000, 1000, 0), 1, 0, 1022),
            TRANSFORM);
    var body = new AABB(0, .25, 0, .6, 2.05, .6);
    var bsp = new BuildingMovementShape(geometry, body.expandTowards(1, -2, 0), .6);
    var shapes = List.of(Shapes.box(1, 1, -1, 2, 3, 2), bsp);
    double down = Shapes.collide(Axis.Y, body, shapes, -2);
    assertEquals(-.25, down, .005);
    double forward = Shapes.collide(Axis.X, body.move(0, down, 0), shapes, 1);
    assertEquals(.4, forward, 1e-6);
    // The former whole-world sequence saw horizontal clearance below this wall.
    double wrongDown = Shapes.collide(Axis.Y, body, List.of(shapes.getFirst()), -2);
    assertEquals(
        1, Shapes.collide(Axis.X, body.move(0, wrongDown, 0), List.of(shapes.getFirst()), 1));
  }
}

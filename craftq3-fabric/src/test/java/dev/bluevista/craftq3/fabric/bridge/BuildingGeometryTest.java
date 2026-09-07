package dev.bluevista.craftq3.fabric.bridge;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.building.BuildingGeometry;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BuildingGeometryTest {
  private static final CoordinateTransform TRANSFORM =
      new CoordinateTransform(32, new Vec3(10, 20, 30));

  private static BoxTraceWorld floor(double top) {
    return new BoxTraceWorld(new Vec3(-1000, -1000, -1000), new Vec3(1000, 1000, top), 1, 0, 1022);
  }

  @Test
  void nativeSizeBodyLandsAndSlidesAlongBspWalls() {
    var wall = new BoxTraceWorld(new Vec3(32, -1000, 0), new Vec3(64, 1000, 1000), 1, 0, 1022);
    var geometry =
        new BuildingGeometry(new CompositeTraceWorld(List.of(floor(0), wall)), TRANSFORM);
    var body = new BuildingGeometry.Box(new Vec3(10, 20.25, 30), new Vec3(10.6, 22.05, 30.6));
    var move = geometry.clip(body, new Vec3(1, -1, 1));
    assertEquals(.4, move.x(), .005);
    assertEquals(-.25, move.y(), .005);
    assertEquals(1, move.z(), .005);
    assertTrue(geometry.clear(body.moved(move)));
  }

  @Test
  void gridAndFractionalFloorsChooseAnOutsideCellWithLegalNativeClickCoordinates() {
    for (double surface : new double[] {0, 16, -16}) {
      var geometry = new BuildingGeometry(floor(surface), TRANSFORM);
      var hit = geometry.pick(new Vec3(10.5, 24, 30.5), new Vec3(10.5, 18, 30.5)).orElseThrow();
      assertEquals(Math.ceil(20 + surface / 32), hit.cell().y());
      assertEquals(0, hit.normal().x(), 1e-9);
      assertEquals(1, hit.normal().y(), 1e-9);
      assertEquals(0, hit.normal().z(), 1e-9);
      var click = hit.placementPoint();
      assertTrue(click.y() >= hit.cell().y() && click.y() <= hit.cell().y() + 1);
      assertTrue(
          geometry.clear(
              new BuildingGeometry.Box(
                  hit.cell().add(new Vec3(.001, .001, .001)),
                  hit.cell().add(new Vec3(.999, .999, .999)))));
    }
  }

  @Test
  void solidBodiesAndMissedFacesAreRejected() {
    var geometry = new BuildingGeometry(floor(0), TRANSFORM);
    assertFalse(
        geometry.clear(new BuildingGeometry.Box(new Vec3(10, 19, 30), new Vec3(11, 21, 31))));
    assertTrue(geometry.pick(new Vec3(10, 24, 30), new Vec3(10, 25, 30)).isEmpty());
  }
}

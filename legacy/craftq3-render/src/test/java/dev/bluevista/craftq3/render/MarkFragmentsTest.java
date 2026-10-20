package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkFragmentsTest {
  private static final Vec3 DOWN = new Vec3(0, 0, -20);

  private static List<Vec3> polygon(double x, double z) {
    return List.of(
        new Vec3(x - 1, -1, z),
        new Vec3(x + 1, -1, z),
        new Vec3(x + 1, 1, z),
        new Vec3(x - 1, 1, z));
  }

  private static MarkFragments floor() {
    var points =
        List.of(
            new Vec3(-10, -10, 0),
            new Vec3(-10, 10, 0),
            new Vec3(10, -10, 0),
            new Vec3(10, -10, 0),
            new Vec3(-10, 10, 0),
            new Vec3(10, 10, 0));
    var uv = new BspMap.Uv(0, 0);
    var vertices =
        points.stream().map(p -> new BspMap.Vertex(p, uv, uv, new Vec3(0, 0, 1), -1)).toList();
    var bounds = new BspMap.Bounds(new Vec3(-10, -10, 0), new Vec3(10, 10, 0));
    return new MarkFragments(
        new RenderScene(
            "floor",
            List.of(),
            new RenderScene.Camera(new Vec3(0, 0, 10), 0, 0, 90),
            vertices,
            List.of(new RenderScene.Surface(0, 0, "floor", -1, 1, 0, 6, bounds)),
            null));
  }

  @Test
  void clipsProjectedMarkToWorldTrianglesAndRetainsSurfacePlane() {
    var fragments = floor().project(polygon(0, .125), DOWN, 64, 16);
    assertEquals(2, fragments.size());
    for (var points : fragments)
      for (var p : points) {
        assertEquals(0, p.z(), 1e-8);
        assertTrue(Math.abs(p.x()) <= 1.05);
        assertTrue(Math.abs(p.y()) <= 1.05);
      }
  }

  @Test
  void excludesBackfacesMissesAndPointsBeyondTheProjection() {
    var marks = floor();
    assertTrue(marks.project(polygon(0, 1), DOWN.scale(-1), 64, 16).isEmpty());
    assertTrue(marks.project(polygon(50, 1), DOWN, 64, 16).isEmpty());
    assertTrue(marks.project(polygon(0, 30), DOWN, 64, 16).isEmpty());
    assertTrue(marks.project(polygon(0, 1), new Vec3(0, 0, 0), 64, 16).isEmpty());
  }

  @Test
  void clipsAtEdgesAndRespectsCallerOutputCapacity() {
    var marks = floor();
    var edge = marks.project(polygon(10, 1), DOWN, 64, 16);
    assertFalse(edge.isEmpty());
    assertTrue(
        edge.stream().flatMap(List::stream).allMatch(p -> p.x() <= 10.00001 && p.x() >= 9 - .05));
    assertTrue(marks.project(polygon(0, 1), DOWN, 2, 16).isEmpty());
    assertEquals(1, marks.project(polygon(0, 1), DOWN, 64, 1).size());
    assertThrows(
        IllegalArgumentException.class,
        () -> marks.project(polygon(0, 1), DOWN, Integer.MAX_VALUE, 16));
  }
}

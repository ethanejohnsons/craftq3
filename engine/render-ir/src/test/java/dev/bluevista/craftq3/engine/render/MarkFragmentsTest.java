package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code MarkFragmentsTest}. */
class MarkFragmentsTest {
  private static final Vec3f DOWN = new Vec3f(0, 0, -20);

  private static float[] polygon(float x, float z) {
    return new float[] {x - 1, -1, z, x + 1, -1, z, x + 1, 1, z, x - 1, 1, z};
  }

  private static MarkFragments floor(int flags) {
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture("textures/floor", flags);
    float[][] points = {{-10, -10}, {-10, 10}, {10, -10}, {10, -10}, {-10, 10}, {10, 10}};
    for (float[] p : points) bsp.vertex(p[0], p[1], 0, 0, 0, 0, 0, 0, 0, 1, -1);
    bsp.planar(texture, -1, -1, 0, 6, new float[] {0, 0, 1}, 0, 1, 2, 3, 4, 5);
    bsp.model(new float[] {-10, -10, 0}, new float[] {10, 10, 0}, 0, 1, 0, 0);
    return new MarkFragments(TestBsp.scene(bsp.read()).world());
  }

  @Test
  void clipsProjectedMarkToWorldTrianglesAndRetainsSurfacePlane() {
    MarkFragments.Result fragments =
        floor(0).project(polygon(0, .125f), 4, DOWN, 64, 16, new MarkFragments.Result());
    assertEquals(2, fragments.fragmentCount());
    for (int p = 0; p < fragments.pointCount(); p++) {
      assertEquals(0, fragments.point(p, 2), 1e-6);
      assertTrue(Math.abs(fragments.point(p, 0)) <= 1.05);
      assertTrue(Math.abs(fragments.point(p, 1)) <= 1.05);
    }
  }

  @Test
  void excludesBackfacesMissesAndPointsBeyondTheProjection() {
    MarkFragments marks = floor(0);
    MarkFragments.Result out = new MarkFragments.Result();
    assertEquals(0, marks.project(polygon(0, 1), 4, DOWN.negate(), 64, 16, out).fragmentCount());
    assertEquals(0, marks.project(polygon(50, 1), 4, DOWN, 64, 16, out).fragmentCount());
    assertEquals(0, marks.project(polygon(0, 30), 4, DOWN, 64, 16, out).fragmentCount());
    assertEquals(0, marks.project(polygon(0, 1), 4, Vec3f.ZERO, 64, 16, out).fragmentCount());
    assertEquals(
        0,
        floor(WorldGeometry.SURF_NOMARKS)
            .project(polygon(0, 1), 4, DOWN, 64, 16, out)
            .fragmentCount());
  }

  @Test
  void clipsAtEdgesAndRespectsCallerOutputCapacity() {
    MarkFragments marks = floor(0);
    MarkFragments.Result edge =
        marks.project(polygon(10, 1), 4, DOWN, 64, 16, new MarkFragments.Result());
    assertFalse(edge.fragmentCount() == 0);
    for (int p = 0; p < edge.pointCount(); p++)
      assertTrue(edge.point(p, 0) <= 10.00001 && edge.point(p, 0) >= 9 - .05);
    MarkFragments.Result out = new MarkFragments.Result();
    assertEquals(0, marks.project(polygon(0, 1), 4, DOWN, 2, 16, out).fragmentCount());
    assertEquals(1, marks.project(polygon(0, 1), 4, DOWN, 64, 1, out).fragmentCount());
    assertThrows(
        IllegalArgumentException.class,
        () -> marks.project(polygon(0, 1), 4, DOWN, Integer.MAX_VALUE, 16, out));
  }
}

package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Error-bounded patch tessellation ({@code r_subdivisions}): spans, removal and stitching. */
class PatchTessellationTest {
  private static final float SPACING = 64;

  @Test
  void flatPatchesCollapseToOneQuad() {
    for (int size : new int[] {3, 5, 9}) {
      TestBsp bsp = new TestBsp();
      int texture = bsp.texture("textures/test", 0);
      patch(bsp, texture, size, size, (column, row) -> 0f);
      bsp.model(new float[] {-1024, -1024, -1024}, new float[] {1024, 1024, 1024}, 0, 1, 0, 0);
      WorldGeometry world = adaptive(bsp.read(), 4).world();
      assertEquals(2, world.triangleCount(), size + "x" + size);
      assertEquals(4, world.vertexCount(), size + "x" + size);
    }
  }

  /**
   * A cylinder-like bend across the rows: the middle control row is raised by 64 over a 128-unit
   * span. The curve's midpoint is 32 units off the chord; halves are about 7.2 units off their
   * chords and quarters under 2, so the span count follows the tolerance.
   */
  @Test
  void curvatureSetsTheSubdivisionCount() {
    BspMap map = bend(64);
    assertEquals(4, adaptive(map, 40).world().triangleCount(), "one span: 3 rows");
    assertEquals(8, adaptive(map, 8).world().triangleCount(), "two spans: 5 rows");
    assertEquals(16, adaptive(map, 4).world().triangleCount(), "four spans: 9 rows");
    assertEquals(16, adaptive(map, 4).world().triangleCount(), "the default tolerance");
    assertEquals(18, adaptive(map, 4).world().vertexCount(), "straight columns keep their ends");
    assertEquals(
        16,
        RenderScene.build("test", map, TestBsp.shaders(Map.of())).world().triangleCount(),
        "r_subdivisions defaults to 4");
  }

  /**
   * Each bent block wants four spans (three splits). Five blocks get them: 20 spans, 41 samples.
   * Fifteen blocks start with 15 spans; splits are made left to right, depth first, until the
   * direction has 32 spans (65 samples), leaving the blocks on the right unsplit.
   */
  @Test
  void subdivisionStopsAtTheGridLimit() {
    assertEquals(2 * 9, adaptive(wave(1), 4).world().vertexCount(), "one block: four spans");
    assertEquals(2 * 41, adaptive(wave(5), 4).world().vertexCount(), "below the limit");
    WorldGeometry capped = adaptive(wave(15), 4).world();
    assertEquals(2 * PatchTessellator.MAX_GRID_SIZE, capped.vertexCount());
    assertEquals(2 * (PatchTessellator.MAX_GRID_SIZE - 1), capped.triangleCount());
  }

  @Test
  void tessellatedPointsLieOnTheSurfaceAndKeepCorners() {
    WorldGeometry world = adaptive(bend(64), 4).world();
    Set<Vec3f> points = positions(world, 0);
    assertTrue(points.contains(new Vec3f(0, 0, 0)));
    assertTrue(points.contains(new Vec3f(128, 128, 0)));
    // The parabola z = 128 t (1 - t) at y = 128 t.
    for (Vec3f point : points) {
      double t = point.y() / 128.0;
      assertEquals(128 * t * (1 - t), point.z(), 1e-3, point.toString());
    }
  }

  /**
   * Two patches share an edge whose control points run in opposite directions. The first bends
   * sharply across its columns, so on its own it samples the shared edge more densely than the
   * second; stitched, both sample it identically, bit for bit.
   */
  @Test
  void sharedEdgesAreStitchedWithoutCracks() {
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture("textures/test", 0);
    // Patch A: rows y = 0, 64, 128; the last row is the shared edge.
    patch(bsp, texture, 3, 3, (column, row) -> column == 1 ? (row == 2 ? 24f : 400f) : 0f);
    // Patch B: rows y = 128, 192, 256, columns running from x = 128 down to 0.
    int bFirst = 9;
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 3; column++)
        bsp.vertex(
            (2 - column) * SPACING,
            128 + row * SPACING,
            column == 1 ? 24 : 0,
            0,
            0,
            0,
            0,
            0,
            0,
            1,
            -1);
    bsp.patch(texture, 0, bFirst, 3, 3);
    bsp.model(new float[] {-1024, -1024, -1024}, new float[] {1024, 1024, 1024}, 0, 2, 0, 0);
    BspMap both = bsp.read();
    WorldGeometry world = adaptive(both, 4).world();
    Set<Vec3f> a = onEdge(world, 0), b = onEdge(world, 1);
    assertEquals(a, b, "both patches carry the same vertices along the shared edge");

    TestBsp alone = new TestBsp();
    int t = alone.texture("textures/test", 0);
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 3; column++)
        alone.vertex(
            (2 - column) * SPACING,
            128 + row * SPACING,
            column == 1 ? 24 : 0,
            0,
            0,
            0,
            0,
            0,
            0,
            1,
            -1);
    alone.patch(t, 0, 0, 3, 3);
    alone.model(new float[] {-1024, -1024, -1024}, new float[] {1024, 1024, 1024}, 0, 1, 0, 0);
    int unstitched = onEdge(adaptive(alone.read(), 4).world(), 0).size();
    assertTrue(
        b.size() > unstitched,
        "stitching added samples: " + b.size() + " vs " + unstitched + " alone");
  }

  @Test
  void optionsValidateAndMapTheCvar() {
    assertEquals(RenderScene.Options.DEFAULT, RenderScene.Options.subdivisions(Float.NaN));
    assertEquals(0, RenderScene.Options.subdivisions(-3).subdivisionError());
    assertEquals(4, RenderScene.Options.DEFAULT.subdivisionError());
    assertEquals(0, RenderScene.Options.DEFAULT.uniformSubdivisions());
    assertThrows(IllegalArgumentException.class, () -> new RenderScene.Options(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new RenderScene.Options(Float.NaN, 0));
    assertThrows(IllegalArgumentException.class, () -> RenderScene.Options.uniform(0));
    assertThrows(IllegalArgumentException.class, () -> RenderScene.Options.uniform(17));
  }

  // ------------------------------------------------------------------ fixtures

  private interface Height {
    float z(int column, int row);
  }

  /** A {@code width}×{@code height} control grid at {@link #SPACING}, heights from {@code z}. */
  private static void patch(TestBsp bsp, int texture, int width, int height, Height z) {
    int first = -1;
    for (int row = 0; row < height; row++)
      for (int column = 0; column < width; column++) {
        int v =
            bsp.vertex(
                column * SPACING, row * SPACING, z.z(column, row), column, row, 0, 0, 0, 0, 1, -1);
        if (first < 0) first = v;
      }
    bsp.patch(texture, 0, first, width, height);
  }

  /** A 3×3 patch whose middle control row is raised by {@code rise}. */
  private static BspMap bend(float rise) {
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture("textures/test", 0);
    patch(bsp, texture, 3, 3, (column, row) -> row == 1 ? rise : 0f);
    bsp.model(new float[] {-8192, -8192, -8192}, new float[] {8192, 8192, 8192}, 0, 1, 0, 0);
    return bsp.read();
  }

  /** {@code blocks} blocks side by side, each bent like {@link #bend} but across its columns. */
  private static BspMap wave(int blocks) {
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture("textures/test", 0);
    patch(bsp, texture, 2 * blocks + 1, 3, (column, row) -> (column & 1) == 1 ? 64f : 0f);
    bsp.model(new float[] {-8192, -8192, -8192}, new float[] {8192, 8192, 8192}, 0, 1, 0, 0);
    return bsp.read();
  }

  private static RenderScene adaptive(BspMap map, float error) {
    return RenderScene.build(
        "test", map, TestBsp.shaders(Map.of()), RenderScene.Options.subdivisions(error));
  }

  private static Set<Vec3f> positions(WorldGeometry world, int surface) {
    Set<Vec3f> out = new HashSet<>();
    int first = world.surfaceFirstVertex(surface);
    for (int v = first; v < first + world.surfaceVertexCount(surface); v++)
      out.add(WorldGeometryTest.position(world, v));
    return out;
  }

  /** The surface's vertices on the plane y = 128. */
  private static Set<Vec3f> onEdge(WorldGeometry world, int surface) {
    Set<Vec3f> out = new HashSet<>();
    for (Vec3f point : positions(world, surface)) if (point.y() == 128) out.add(point);
    return out;
  }
}

package dev.bluevista.craftq3.engine.collision;

import static dev.bluevista.craftq3.engine.collision.TestMap.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Patch facets: grid refinement, facets, borders, bevels, and sweeps against them. */
class PatchTraceTest {
  private static final float[] PLAYER_MINS = {-15, -15, -24};
  private static final float[] PLAYER_MAXS = {15, 15, 32};

  /** One patch in the world's only leaf. */
  private static CollisionModel patchMap(
      int flags, int contents, int width, int height, float[][] points) {
    TestMap map = new TestMap();
    int texture = map.texture("textures/curve", flags, contents);
    int face = map.patch(texture, width, height, points);
    map.leaf(0, 0, new int[] {face}, new int[0]);
    map.model(TestMap.WORLD_MIN, TestMap.WORLD_MAX, 0, 1, 0, 0);
    return CollisionModel.build(map.build());
  }

  /**
   * The legacy fixture's 3x3 dome: corners and edges at z = 0, the centre control point at 100. The
   * legacy layer oriented facets by the vertex normals; the original orients them by the winding of
   * the (refined, transposed) control grid, which for this grid faces up.
   */
  private static float[][] dome() {
    float[][] points = new float[9][];
    for (int i = 0; i < 9; i++)
      points[i] = v((i % 3 - 1) * 128, (i / 3 - 1) * 128, i == 4 ? 100 : 0);
    return points;
  }

  /** Biquadratic surface height of {@link #dome()} at grid parameters u, v. */
  private static float domeHeight(float u, float w) {
    return 100 * (2 * u * (1 - u)) * (2 * w * (1 - w));
  }

  /**
   * A quarter cylinder wall of radius 128 around the z axis from (0, 128) to (128, 0), 128 high,
   * with its front facing away from the axis.
   */
  private static float[][] wall() {
    float[][] arc = {{0, 128}, {128, 128}, {128, 0}};
    float[][] points = new float[9][];
    // Rows follow the arc and columns climb, which winds the front away from the axis.
    for (int row = 0; row < 3; row++)
      for (int column = 0; column < 3; column++)
        points[row * 3 + column] = v(arc[row][0], arc[row][1], column * 64);
    return points;
  }

  @Test
  void curvedPatchBlocksFrontTracesAndHasNoPointContents() {
    var model = patchMap(0, Contents.SOLID, 3, 3, dome());
    Trace down =
        BrushTraceTest.trace(model, v(0, 0, 100), v(0, 0, -100), null, null, Contents.SOLID);
    assertTrue(down.blocked());
    // The refined grid always contains the patch centre, where the surface peaks at 25.
    assertEquals(25 + Clip.SURFACE_CLIP_EPSILON, down.endPos(2), 0.01f);
    assertTrue(down.planeNormal(2) > .9f);
    assertEquals(Contents.SOLID, down.contents());
    // Facets are one-sided: a trace from behind passes through.
    assertFalse(BrushTraceTest.trace(model, v(0, 0, -100), v(0, 0, 100), null, null, -1).blocked());
    assertEquals(0, model.pointContents(v(0, 0, 20), 0));
    Trace body =
        BrushTraceTest.trace(model, v(0, 0, 100), v(0, 0, -100), PLAYER_MINS, PLAYER_MAXS, -1);
    assertTrue(body.blocked());
    assertTrue(body.endPos(2) >= 49, "the box bottom rests on the peak: " + body);
  }

  @Test
  void nonSolidPatchesFollowContentsNotTheSurfaceFlag() {
    // The legacy layer skipped patches flagged SURF_NONSOLID. The original decides by contents
    // alone (the map compiler clears CONTENTS_SOLID for non-solid shaders), so the flag by itself
    // does not stop collision.
    var flagged = patchMap(SurfaceFlags.NONSOLID, Contents.SOLID, 3, 3, dome());
    assertTrue(
        BrushTraceTest.trace(flagged, v(0, 0, 100), v(0, 0, -100), null, null, -1).blocked());
    var empty = patchMap(SurfaceFlags.NONSOLID, 0, 3, 3, dome());
    assertFalse(BrushTraceTest.trace(empty, v(0, 0, 100), v(0, 0, -100), null, null, -1).blocked());
    assertEquals(0, empty.statistics().patches());
    var water = patchMap(0, Contents.WATER, 3, 3, dome());
    assertFalse(
        BrushTraceTest.trace(water, v(0, 0, 100), v(0, 0, -100), null, null, Contents.SOLID)
            .blocked());
  }

  @Test
  void refinementTracksTheCurveWithinTheTolerance() {
    var model = patchMap(0, Contents.SOLID, 3, 3, dome());
    // Within the 16-unit tolerance one split per direction suffices: a 3x3 grid of on-curve
    // points (the centre at the peak height 25) whose 4 cells each make two triangle facets.
    PatchTable patches = model.patches;
    assertEquals(1, patches.count);
    assertEquals(8, patches.totalFacets());
    for (int i = 0; i <= 20; i++)
      for (int j = 0; j <= 20; j++) {
        // Off the grid lines: a point ray exactly along a seam between two non-mirrored facets can
        // slip between them by float rounding, in the original as here.
        float u = 0.05f + 0.9f * (i + 0.37f) / 21;
        float w = 0.05f + 0.9f * (j + 0.61f) / 21;
        float x = (u * 2 - 1) * 128;
        float y = (w * 2 - 1) * 128;
        Trace down = BrushTraceTest.trace(model, v(x, y, 200), v(x, y, -50), null, null, -1);
        assertTrue(down.blocked(), "hit at " + x + "," + y);
        float error = Math.abs(down.endPos(2) - Clip.SURFACE_CLIP_EPSILON - domeHeight(u, w));
        assertTrue(
            error < PatchBuilder.SUBDIVIDE_DISTANCE, "error " + error + " at " + x + "," + y);
      }
  }

  @Test
  void flatPatchesCollapseToOneQuadFacetWithBevels() {
    float[][] flat = dome();
    flat[4] = v(0, 0, 0);
    var model = patchMap(0, Contents.SOLID, 3, 3, flat);
    PatchTable p = model.patches;
    assertEquals(1, p.totalFacets(), "a planar 3x3 patch is one quad");
    int borders = p.facetBorderCount[0];
    // Four edge borders, the six axial bevels minus the two coinciding with the surface plane
    // (+z) and its reverse... and finally the reversed surface plane.
    int last = p.facetFirstBorder[0] + borders - 1;
    assertEquals(p.facetSurface[0], p.borderPlane[last]);
    assertTrue(p.borderInward[last]);
    assertTrue(borders >= 5, "borders: " + borders);
    Trace down = BrushTraceTest.trace(model, v(10, 10, 10), v(10, 10, -10), null, null, -1);
    assertEquals(0.5f - Clip.SURFACE_CLIP_EPSILON / 20, down.fraction(), 1e-6f);
    // Outside the patch's square the trace misses.
    assertFalse(
        BrushTraceTest.trace(model, v(130, 0, 10), v(130, 0, -10), null, null, -1).blocked());
    // A box reaching over the edge lands on it until its side clears x = 128 (the +x bevel).
    Trace edge =
        BrushTraceTest.trace(model, v(135, 0, 10), v(135, 0, -10), v(-8, -8, -8), v(8, 8, 8), -1);
    assertTrue(edge.blocked());
    assertFalse(
        BrushTraceTest.trace(model, v(137, 0, 10), v(137, 0, -10), v(-8, -8, -8), v(8, 8, 8), -1)
            .blocked());
  }

  @Test
  void degenerateColumnsAreRemoved() {
    float[][] collapsed = dome();
    // Every point of the grid collapses onto three columns that coincide: no surface at all.
    for (int i = 0; i < 9; i++) collapsed[i] = v(0, (i / 3 - 1) * 128, 0);
    var model = patchMap(0, Contents.SOLID, 3, 3, collapsed);
    assertEquals(0, model.statistics().patches());
    // A triangle-shaped patch: one edge collapses to a point but the rest is a valid surface.
    float[][] triangle = dome();
    triangle[4] = v(0, 0, 0);
    for (int i = 6; i < 9; i++) triangle[i] = v(0, 128, 0);
    var tri = patchMap(0, Contents.SOLID, 3, 3, triangle);
    assertEquals(1, tri.statistics().patches());
    assertTrue(BrushTraceTest.trace(tri, v(0, -64, 10), v(0, -64, -10), null, null, -1).blocked());
    assertFalse(
        BrushTraceTest.trace(tri, v(100, 100, 10), v(100, 100, -10), null, null, -1).blocked());
  }

  @Test
  void curvedWallStopsPointsBoxesAndCapsulesOnTheFront() {
    var model = patchMap(0, Contents.SOLID, 3, 3, wall());
    float s = (float) Math.sqrt(0.5);
    // Refined to the tolerance the arc becomes the chords (0, 128)-(96, 96)-(128, 0); the ray aims
    // at their shared vertex, the Bezier midpoint, and stops on one chord's facet.
    Trace ray = BrushTraceTest.trace(model, v(300, 300, 64), v(0, 0, 64), null, null, -1);
    assertTrue(ray.blocked());
    float distance = (float) Math.hypot(ray.endPos(0), ray.endPos(1));
    assertEquals(96 / s, distance, 0.5f);
    float chord = (float) (1 / Math.sqrt(10));
    assertEquals(chord, Math.min(ray.planeNormal(0), ray.planeNormal(1)), 1e-4f);
    assertEquals(3 * chord, Math.max(ray.planeNormal(0), ray.planeNormal(1)), 1e-4f);
    assertFalse(
        BrushTraceTest.trace(model, v(0, 0, 64), v(300, 300, 64), null, null, -1).blocked());

    Trace box =
        BrushTraceTest.trace(model, v(300, 300, 64), v(0, 0, 64), PLAYER_MINS, PLAYER_MAXS, -1);
    assertTrue(box.blocked());
    assertTrue(Math.hypot(box.endPos(0), box.endPos(1)) > 96 / s + 15, "box stops before the wall");
    Trace capsule = new Trace();
    model.boxTrace(
        model.work(), capsule, v(300, 300, 64), v(0, 0, 64), PLAYER_MINS, PLAYER_MAXS, 0, -1, true);
    assertTrue(capsule.blocked());
    // The capsule's round side reaches closer to the wall than the box's corner.
    assertTrue(
        Math.hypot(capsule.endPos(0), capsule.endPos(1))
            < Math.hypot(box.endPos(0), box.endPos(1)));
    assertTrue(Math.hypot(capsule.endPos(0), capsule.endPos(1)) > 96 / s + 14);

    // A box straddling the wall is inside its facets.
    Trace inside =
        BrushTraceTest.trace(model, v(96, 96, 64), v(96, 96, 64), v(-4, -4, -4), v(4, 4, 4), -1);
    assertTrue(inside.allSolid());
    assertEquals(Contents.SOLID, inside.contents());
    Trace clear =
        BrushTraceTest.trace(
            model, v(130, 130, 64), v(130, 130, 64), v(-4, -4, -4), v(4, 4, 4), -1);
    assertFalse(clear.startSolid());
    // Facets have no thickness: a stationary point is inside one only when exactly on its surface.
    for (float d : new float[] {95, 97})
      assertFalse(
          BrushTraceTest.trace(model, v(d, d, 64), v(d, d, 64), null, null, -1).startSolid());
  }

  @Test
  void invalidControlGridsAreSkipped() {
    float[][] even = new float[8][];
    for (int i = 0; i < 8; i++) even[i] = v(i % 4 * 32, i / 4 * 32, 0);
    // The reader already rejects even sizes; the builder refuses them on its own as well.
    var builder = new PatchBuilder();
    float[] flat = new float[8 * 3];
    for (int i = 0; i < 8; i++) System.arraycopy(even[i], 0, flat, i * 3, 3);
    boolean refused = false;
    try {
      builder.build(4, 2, flat, 0, Contents.SOLID, 0, new PatchTable.Builder());
    } catch (IllegalArgumentException e) {
      refused = true;
    }
    assertTrue(refused);
  }
}

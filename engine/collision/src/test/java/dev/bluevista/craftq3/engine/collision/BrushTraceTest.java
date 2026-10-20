package dev.bluevista.craftq3.engine.collision;

import static dev.bluevista.craftq3.engine.collision.TestMap.v;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Brush sweeps, position tests and point contents (ported from the legacy trace-world tests). */
class BrushTraceTest {
  private static final float[] PLAYER_MINS = {-15, -15, -24};
  private static final float[] PLAYER_MAXS = {15, 15, 32};

  static Trace trace(
      CollisionModel model, float[] start, float[] end, float[] mins, float[] maxs, int mask) {
    Trace out = new Trace();
    model.boxTrace(model.work(), out, start, end, mins, maxs, 0, mask, false);
    return out;
  }

  private static CollisionModel boxes(TestMap.BoxSpec... specs) {
    return CollisionModel.build(TestMap.boxes(List.of(specs)));
  }

  @Test
  void sweepsAsymmetricPlayerBoxAndReturnsTheUnexpandedContactPlane() {
    var model =
        boxes(new TestMap.BoxSpec(v(-100, -100, -10), v(100, 100, 0), Contents.SOLID, 4096));
    Trace hit =
        trace(
            model,
            v(0, 0, 100),
            v(0, 0, -100),
            PLAYER_MINS,
            PLAYER_MAXS,
            Contents.MASK_PLAYERSOLID);
    // The box bottom stops SURFACE_CLIP_EPSILON above the floor.
    assertEquals(24.125f, hit.endPos(2), 1e-4f);
    assertEquals((100 - 24.125f) / 200, hit.fraction(), 1e-6f);
    assertFalse(hit.startSolid());
    assertFalse(hit.allSolid());
    assertArrayEquals(new float[] {0, 0, 1}, hit.planeNormal().toArray());
    assertEquals(0, hit.planeDist());
    assertEquals(2, hit.planeType());
    assertEquals(4096, hit.surfaceFlags());
    assertEquals(Contents.SOLID, hit.contents());
    assertEquals(0, hit.entityNum(), "the collision model leaves the entity to the server");
    assertEquals(Trace.ENTITYNUM_WORLD, hit.markWorld().entityNum());
  }

  @Test
  void distinguishesEscapingSolidFromEntirelySolidAndHonoursMasks() {
    var model = boxes(new TestMap.BoxSpec(v(-1, -1, -1), v(1, 1, 1), Contents.PLAYERCLIP, 7));
    Trace inside = trace(model, v(0, 0, 0), v(.5f, 0, 0), null, null, Contents.MASK_PLAYERSOLID);
    assertTrue(inside.startSolid());
    assertTrue(inside.allSolid());
    assertEquals(0, inside.fraction());
    assertEquals(Contents.PLAYERCLIP, inside.contents());
    assertArrayEquals(new float[] {0, 0, 0}, inside.planeNormal().toArray());
    Trace escape = trace(model, v(0, 0, 0), v(2, 0, 0), null, null, Contents.MASK_PLAYERSOLID);
    assertTrue(escape.startSolid());
    assertFalse(escape.allSolid());
    assertEquals(1, escape.fraction());
    assertFalse(escape.blocked());
    assertFalse(trace(model, v(-2, 0, 0), v(2, 0, 0), null, null, Contents.SOLID).blocked());
    assertEquals(Contents.PLAYERCLIP, model.pointContents(v(0, 0, 0), 0));
    // A point on a face counts as inside, as in the original.
    assertEquals(Contents.PLAYERCLIP, model.pointContents(v(1, 0, 0), 0));
    assertEquals(0, model.pointContents(v(1.01f, 0, 0), 0));
  }

  @Test
  void parallelDegenerateAndHugeQueriesStayFinite() {
    var model = boxes(new TestMap.BoxSpec(v(-1, -1, -1), v(1, 1, 1), Contents.SOLID, 0));
    assertEquals(1, trace(model, v(-2, 1.01f, 0), v(2, 1.01f, 0), null, null, -1).fraction());
    Trace position = trace(model, v(2, 2, 2), v(2, 2, 2), null, null, -1);
    assertEquals(1, position.fraction());
    assertFalse(position.startSolid());
    // The legacy layer rejected coordinates beyond 1e12 and inverted boxes; the original accepts
    // any input, so both now produce finite results instead of exceptions.
    Trace huge = trace(model, v(1e13f, 0, 0), v(-1e13f, 0, 0), null, null, -1);
    // At this scale the 0.125 clip epsilon is below float resolution, so the entry and exit times
    // coincide and the thin box is missed, exactly as float math in the original would.
    assertTrue(Float.isFinite(huge.fraction()));
    assertFalse(huge.startSolid());
    assertTrue(trace(model, v(1e6f, 0, 0), v(-1e6f, 0, 0), null, null, -1).blocked());
    Trace inverted = trace(model, v(0, 0, 10), v(0, 0, -10), v(1, 0, 0), v(-1, 0, 0), -1);
    assertTrue(Float.isFinite(inverted.fraction()));
    Trace nan = trace(model, v(Float.NaN, 0, 0), v(0, 0, 0), null, null, -1);
    assertTrue(Float.isFinite(nan.fraction()));
  }

  @Test
  void bspTraversalAndDedupAgreeWithFlatBoxComposition() {
    var specs =
        new TestMap.BoxSpec[] {
          new TestMap.BoxSpec(v(-30, -5, -5), v(-20, 5, 5), Contents.SOLID, 2),
          new TestMap.BoxSpec(v(-2, -4, -4), v(2, 4, 4), Contents.SOLID, 4),
          new TestMap.BoxSpec(v(20, -6, -6), v(30, 6, 6), Contents.PLAYERCLIP, 8),
          // Touching the split plane from behind only: listed in the back leaf alone.
          new TestMap.BoxSpec(v(-10, 10, -6), v(0, 16, 6), Contents.SOLID, 16)
        };
    var bsp = boxes(specs);
    var empty = CollisionModel.build(emptyMap());
    var flat = BoxSource.builder();
    for (var spec : specs) flat.add(spec.min(), spec.max(), spec.contents(), spec.flags());
    BoxSource source = flat.build();
    var random = new Random(46);
    Trace expected = new Trace();
    Trace actual = new Trace();
    float[] mins = {-2, -1, -3};
    float[] maxs = {1, 2, 4};
    for (int i = 0; i < 4000; i++) {
      float[] start = v(random.nextInt(101) - 50, random.nextInt(41) - 10, random.nextInt(21) - 10);
      float[] end = v(random.nextInt(101) - 50, random.nextInt(41) - 10, random.nextInt(21) - 10);
      if (i % 4 == 0) {
        // Skim the split plane x = 0 within a unit, where the cull margin matters.
        start[0] = -mins[0] + random.nextFloat();
        end[0] = -mins[0] + random.nextFloat() - 0.2f;
      }
      boolean point = i % 3 == 0;
      empty.boxTrace(
          empty.work(),
          expected,
          start,
          end,
          point ? null : mins,
          point ? null : maxs,
          0,
          Contents.MASK_PLAYERSOLID,
          false,
          source);
      bsp.boxTrace(
          bsp.work(),
          actual,
          start,
          end,
          point ? null : mins,
          point ? null : maxs,
          0,
          Contents.MASK_PLAYERSOLID,
          false);
      String label = "trace " + i;
      assertEquals(expected.fraction(), actual.fraction(), label);
      assertEquals(expected.startSolid(), actual.startSolid(), label);
      assertEquals(expected.allSolid(), actual.allSolid(), label);
      assertEquals(expected.contents(), actual.contents(), label);
      assertEquals(expected.surfaceFlags(), actual.surfaceFlags(), label);
    }
    assertEquals(Contents.PLAYERCLIP, bsp.pointContents(v(25, 0, 0), 0));
    Trace hit = trace(bsp, v(-40, 0, 0), v(40, 0, 0), null, null, Contents.SOLID);
    // The legacy hit named brush 0 and its shader; trace_t carries its surface flags instead.
    assertEquals(2, hit.surfaceFlags());
    assertEquals(-30.125f, hit.endPos(0), 1e-4f);
  }

  @Test
  void stationaryBoxesAreAllSolidOnlyWhenOverlapping() {
    var model = boxes(new TestMap.BoxSpec(v(-8, -8, -8), v(8, 8, 8), Contents.SOLID, 0));
    float[] mins = {-4, -4, -4};
    float[] maxs = {4, 4, 4};
    assertTrue(trace(model, v(11.9f, 0, 0), v(11.9f, 0, 0), mins, maxs, -1).allSolid());
    // Touching counts as overlapping in a position test.
    assertTrue(trace(model, v(12, 0, 0), v(12, 0, 0), mins, maxs, -1).allSolid());
    assertFalse(trace(model, v(12.01f, 0, 0), v(12.01f, 0, 0), mins, maxs, -1).startSolid());
    assertFalse(trace(model, v(0, 0, 0), v(0, 0, 0), mins, maxs, Contents.WATER).startSolid());
  }

  @Test
  void slopedBrushUsesSupportOfTheBox() {
    float s = (float) Math.sqrt(0.5);
    // A 45-degree ramp rising towards +x: below z = x, within a large box.
    TestMap map = new TestMap();
    int texture = map.texture("textures/ramp", 0, Contents.SOLID);
    map.brush(
        texture,
        new float[] {-1, 0, 0, 512},
        new float[] {1, 0, 0, 512},
        new float[] {0, -1, 0, 512},
        new float[] {0, 1, 0, 512},
        new float[] {0, 0, -1, 512},
        new float[] {0, 0, 1, 512},
        new float[] {-s, 0, s, 0});
    map.leaf(0, 0, new int[0], new int[] {0});
    map.model(TestMap.WORLD_MIN, TestMap.WORLD_MAX, 0, 0, 0, 1);
    var model = CollisionModel.build(map.build());
    Trace hit = trace(model, v(0, 0, 200), v(0, 0, -200), PLAYER_MINS, PLAYER_MAXS, -1);
    // The box centre (0, 0, z + 4) stops when its support along the ramp normal plus the clip
    // epsilon separates it from the plane.
    float support = 15 * s + 28 * s;
    float centre = (support + Clip.SURFACE_CLIP_EPSILON) / s;
    assertEquals(centre - 4, hit.endPos(2), 0.01f);
    assertEquals(-s, hit.planeNormal(0), 1e-6f);
    assertEquals(Trace.PLANE_NON_AXIAL, hit.planeType());
    assertEquals(1, hit.planeSignbits());
  }

  static BspMap emptyMap() {
    TestMap map = new TestMap();
    map.texture("textures/none", 0, 0);
    map.leaf(0, 0, new int[0], new int[0]);
    map.model(TestMap.WORLD_MIN, TestMap.WORLD_MAX, 0, 0, 0, 0);
    return map.build();
  }
}

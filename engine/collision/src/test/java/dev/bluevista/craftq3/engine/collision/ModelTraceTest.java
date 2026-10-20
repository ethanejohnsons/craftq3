package dev.bluevista.craftq3.engine.collision;

import static dev.bluevista.craftq3.engine.collision.TestMap.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Inline models, transformed traces and temporary boxes. */
class ModelTraceTest {
  private static final float[] ZERO = {0, 0, 0};

  /** World with no solids; brush 0 (a box) belongs to inline model 1 only. */
  private static CollisionModel inlineBox(float[] min, float[] max) {
    TestMap map = new TestMap();
    int texture = map.texture("textures/mover", 4096, Contents.SOLID);
    map.box(min, max, texture);
    map.leaf(0, 0, new int[0], new int[0]);
    map.model(TestMap.WORLD_MIN, TestMap.WORLD_MAX, 0, 0, 0, 0);
    map.model(min, max, 0, 0, 0, 1);
    return CollisionModel.build(map.build());
  }

  private static Trace transformed(
      CollisionModel model,
      int handle,
      float[] start,
      float[] end,
      float[] mins,
      float[] maxs,
      float[] origin,
      float[] angles,
      boolean capsule) {
    Trace out = new Trace();
    model.transformedBoxTrace(
        model.work(), out, start, end, mins, maxs, handle, -1, origin, angles, capsule);
    return out;
  }

  @Test
  void inlineModelTranslationRotationAndWorldStaySeparate() {
    var model = inlineBox(v(-2, -1, -1), v(2, 1, 1));
    assertEquals(2, model.inlineModelCount());
    assertEquals(1, model.inlineModel(1));
    assertThrows(IllegalArgumentException.class, () -> model.inlineModel(2));
    assertEquals(
        1, BrushTraceTest.trace(model, v(-5, 0, 0), v(5, 0, 0), null, null, -1).fraction());
    int mover = model.inlineModel(1);
    float[] origin = {10, 20, 30};
    float[] yaw90 = {0, 90, 0};
    Trace hit =
        transformed(model, mover, v(5, 20, 30), v(15, 20, 30), null, null, origin, yaw90, false);
    // Yawed 90 degrees the box's 1-unit y half-width lies along world x.
    assertEquals(8.875f, hit.endPos(0), 1e-4f);
    assertEquals(-1, hit.planeNormal(0), 1e-6f);
    // The legacy layer reported plane distances in world space; so does this one (the original
    // left them in model space).
    assertEquals(-9, hit.planeDist(), 1e-4f);
    assertEquals(4096, hit.surfaceFlags());
    var work = model.work();
    assertEquals(
        Contents.SOLID,
        model.transformedPointContents(work, v(10, 21.5f, 30), mover, origin, yaw90));
    assertEquals(0, model.transformedPointContents(work, v(11.5f, 20, 30), mover, origin, yaw90));
    // Inline models are cached by id: moving one needs no new geometry and gives the same result.
    Trace moved =
        transformed(
            model, mover, v(105, 20, 30), v(115, 20, 30), null, null, v(110, 20, 30), yaw90, false);
    assertEquals(hit.fraction(), moved.fraction(), 1e-6f);
    assertEquals(-109, moved.planeDist(), 1e-4f);
    float[] mins = new float[3];
    float[] maxs = new float[3];
    model.modelBounds(mover, mins, maxs);
    assertEquals(-3, mins[0]);
    assertEquals(2, maxs[1]);
  }

  @Test
  void pitchAndRollKeepQuakeAxesAndInsideSemantics() {
    var map =
        TestMap.boxes(List.of(new TestMap.BoxSpec(v(-4, -2, -1), v(4, 2, 1), Contents.SOLID, 0)));
    var model = CollisionModel.build(map);
    var work = model.work();
    float[] origin = {10, 20, 30};
    float[] pitched = {90, 0, 0};
    assertEquals(
        Contents.SOLID, model.transformedPointContents(work, v(10, 20, 26.1f), 0, origin, pitched));
    assertEquals(0, model.transformedPointContents(work, v(11.1f, 20, 30), 0, origin, pitched));
    float[] rolled = {0, 0, 90};
    assertEquals(
        Contents.SOLID, model.transformedPointContents(work, v(10, 20, 31.9f), 0, origin, rolled));
    assertEquals(0, model.transformedPointContents(work, v(10, 21.1f, 30), 0, origin, rolled));
    Trace inside =
        transformed(model, 0, v(10, 20, 30), v(10, 20, 31), null, null, origin, rolled, false);
    assertTrue(inside.allSolid());
    assertEquals(0, inside.planeNormal(0));
    assertEquals(0, inside.planeNormal(2));
    assertEquals(30, inside.endPos(2));
  }

  @Test
  void rotatedModelsRotateTheSegmentButNotTheBox() {
    var model = inlineBox(v(-1, -1, -1), v(1, 1, 1));
    float s = (float) Math.sqrt(0.5);
    Trace hit =
        transformed(
            model,
            1,
            v(3, 0, 0),
            v(0, 0, 0),
            v(-.5f, -.5f, -.5f),
            v(.5f, .5f, .5f),
            ZERO,
            v(0, 45, 0),
            false);
    // The legacy layer swept a world-aligned box against bevelled rotated brushes and stopped at
    // x = sqrt(2) + 0.625. The original instead rotates only the segment into the model's frame and
    // sweeps the box there unrotated, an approximation it accepts: the model-frame box (+-1.5 once
    // expanded) is entered through its +x face along the diagonal.
    float localStart = 3 * s;
    float enter = (localStart - 1.5f - Clip.SURFACE_CLIP_EPSILON) / localStart;
    assertEquals(3 * (1 - enter), hit.endPos(0), 1e-4f);
    assertEquals(s, hit.planeNormal(0), 1e-5f);
    assertEquals(s, hit.planeNormal(1), 1e-5f);
  }

  @Test
  void unrotatedTransformedTracesEqualTracesAgainstMovedGeometry() {
    var placed = inlineBox(v(-2, -3, -4), v(2, 3, 4));
    var world =
        CollisionModel.build(
            TestMap.boxes(
                List.of(new TestMap.BoxSpec(v(8, -3, 16), v(12, 3, 24), Contents.SOLID, 4096))));
    float[] origin = {10, 0, 20};
    var random = new Random(7);
    Trace expected = new Trace();
    for (int i = 0; i < 2000; i++) {
      float[] start =
          v(random.nextFloat() * 40 - 10, random.nextFloat() * 20 - 10, random.nextFloat() * 40);
      float[] end =
          v(random.nextFloat() * 40 - 10, random.nextFloat() * 20 - 10, random.nextFloat() * 40);
      float[] mins = i % 2 == 0 ? null : v(-3, -1, -2);
      float[] maxs = i % 2 == 0 ? null : v(1, 3, 6);
      Trace actual = transformed(placed, 1, start, end, mins, maxs, origin, ZERO, false);
      world.boxTrace(world.work(), expected, start, end, mins, maxs, 0, -1, false);
      assertEquals(expected.fraction(), actual.fraction(), 2e-6f, "trace " + i);
      assertEquals(expected.startSolid(), actual.startSolid(), "trace " + i);
      assertEquals(expected.allSolid(), actual.allSolid(), "trace " + i);
      if (expected.fraction() < 1 && !expected.allSolid()) {
        assertEquals(expected.planeDist(), actual.planeDist(), 1e-3f, "trace " + i);
        assertEquals(expected.planeNormal(), actual.planeNormal(), "trace " + i);
      }
    }
  }

  @Test
  void temporaryBoxesHaveBodyContentsAndIgnoreRotation() {
    var model = CollisionModel.build(BrushTraceTest.emptyMap());
    var work = model.work();
    int box = model.tempBoxModel(work, v(-16, -16, -24), v(16, 16, 32), false);
    assertEquals(CollisionModel.BOX_MODEL_HANDLE, box);
    float[] origin = {100, 0, 0};
    Trace hit =
        transformed(model, box, v(0, 0, 0), v(200, 0, 0), null, null, origin, v(0, 45, 0), false);
    assertEquals(84 - Clip.SURFACE_CLIP_EPSILON, hit.endPos(0), 1e-4f);
    assertEquals(Contents.BODY, hit.contents());
    assertEquals(Trace.PLANE_NON_AXIAL, hit.planeType(), "the negative x side, as the original");
    Trace solidOnly = new Trace();
    model.transformedBoxTrace(
        work,
        solidOnly,
        v(0, 0, 0),
        v(200, 0, 0),
        null,
        null,
        box,
        Contents.SOLID,
        origin,
        ZERO,
        false);
    assertFalse(solidOnly.blocked(), "a temporary box only blocks traces that include BODY");
    assertEquals(
        Contents.BODY, model.transformedPointContents(work, v(110, 0, 0), box, origin, ZERO));
    assertTrue(model.entityContact(work, v(110, -1, -1), v(120, 1, 1), box, origin, ZERO, false));
    assertFalse(model.entityContact(work, v(120, -1, -1), v(130, 1, 1), box, origin, ZERO, false));
    float[] mins = new float[3];
    float[] maxs = new float[3];
    model.modelBounds(work, box, mins, maxs);
    assertEquals(-24, mins[2]);
    assertThrows(IllegalArgumentException.class, () -> model.pointContents(v(0, 0, 0), 7));
  }

  @Test
  void entityTracesMergeNearestFirstAndKeepStartSolid() {
    var model = CollisionModel.build(BrushTraceTest.emptyMap());
    var work = model.work();
    float[] start = {0, 0, 0};
    float[] end = {0, 0, -10};
    Trace result = new Trace().clear(end);
    Trace candidate = new Trace();
    // Entity 1: water volume around the start. Entity 2: a floor below.
    int water = model.tempBoxModel(work, v(-2, -2, -2), v(2, 2, 2), false);
    model.boxTrace(work, candidate, start, end, null, null, water, -1, false);
    assertTrue(candidate.startSolid());
    TraceMerge.merge(result, candidate, 1);
    int floor = model.tempBoxModel(work, v(-3, -3, -4), v(3, 3, -1), false);
    model.boxTrace(work, candidate, start, end, null, null, floor, -1, false);
    TraceMerge.merge(result, candidate, 2);
    assertTrue(result.startSolid());
    assertFalse(result.allSolid());
    assertEquals(-.875f, result.endPos(2), 1e-5f);
    assertEquals(2, result.entityNum());

    // Nearest wins regardless of order; ties keep the first.
    float[] far = {20, 0, 0};
    for (int order = 0; order < 2; order++) {
      Trace merged = new Trace().clear(far);
      for (int k = 0; k < 2; k++) {
        boolean nearFirst = (k == 0) == (order == 0);
        int handle =
            nearFirst
                ? model.tempBoxModel(work, v(4, -1, -1), v(6, 1, 1), false)
                : model.tempBoxModel(work, v(8, -1, -1), v(10, 1, 1), false);
        model.boxTrace(work, candidate, start, far, null, null, handle, Contents.MASK_SHOT, false);
        TraceMerge.merge(merged, candidate, nearFirst ? 10 : 20);
      }
      assertEquals(3.875f, merged.endPos(0), 1e-5f);
      assertEquals(10, merged.entityNum());
    }
    Trace tie = new Trace().clear(far);
    for (int entity : new int[] {30, 31}) {
      int handle = model.tempBoxModel(work, v(4, -1, -1), v(6, 1, 1), false);
      model.boxTrace(work, candidate, start, far, null, null, handle, -1, false);
      TraceMerge.merge(tie, candidate, entity);
    }
    assertEquals(30, tie.entityNum());
  }
}

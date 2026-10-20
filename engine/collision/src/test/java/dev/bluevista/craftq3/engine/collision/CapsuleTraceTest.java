package dev.bluevista.craftq3.engine.collision;

import static dev.bluevista.craftq3.engine.collision.TestMap.v;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Capsules: the box's capsule is a vertical cylinder of radius {@code min(halfWidth, halfHeight)}
 * capped by two spheres whose centres sit {@code halfHeight - radius} above and below the centre.
 */
class CapsuleTraceTest {
  private static final float[] MINS = {-15, -15, -24};
  private static final float[] MAXS = {15, 15, 32};
  private static final float[] ZERO = {0, 0, 0};
  private static final float RADIUS = 15;
  private static final float AXIS = 13;
  private static final float CENTRE = 4;

  private static Trace capsule(CollisionModel model, float[] start, float[] end, int handle) {
    Trace out = new Trace();
    model.boxTrace(model.work(), out, start, end, MINS, MAXS, handle, -1, true);
    return out;
  }

  @Test
  void capsuleRestsOnFloorsLikeItsBox() {
    var model =
        CollisionModel.build(
            TestMap.boxes(
                List.of(
                    new TestMap.BoxSpec(v(-100, -100, -10), v(100, 100, 0), Contents.SOLID, 0))));
    Trace hit = capsule(model, v(0, 0, 100), v(0, 0, -100), 0);
    assertEquals(24 + Clip.SURFACE_CLIP_EPSILON, hit.endPos(2), 1e-4f);
    assertEquals(1, hit.planeNormal(2));
    assertEquals(Contents.SOLID, hit.contents());
    Trace inside = capsule(model, v(0, 0, 20), v(0, 0, 20), 0);
    assertTrue(inside.allSolid());
    assertFalse(capsule(model, v(0, 0, 25), v(0, 0, 25), 0).startSolid());
  }

  @Test
  void capsuleSitsLowerThanTheBoxOnSlopes() {
    float s = (float) Math.sqrt(0.5);
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
    Trace hit = capsule(model, v(0, 0, 200), v(0, 0, -200), 0);
    // The lower sphere touches the ramp: its centre is radius + epsilon off the plane.
    float centre = (RADIUS + Clip.SURFACE_CLIP_EPSILON) / s + AXIS;
    assertEquals(centre - CENTRE, hit.endPos(2), 0.01f);
    Trace box = BrushTraceTest.trace(model, v(0, 0, 200), v(0, 0, -200), MINS, MAXS, -1);
    assertTrue(hit.endPos(2) < box.endPos(2) - 8);
  }

  @Test
  void capsulesAgainstCapsulesUseCylindersAndSpheres() {
    var model = CollisionModel.build(BrushTraceTest.emptyMap());
    var work = model.work();
    int handle = model.tempBoxModel(work, MINS, MAXS, true);
    assertEquals(CollisionModel.CAPSULE_MODEL_HANDLE, handle);
    float reach = 2 * RADIUS + Clip.RADIUS_EPSILON;
    Trace side = capsule(model, v(-100, 0, 0), v(100, 0, 0), handle);
    assertEquals(-reach, side.endPos(0), 1e-3f);
    assertEquals(-1, side.planeNormal(0), 1e-5f);
    assertEquals(Contents.BODY, side.contents());
    Trace top = capsule(model, v(0, 0, 200), v(0, 0, -200), handle);
    // Moving lower sphere (origin + 4 - 13) meets the static upper sphere (4 + 13).
    assertEquals(CENTRE + AXIS + reach + AXIS - CENTRE, top.endPos(2), 1e-3f);
    assertEquals(1, top.planeNormal(2), 1e-5f);
    assertTrue(capsule(model, v(20, 0, 0), v(20, 0, 0), handle).allSolid());
    assertTrue(capsule(model, v(0, 0, 50), v(0, 0, 50), handle).allSolid());
    assertFalse(capsule(model, v(31, 0, 0), v(31, 0, 0), handle).startSolid());
    assertFalse(capsule(model, v(25, 25, 0), v(25, 25, 0), handle).startSolid());
    assertTrue(model.entityContact(work, v(10, -5, 0), v(20, 5, 10), handle, ZERO, ZERO, true));
    assertEquals(Contents.BODY, model.pointContents(work, v(0, 0, 30), handle));
    assertEquals(0, model.pointContents(work, v(14, 14, 30), handle));
  }

  @Test
  void boxesAgainstCapsulesSwapRoles() {
    var model = CollisionModel.build(BrushTraceTest.emptyMap());
    var work = model.work();
    int handle = model.tempBoxModel(work, MINS, MAXS, true);
    Trace hit = new Trace();
    model.boxTrace(work, hit, v(-100, 0, 0), v(100, 0, 0), MINS, MAXS, handle, -1, false);
    // The capsule's radius plus the box's half width, then the usual clip epsilon.
    assertEquals(-2 * RADIUS - Clip.SURFACE_CLIP_EPSILON, hit.endPos(0), 1e-3f);
    assertEquals(-1, hit.planeNormal(0));
    assertEquals(Contents.BODY, hit.contents());
    model.boxTrace(
        work, hit, v(-100, 0, 0), v(100, 0, 0), MINS, MAXS, handle, Contents.SOLID, false);
    assertFalse(hit.blocked(), "the stand-in box has BODY contents");
    model.boxTrace(work, hit, v(25, 0, 0), v(25, 0, 0), MINS, MAXS, handle, -1, false);
    assertTrue(hit.allSolid());
    // Against brushes a capsule only pushes the planes out by its radius, so the stand-in box's
    // corners stay square.
    model.boxTrace(work, hit, v(25, 25, 0), v(25, 25, 0), MINS, MAXS, handle, -1, false);
    assertTrue(hit.allSolid());
    model.boxTrace(work, hit, v(31, 0, 0), v(31, 0, 0), MINS, MAXS, handle, -1, false);
    assertFalse(hit.startSolid());
  }

  @Test
  void rotatedModelsRotateTheCapsuleAxis() {
    // A thin slab inline model pitched 90 degrees stands upright: world |x| <= 8.
    TestMap map = new TestMap();
    int texture = map.texture("textures/slab", 0, Contents.SOLID);
    map.box(v(-64, -64, -8), v(64, 64, 8), texture);
    map.leaf(0, 0, new int[0], new int[0]);
    map.model(TestMap.WORLD_MIN, TestMap.WORLD_MAX, 0, 0, 0, 0);
    map.model(v(-64, -64, -8), v(64, 64, 8), 0, 0, 0, 1);
    var placed = CollisionModel.build(map.build());
    var upright =
        CollisionModel.build(
            TestMap.boxes(
                List.of(new TestMap.BoxSpec(v(-8, -64, -64), v(8, 64, 64), Contents.SOLID, 0))));
    float[] start = {-100, 0, 30};
    float[] end = {100, 0, 30};
    Trace expected = capsule(upright, start, end, 0);
    Trace actual = new Trace();
    placed.transformedBoxTrace(
        placed.work(), actual, start, end, MINS, MAXS, 1, -1, ZERO, v(90, 0, 0), true);
    assertTrue(expected.blocked());
    assertEquals(expected.fraction(), actual.fraction(), 1e-5f);
    assertEquals(expected.planeNormal(0), actual.planeNormal(0), 1e-5f);
    assertEquals(expected.planeNormal(2), actual.planeNormal(2), 1e-5f);
  }
}

package dev.bluevista.craftq3.collision;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BspTraceWorldTest {
  @Test
  void bspTraversalAndDedupAgreeWithIndependentBoxWorldComposition() throws Exception {
    var boxes =
        List.of(
            new CollisionFixtures.Box(
                new Vec3(-30, -5, -5), new Vec3(-20, 5, 5), Contents.SOLID, 2),
            new CollisionFixtures.Box(new Vec3(-2, -4, -4), new Vec3(2, 4, 4), Contents.SOLID, 4),
            new CollisionFixtures.Box(
                new Vec3(20, -6, -6), new Vec3(30, 6, 6), Contents.PLAYERCLIP, 8));
    var bsp = new BspTraceWorld(CollisionFixtures.boxes(boxes));
    var brute =
        new CompositeTraceWorld(
            boxes.stream()
                .map(
                    b ->
                        new BoxTraceWorld(
                            b.min(), b.max(), b.contents(), b.flags(), Contents.WORLD_ENTITY))
                .toList());
    var random = new Random(46);
    for (int i = 0; i < 500; i++) {
      Vec3 start =
          new Vec3(random.nextInt(101) - 50, random.nextInt(21) - 10, random.nextInt(21) - 10);
      Vec3 end =
          new Vec3(random.nextInt(101) - 50, random.nextInt(21) - 10, random.nextInt(21) - 10);
      var request =
          TraceRequest.box(
              start, end, new Vec3(-2, -1, -3), new Vec3(1, 2, 4), Contents.MASK_PLAYERSOLID);
      var expected = brute.trace(request);
      var actual = bsp.trace(request);
      assertEquals(expected.fraction(), actual.fraction(), 1e-10, "trace " + i);
      assertEquals(expected.startSolid(), actual.startSolid(), "trace " + i);
      assertEquals(expected.allSolid(), actual.allSolid(), "trace " + i);
    }
    assertEquals(Contents.PLAYERCLIP, bsp.pointContents(new Vec3(25, 0, 0)));
    var hit = bsp.trace(TraceRequest.ray(new Vec3(-40, 0, 0), new Vec3(40, 0, 0), Contents.SOLID));
    assertEquals(0, hit.hit().orElseThrow().brush());
    assertEquals("textures/fixture0", hit.hit().orElseThrow().shaderName());
  }

  @Test
  void inlineModelTranslationRotationAndWorldRangesStaySeparate() throws Exception {
    var map =
        CollisionFixtures.boxes(
            List.of(
                new CollisionFixtures.Box(
                    new Vec3(-2, -1, -1), new Vec3(2, 1, 1), Contents.SOLID, 4096)));
    var bounds = map.models().getFirst().bounds();
    map =
        CollisionFixtures.withModels(
            map,
            List.of(new BspMap.Model(bounds, 0, 0, 0, 0), new BspMap.Model(bounds, 0, 0, 0, 1)));
    var bsp = new BspTraceWorld(map);
    assertEquals(
        1, bsp.trace(TraceRequest.ray(new Vec3(-5, 0, 0), new Vec3(5, 0, 0), -1)).fraction());
    var inline = bsp.model(1, 7, new Vec3(10, 20, 30), new Vec3(0, 90, 0));
    var hit = inline.trace(TraceRequest.ray(new Vec3(5, 20, 30), new Vec3(15, 20, 30), -1));
    assertEquals(8.875, hit.endPosition().x(), 1e-8);
    assertEquals(7, hit.hit().orElseThrow().entity());
    assertEquals(1, hit.hit().orElseThrow().model());
    assertEquals(-9, hit.hit().orElseThrow().plane().distance(), 1e-8);
    assertEquals(Contents.SOLID, inline.pointContents(new Vec3(10, 21.5, 30)));
    assertEquals(0, inline.pointContents(new Vec3(11.5, 20, 30)));
    assertSame(inline, bsp.model(1, 7, new Vec3(10, 20, 30), new Vec3(0, 90, 0)));
    var moved = bsp.model(1, 7, new Vec3(110, 20, 30), new Vec3(0, 90, 0));
    var movedHit = moved.trace(TraceRequest.ray(new Vec3(105, 20, 30), new Vec3(115, 20, 30), -1));
    assertEquals(hit.fraction(), movedHit.fraction(), 1e-8);
    assertEquals(-109, movedHit.hit().orElseThrow().plane().distance(), 1e-8);
    assertEquals(
        8.875,
        inline
            .trace(TraceRequest.ray(new Vec3(5, 20, 30), new Vec3(15, 20, 30), -1))
            .endPosition()
            .x(),
        1e-8);
  }

  @Test
  void pitchAndRollKeepQ3AxesAndInsideSemantics() throws Exception {
    var map =
        CollisionFixtures.boxes(
            List.of(
                new CollisionFixtures.Box(
                    new Vec3(-4, -2, -1), new Vec3(4, 2, 1), Contents.SOLID, 0)));
    var bsp = new BspTraceWorld(map);
    var pitched = bsp.model(0, 5, new Vec3(10, 20, 30), new Vec3(90, 0, 0));
    assertEquals(Contents.SOLID, pitched.pointContents(new Vec3(10, 20, 26.1)));
    assertEquals(0, pitched.pointContents(new Vec3(11.1, 20, 30)));
    var rolled = bsp.model(0, 6, new Vec3(10, 20, 30), new Vec3(0, 0, 90));
    assertEquals(Contents.SOLID, rolled.pointContents(new Vec3(10, 20, 31.9)));
    assertEquals(0, rolled.pointContents(new Vec3(10, 21.1, 30)));
    var inside = rolled.trace(TraceRequest.ray(new Vec3(10, 20, 30), new Vec3(10, 20, 31), -1));
    assertTrue(inside.allSolid());
    assertEquals(TraceResult.Plane.NONE, inside.hit().orElseThrow().plane());
    assertEquals(new Vec3(10, 20, 30), inside.endPosition());
  }

  @Test
  void rotatedBrushAddsWorldAxisBevelsForSweptAabb() throws Exception {
    var map =
        CollisionFixtures.boxes(
            List.of(
                new CollisionFixtures.Box(
                    new Vec3(-1, -1, -1), new Vec3(1, 1, 1), Contents.SOLID, 0)));
    var world = new BspTraceWorld(map).model(0, 5, new Vec3(0, 0, 0), new Vec3(0, 45, 0));
    var hit =
        world.trace(
            TraceRequest.box(
                new Vec3(3, 0, 0),
                new Vec3(0, 0, 0),
                new Vec3(-.5, -.5, -.5),
                new Vec3(.5, .5, .5),
                Contents.SOLID));
    assertEquals(Math.sqrt(2) + .5 + .125, hit.endPosition().x(), 1e-7);
    assertEquals(1, hit.hit().orElseThrow().plane().normal().x(), 1e-8);
  }

  @Test
  void curvedPatchBlocksFrontTracesAndPreservesEmptyPointContents() throws Exception {
    var world = new BspTraceWorld(CollisionFixtures.patch(0), 8);
    var down =
        world.trace(TraceRequest.ray(new Vec3(0, 0, 100), new Vec3(0, 0, -100), Contents.SOLID));
    assertTrue(down.blocked());
    assertEquals(25, down.endPosition().z(), .2);
    assertEquals(0, down.hit().orElseThrow().face());
    assertEquals(-1, down.hit().orElseThrow().brush());
    assertTrue(down.hit().orElseThrow().plane().normal().z() > .9);
    assertFalse(
        world
            .trace(TraceRequest.ray(new Vec3(0, 0, -100), new Vec3(0, 0, 100), Contents.SOLID))
            .blocked());
    assertEquals(0, world.pointContents(new Vec3(0, 0, 25)));
    var body =
        world.trace(
            TraceRequest.box(
                new Vec3(0, 0, 100),
                new Vec3(0, 0, -100),
                new Vec3(-15, -15, -24),
                new Vec3(15, 15, 32),
                Contents.SOLID));
    assertTrue(body.blocked());
    assertTrue(body.endPosition().z() >= 49);
    var nonSolid = new BspTraceWorld(CollisionFixtures.patch(0x4000));
    assertFalse(
        nonSolid.trace(TraceRequest.ray(new Vec3(0, 0, 100), new Vec3(0, 0, -100), -1)).blocked());
    assertEquals(0, nonSolid.statistics().patchFaces());
  }
}

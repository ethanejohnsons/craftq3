package dev.bluevista.craftq3.collision;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceWorldTest {
  @Test
  void overlappingContentsAndAnEscapingStartSurviveNearestHitComposition() {
    var water =
        new BoxTraceWorld(
            new Vec3(-2, -2, -2), new Vec3(2, 2, 2), Contents.WATER | Contents.TRIGGER, 0, 1);
    var floor = new BoxTraceWorld(new Vec3(-3, -3, -4), new Vec3(3, 3, -1), Contents.SOLID, 0, 2);
    var world = new CompositeTraceWorld(List.of(water, floor));
    assertEquals(
        Contents.WATER | Contents.TRIGGER | Contents.SOLID,
        world.pointContents(new Vec3(0, 0, -1.5)));
    assertEquals(
        Contents.WATER | Contents.TRIGGER,
        world.pointContents(new Vec3(0, 0, -1.5), Contents.WATER));
    var result = world.trace(TraceRequest.ray(new Vec3(0, 0, 0), new Vec3(0, 0, -10), -1));
    assertTrue(result.startSolid());
    assertFalse(result.allSolid());
    assertEquals(-.875, result.endPosition().z(), 1e-9);
    assertEquals(2, result.hit().orElseThrow().entity());
  }

  @Test
  void sweepsAsymmetricPlayerBoxAndReturnsWorldContactPlane() {
    var floor =
        new BoxTraceWorld(
            new Vec3(-100, -100, -10),
            new Vec3(100, 100, 0),
            Contents.SOLID,
            4096,
            Contents.WORLD_ENTITY);
    var request =
        TraceRequest.box(
            new Vec3(0, 0, 100),
            new Vec3(0, 0, -100),
            new Vec3(-15, -15, -24),
            new Vec3(15, 15, 32),
            Contents.MASK_PLAYERSOLID);
    var hit = floor.trace(request);
    assertEquals(24.125, hit.endPosition().z(), 1e-9);
    assertEquals((100 - 24.125) / 200, hit.fraction(), 1e-9);
    assertFalse(hit.startSolid());
    assertFalse(hit.allSolid());
    assertEquals(new Vec3(0, 0, 1), hit.hit().orElseThrow().plane().normal());
    assertEquals(0, hit.hit().orElseThrow().plane().distance());
    assertEquals(4096, hit.hit().orElseThrow().surfaceFlags());
  }

  @Test
  void distinguishesEscapingSolidFromEntirelySolidAndHonorsEntityMasks() {
    var solid = new BoxTraceWorld(new Vec3(-1, -1, -1), new Vec3(1, 1, 1), Contents.BODY, 7, 42);
    var inside =
        solid.trace(TraceRequest.ray(new Vec3(0, 0, 0), new Vec3(.5, 0, 0), Contents.MASK_SHOT));
    assertTrue(inside.startSolid());
    assertTrue(inside.allSolid());
    assertEquals(0, inside.fraction());
    assertEquals(TraceResult.Plane.NONE, inside.hit().orElseThrow().plane());
    var escape =
        solid.trace(TraceRequest.ray(new Vec3(0, 0, 0), new Vec3(2, 0, 0), Contents.MASK_SHOT));
    assertTrue(escape.startSolid());
    assertFalse(escape.allSolid());
    assertEquals(1, escape.fraction());
    assertTrue(escape.hit().isEmpty());
    assertEquals(0, solid.pointContents(new Vec3(0, 0, 0), -1, 42));
    assertEquals(Contents.BODY, solid.pointContents(new Vec3(0, 0, 0)));
    assertFalse(
        solid
            .trace(TraceRequest.ray(new Vec3(-2, 0, 0), new Vec3(2, 0, 0), Contents.SOLID))
            .blocked());
    assertFalse(
        solid
            .trace(
                TraceRequest.ray(new Vec3(-2, 0, 0), new Vec3(2, 0, 0), Contents.MASK_SHOT)
                    .ignoring(42))
            .blocked());
  }

  @Test
  void compositeFindsNearestObstacleIndependentOfSourceOrder() {
    var far = new BoxTraceWorld(new Vec3(8, -1, -1), new Vec3(10, 1, 1), Contents.SOLID, 0, 20);
    var near = new BoxTraceWorld(new Vec3(4, -1, -1), new Vec3(6, 1, 1), Contents.BODY, 0, 10);
    var composite = new CompositeTraceWorld(List.of(far, near));
    var hit =
        composite.trace(
            TraceRequest.ray(new Vec3(0, 0, 0), new Vec3(20, 0, 0), Contents.MASK_SHOT));
    assertEquals(3.875, hit.endPosition().x(), 1e-9);
    assertEquals(10, hit.hit().orElseThrow().entity());
    assertEquals(
        20,
        composite
            .trace(
                TraceRequest.ray(new Vec3(0, 0, 0), new Vec3(20, 0, 0), Contents.MASK_SHOT)
                    .ignoring(10))
            .hit()
            .orElseThrow()
            .entity());
    assertThrows(UnsupportedOperationException.class, () -> composite.worlds().clear());
  }

  @Test
  void parallelAndDegenerateQueriesRemainFinite() {
    var box = new BoxTraceWorld(new Vec3(-1, -1, -1), new Vec3(1, 1, 1), Contents.SOLID, 0, 1);
    var parallel =
        box.trace(TraceRequest.ray(new Vec3(-2, 1.01, 0), new Vec3(2, 1.01, 0), Contents.SOLID));
    assertEquals(1, parallel.fraction());
    assertEquals(
        1,
        box.trace(TraceRequest.ray(new Vec3(2, 2, 2), new Vec3(2, 2, 2), Contents.SOLID))
            .fraction());
    assertThrows(
        IllegalArgumentException.class,
        () -> TraceRequest.ray(new Vec3(1e13, 0, 0), new Vec3(0, 0, 0), -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TraceRequest.box(
                new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(1, 0, 0), new Vec3(-1, 0, 0), -1));
  }
}

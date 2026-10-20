package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import org.junit.jupiter.api.Test;

final class AasTargetBoxTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);
  private static final AasTargetBox BOX =
      new AasTargetBox(new Vec3f(-10, -10, -10), new Vec3f(10, 10, 10));

  @Test
  void expandsTheTargetByTheRequestedPresenceAndClearsTraceMetadata() {
    var hit = BOX.clip(new Vec3f(-100, 0, 0), new Vec3f(100, 0, 0), 2).orElseThrow();
    assertEquals(.375f, hit.fraction());
    assertEquals(new Vec3f(-25, 0, 0), hit.endPosition());
    assertFalse(hit.startSolid());
    assertEquals(0, hit.entity() | hit.lastArea() | hit.area() | hit.planeNumber());
    assertEquals(-42, BOX.clip(ZERO, new Vec3f(0, 0, 1), 2).orElseThrow().fraction());
    assertEquals(-18, BOX.clip(ZERO, new Vec3f(0, 0, 1), 4).orElseThrow().fraction());
  }

  @Test
  void exactEdgesAndCornersAreExcludedWhileAdjacentFaceInteriorIsIncluded() {
    assertTrue(BOX.clip(new Vec3f(-100, -100, 0), new Vec3f(100, 100, 0), 2).isEmpty());
    assertTrue(BOX.clip(new Vec3f(-100, 25, 0), new Vec3f(100, 25, 0), 2).isEmpty());
    float interior = Math.nextDown(25f);
    assertTrue(BOX.clip(new Vec3f(-100, interior, 0), new Vec3f(100, interior, 0), 2).isPresent());
    assertEquals(
        1, BOX.clip(new Vec3f(-100, 0, 0), new Vec3f(-25, 0, 0), 2).orElseThrow().fraction());
  }

  @Test
  void initialOverlapRetainsNegativeEntryRatherThanClampingOrMarkingStartSolid() {
    var hit = BOX.clip(ZERO, new Vec3f(100, 0, 0), 2).orElseThrow();
    assertEquals(-.25f, hit.fraction());
    assertEquals(new Vec3f(-25, 0, 0), hit.endPosition());
    assertFalse(hit.startSolid());
    assertTrue(BOX.clip(ZERO, ZERO, 2).isEmpty());
    assertTrue(BOX.clip(new Vec3f(100, 0, 0), new Vec3f(200, 0, 0), 2).isEmpty());
  }

  @Test
  void endpointPlaneDistancesPreserveObservedFloatOrderAndTinySegmentOrientation() {
    var box =
        new AasTargetBox(
            new Vec3f(98.41584777832031f, 5.307497501373291f, 83.7602310180664f),
            new Vec3f(175.1595916748047f, 129.10935974121094f, 214.01382446289062f));
    var hit =
        box.clip(
                new Vec3f(-185.93582153320312f, 29.603628158569336f, -60.07548522949219f),
                new Vec3f(151.2850341796875f, 29.603628158569336f, 138.05914306640625f),
                4)
            .orElseThrow();
    assertEquals(Float.intBitsToFloat(0x3f4c7a33), hit.fraction());

    var behind =
        new AasTargetBox(
            new Vec3f(-2.7663700580596924f, -30.39415168762207f, -197.83168029785156f),
            new Vec3f(39.49232482910156f, 47.87572479248047f, -50.92450714111328f));
    assertTrue(
        behind
            .clip(
                new Vec3f(45.3240891f, 23.3530178f, -1.90734863e-6f),
                new Vec3f(45.3240891f, 23.3530178f, 0),
                2)
            .isEmpty());
  }

  @Test
  void targetModeTestsTheTracedSegmentBeforeOtherEvents() {
    var world = new FloorWorld();
    var predictor = new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults());
    var hit =
        predictor
            .predictHitBox(request(3, 0), new Vec3f(-10, -10, 0), new Vec3f(10, 10, 10))
            .orElseThrow();
    assertEquals(AasMovementPredictor.HIT_BOUNDING_BOX, hit.stopEvent());
    assertEquals(new Vec3f(0, 0, 34), hit.endPosition());
    assertEquals(new Vec3f(0, 0, -1080), hit.velocity());
    assertEquals(.6608479022979736f, hit.trace().orElseThrow().fraction());
    assertEquals(0, hit.frames());
    assertEquals(1, world.traces);
    assertEquals(1, world.contents);
  }

  @Test
  void targetMissesDoNotApplyOrdinaryContactSliding() {
    var world = new FloorWorld();
    var predictor = new AasMovementPredictor(world, AasMovementPredictor.Settings.defaults());
    var miss =
        predictor
            .predictHitBox(request(3, 0), new Vec3f(-10, -10, -100), new Vec3f(10, 10, -90))
            .orElseThrow();
    assertEquals(0, miss.stopEvent());
    assertEquals(ZERO, miss.endPosition());
    assertEquals(new Vec3f(0, 0, -1240), miss.velocity());
    assertEquals(3, miss.frames());
    assertEquals(6, world.traces);
    assertEquals(ZERO, miss.trace().orElseThrow().endPosition());
    assertEquals(0, miss.trace().orElseThrow().fraction());
  }

  @Test
  void targetModeKeepsTheOrdinaryStopMaskGuardAndValidatesItsOwnBoundary() {
    var predictor =
        new AasMovementPredictor(new FloorWorld(), AasMovementPredictor.Settings.defaults());
    // The ordinary prediction ignores the bounding-box bit, which only predictHitBox handles.
    assertEquals(
        0,
        predictor
            .predict(request(1, AasMovementPredictor.HIT_BOUNDING_BOX))
            .orElseThrow()
            .stopEvent());
    assertThrows(
        IllegalArgumentException.class,
        () -> predictor.predictHitBox(request(1, 1), ZERO, new Vec3f(1, 1, 1)));
    assertThrows(IllegalArgumentException.class, () -> new AasTargetBox(new Vec3f(1, 0, 0), ZERO));
    assertThrows(IllegalArgumentException.class, () -> BOX.clip(ZERO, ZERO, 0));
    var empty = predictor.predictHitBox(request(0, 0), ZERO, new Vec3f(1, 1, 1)).orElseThrow();
    assertEquals(new Vec3f(0, 0, 100.25f), empty.endPosition());
    assertEquals(0, empty.stopEvent());
  }

  private static AasMovementPredictor.Request request(int frames, int events) {
    return new AasMovementPredictor.Request(
        3, new Vec3f(0, 0, 100), 2, false, new Vec3f(0, 0, -1000), ZERO, 0, frames, .1f, events);
  }

  private static final class FloorWorld implements AasMovementPredictor.World {
    int traces, contents;

    @Override
    public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
      traces++;
      float from = start.z(), to = end.z();
      float fraction = to < 0 ? from / (from - to) : 1;
      return new AasPresenceTrace.Result(
          false,
          fraction,
          new Vec3f(end.x(), end.y(), from + fraction * (to - from)),
          0,
          1,
          0,
          0,
          0);
    }

    @Override
    public Vec3f planeNormal(int plane) {
      return new Vec3f(0, 0, 1);
    }

    @Override
    public int area(Vec3f point) {
      return 1;
    }

    @Override
    public int presence(Vec3f point) {
      return 6;
    }

    @Override
    public int contents(Vec3f point) {
      contents++;
      return 0;
    }
  }
}

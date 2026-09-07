package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AasPresenceTraceTest {
  @Test
  void collisionFractionPrecedesTheSecondPositionBackoff() {
    var result = new AasPresenceTrace(map()).trace(new Vec3(10, 10, 0), new Vec3(10, -10, 0), 2);
    assertFalse(result.startSolid());
    assertEquals(.49375f, result.fraction(), 1e-7);
    assertEquals(.25, result.endPosition().y(), 1e-6);
    assertEquals(1, result.lastArea());
    assertEquals(2, result.area());
    assertEquals(2, result.planeNumber());
    assertEquals(0, result.entity());
    var reverse = new AasPresenceTrace(map()).trace(new Vec3(10, -10, 0), new Vec3(10, 10, 0), 4);
    assertEquals(-.25, reverse.endPosition().y(), 1e-6);
    assertEquals(3, reverse.planeNumber());
  }

  @Test
  void permittedAreasUseAnyMatchingPresenceBitAndLeaveEmptyHitFieldsClear() {
    var end = new Vec3(10, -10, 0);
    var result = new AasNavigation(map()).presenceTrace(new Vec3(-10, 10, 0), end, 6);
    assertEquals(1, result.fraction());
    assertEquals(end, result.endPosition());
    assertEquals(2, result.lastArea());
    assertEquals(0, result.area());
    assertEquals(0, result.planeNumber());
    assertFalse(result.startSolid());
  }

  @Test
  void initialBlockedAreasAndSolidLeavesDoNotMoveTheStart() {
    var start = new Vec3(10, -10, 0);
    var result = new AasPresenceTrace(map()).trace(start, new Vec3(-10, 10, 0), 2);
    assertTrue(result.startSolid());
    assertEquals(0, result.fraction());
    assertEquals(start, result.endPosition());
    assertEquals(0, result.lastArea());
    assertEquals(2, result.area());
    var solidMap = map(List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, 0, -3)));
    result = new AasPresenceTrace(solidMap).trace(new Vec3(-10, 0, 0), new Vec3(10, 0, 0), 2);
    assertFalse(result.startSolid());
    assertEquals(0, result.area());
    assertEquals(1, result.planeNumber());
    assertEquals(-.25, result.endPosition().x(), 1e-6);
  }

  @Test
  void boundaryStartKeepsNativeMinimumSplitAndCanBackoffBeyondTheStart() {
    var trace = new AasPresenceTrace(map());
    var result = trace.trace(new Vec3(10, 0, 0), new Vec3(10, -10, 0), 2);
    assertFalse(result.startSolid());
    assertEquals(.001f, result.fraction(), 1e-7);
    assertEquals(.115, result.endPosition().y(), 1e-6);
    result = trace.trace(new Vec3(10, 10, 0), new Vec3(10, 0, 0), 2);
    assertEquals(1, result.fraction());
    assertEquals(0, result.endPosition().y());
  }

  @Test
  void exactMarginIsStartSolidAfterAZeroLengthPermittedLeafAndKeepsTheRawPlane() {
    var trace = new AasPresenceTrace(map());
    for (int direction : new int[] {1, -1}) {
      var start = new Vec3(10, direction * .125, 0);
      int presence = direction > 0 ? 2 : 4;
      var end = new Vec3(10, direction * -10, 0);
      var result = trace.trace(start, end, presence);
      assertTrue(result.startSolid());
      assertEquals(0, result.fraction());
      assertEquals(start, result.endPosition());
      assertEquals(direction > 0 ? 1 : 2, result.lastArea());
      assertEquals(direction > 0 ? 2 : 1, result.area());
      assertEquals(2, result.planeNumber());
      for (float margin : new float[] {Math.nextDown(.125f), Math.nextUp(.125f)}) {
        result = trace.trace(new Vec3(10, direction * margin, 0), end, presence);
        assertFalse(result.startSolid());
        assertTrue(result.fraction() > 0);
        assertEquals(direction > 0 ? 2 : 3, result.planeNumber());
      }
    }
  }

  @Test
  void tinyPositiveSplitsRemainUnclampedAndBackoffUsesTheFullRay() {
    var trace = new AasPresenceTrace(map());
    var result = trace.trace(new Vec3(10, .625, 0), new Vec3(10, -999.375, 0), 2);
    assertEquals(.0005f, result.fraction(), 1e-8);
    assertEquals(.25, result.endPosition().y(), 1e-6);
    var start = new Vec3(1000, .12501f, 1000);
    var end = new Vec3(2000, -999.875f, -2000);
    result = trace.trace(start, end, 2);
    float fraction = (.12501f - .125f) / 1000;
    float length = (float) Math.sqrt(1000f * 1000f + 1000f * 1000f + 3000f * 3000f);
    assertEquals(1000f + fraction * 1000f - .125f * (1000f / length), result.endPosition().x());
    assertEquals(1000f - fraction * 3000f + .125f * (3000f / length), result.endPosition().z());
  }

  @Test
  void queryWorkAndFloatCoordinateRangesAreBounded() {
    var cyclic = map(List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, 1, -3)));
    var trace = new AasPresenceTrace(cyclic, 3);
    assertThrows(
        IllegalStateException.class, () -> trace.trace(new Vec3(1, 1, 1), new Vec3(2, 2, 2), 2));
    assertThrows(IllegalArgumentException.class, () -> new AasPresenceTrace(map(), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AasPresenceTrace(map()).trace(new Vec3(1e10, 0, 0), new Vec3(0, 0, 0), 2));
  }

  private static AasMap map() {
    return map(
        List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, 2, -3), new AasMap.Node(2, -1, -2)));
  }

  private static AasMap map(List<AasMap.Node> nodes) {
    var zero = new Vec3(0, 0, 0);
    var indices = new AasMap.Indices(new int[0]);
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new AasMap.Plane(new Vec3(1, 0, 0), 0, 0),
            new AasMap.Plane(new Vec3(-1, 0, 0), 0, 0),
            new AasMap.Plane(new Vec3(0, 1, 0), 0, 1),
            new AasMap.Plane(new Vec3(0, -1, 0), 0, 1)),
        List.of(),
        indices,
        List.of(),
        indices,
        List.of(
            new AasMap.Area(0, 0, 0, zero, zero, zero),
            new AasMap.Area(1, 0, 0, zero, zero, zero),
            new AasMap.Area(2, 0, 0, zero, zero, zero),
            new AasMap.Area(3, 0, 0, zero, zero, zero)),
        List.of(
            new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AasMap.AreaSettings(0, 0, 2, 0, 0, 0, 0),
            new AasMap.AreaSettings(0, 0, 4, 0, 0, 0, 0),
            new AasMap.AreaSettings(0, 0, 6, 0, 0, 0, 0)),
        List.of(),
        nodes,
        List.of(),
        indices,
        List.of());
  }
}

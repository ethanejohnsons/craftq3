package dev.bluevista.craftq3.engine.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.*;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AasAreaTraceTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  private static AasAreaTrace.Entry entry(int area, Vec3f point) {
    return new AasAreaTrace.Entry(area, point);
  }

  @Test
  void crossingReturnsRawOrderedEntryPointsAndOmitsSolidLeaves() {
    Vec3f start = new Vec3f(-1, 2, 3), end = new Vec3f(1, 4, 5), middle = new Vec3f(0, 3, 4);
    assertEquals(
        List.of(entry(2, start), entry(1, middle)),
        AasAreaTrace.trace(half(1, 2, 0), start, end, 10, 20));
    assertEquals(List.of(entry(1, middle)), AasAreaTrace.trace(half(1, 0, 0), start, end, 10, 20));
  }

  @Test
  void exactPlaneBoundariesPreserveNativeAsymmetricZeroLengthVisits() {
    var map = half(1, 2, 0);
    Vec3f positive = new Vec3f(1, 0, 0), negative = new Vec3f(-1, 0, 0);
    assertEquals(
        List.of(entry(1, ZERO), entry(2, ZERO)), AasAreaTrace.trace(map, ZERO, positive, 10, 20));
    assertEquals(List.of(entry(2, ZERO)), AasAreaTrace.trace(map, ZERO, negative, 10, 20));
    assertEquals(List.of(entry(2, ZERO)), AasAreaTrace.trace(map, ZERO, ZERO, 10, 20));
    assertEquals(
        List.of(entry(1, positive), entry(2, ZERO)),
        AasAreaTrace.trace(map, positive, ZERO, 10, 20));
    assertEquals(List.of(entry(2, negative)), AasAreaTrace.trace(map, negative, ZERO, 10, 20));
  }

  @Test
  void repeatedAreasConsumeResultSlotsWithoutCoalescing() {
    Vec3f start = new Vec3f(-1, 0, 0), end = new Vec3f(1, 0, 0);
    var map = half(1, 1, 0);
    assertEquals(
        List.of(entry(1, start), entry(1, ZERO)), AasAreaTrace.trace(map, start, end, 10, 20));
    assertEquals(List.of(entry(1, start)), AasAreaTrace.trace(map, start, end, 1, 2));
  }

  @Test
  void floatInterpolationMatchesAnIndependentNativeHalfSpaceProbe() {
    var result =
        AasAreaTrace.trace(
            half(1, 2, .125f),
            new Vec3f(.75f, 123.4567f, 21.7f),
            new Vec3f(-.375f, -37.3333f, 5.9f),
            10,
            20);
    assertEquals(new Vec3f(.75f, 123.4567f, 21.7f), result.getFirst().point());
    assertEquals(new Vec3f(.125f, 34.1289139f, 12.9222221f), result.get(1).point());
  }

  @Test
  void resultOwnershipAndWorkAndNumericBoundsAreExplicit() {
    var map = half(1, 2, 0);
    Vec3f start = new Vec3f(-1, 0, 0), end = new Vec3f(1, 0, 0);
    assertThrows(
        UnsupportedOperationException.class,
        () -> AasAreaTrace.trace(map, start, end, 10, 20).clear());
    assertThrows(IllegalStateException.class, () -> AasAreaTrace.trace(map, start, end, 10, 2));
    assertThrows(IllegalArgumentException.class, () -> AasAreaTrace.trace(map, start, end, 0, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> AasAreaTrace.trace(map, new Vec3f(Float.POSITIVE_INFINITY, 0, 0), end, 10, 20));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AasAreaTrace.trace(
                map, new Vec3f(-1, Float.MAX_VALUE, 0), new Vec3f(1, -Float.MAX_VALUE, 0), 10, 20));
    var cycle = half(-1, 0, 0);
    assertThrows(IllegalStateException.class, () -> AasAreaTrace.trace(cycle, end, end, 10, 20));
  }

  private static AasWorld half(int front, int back, float distance) {
    var planes =
        List.of(
            new Plane(new Vec3f(1, 0, 0), distance, 0),
            new Plane(new Vec3f(-1, 0, 0), -distance, 0));
    var nodes = List.of(new Node(0, 0, 0), new Node(0, -front, -back));
    var areas =
        List.of(
            new Area(0, 0, 0, ZERO, ZERO, ZERO),
            new Area(1, 0, 0, ZERO, ZERO, ZERO),
            new Area(2, 0, 0, ZERO, ZERO, ZERO));
    var settings =
        List.of(
            new AreaSettings(0, 0, 0, 0, 0, 0, 0),
            new AreaSettings(0, 1, 6, 1, 0, 0, 0),
            new AreaSettings(0, 1, 6, 1, 1, 0, 0));
    return new AasWorld(
        4,
        0,
        List.of(),
        List.of(),
        List.of(),
        planes,
        List.of(),
        new Indices(new int[0]),
        List.of(),
        new Indices(new int[0]),
        areas,
        settings,
        List.of(),
        nodes,
        List.of(),
        new Indices(new int[0]),
        List.of());
  }
}

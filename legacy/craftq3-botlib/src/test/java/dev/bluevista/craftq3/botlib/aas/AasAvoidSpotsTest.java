package dev.bluevista.craftq3.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.botlib.movement.BotMovement.AvoidSpot;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AasAvoidSpotsTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void walkingCanLeaveASpotButApproachingItsCenterBlocks() {
    assertEquals(0, spots(ZERO, 6, 1).type(ZERO, reach(2, 10, 20)));
    assertEquals(1, spots(new Vec3(5, 0, 0), 1, 1).type(ZERO, reach(2, 10, 20)));
    // A second segment moving closer matters even when the first segment moved away.
    assertEquals(1, spots(new Vec3(5, 0, 0), 10, 1).type(ZERO, reach(2, 20, 10)));
  }

  @Test
  void specialTravelKindsOnlyCheckApproachAndDoNotPermitLeavingTheSpot() {
    for (int type = 0; type <= 19; type++) {
      boolean approachOnly =
          switch (type) {
            case 5, 7, 10, 11, 12, 13, 14, 18, 19 -> true;
            default -> false;
          };
      for (int flags : new int[] {0, 0x01000000, 0x02000000}) {
        assertEquals(
            approachOnly ? 1 : 0, spots(ZERO, 1, 1).type(ZERO, reach(type | flags, 10, 20)));
        assertEquals(
            approachOnly ? 0 : 1,
            spots(new Vec3(15, 0, 0), 1, 1).type(ZERO, reach(type | flags, 10, 20)));
      }
    }
  }

  @Test
  void sphereBoundaryIsStrictAndRadiusSignIsPreservedBySquaring() {
    var center = new Vec3(5, 3, 0);
    assertEquals(0, spots(center, 3, 1).type(ZERO, reach(2, 10, 20)));
    assertEquals(1, spots(center, Math.nextUp(3f), 1).type(ZERO, reach(2, 10, 20)));
    assertEquals(1, spots(center, -4, 1).type(ZERO, reach(2, 10, 20)));
    assertEquals(0, spots(new Vec3(5, 0, 0), 0, 1).type(ZERO, reach(2, 10, 20)));
  }

  @Test
  void zeroLengthSegmentsRemainNonintersecting() {
    assertEquals(0, spots(ZERO, 100, 1).type(ZERO, reach(5, 0, 10)));
    assertEquals(0, spots(ZERO, 100, 1).type(ZERO, reach(2, 0, 0)));
    assertEquals(1, spots(new Vec3(5, 0, 0), 1, 1).type(ZERO, reach(2, 0, 10)));
  }

  @Test
  void nativeTypeOrderingKeepsLastHitExceptImmediateAlways() {
    var center = new Vec3(5, 0, 0);
    for (int first : new int[] {0, 1, 2, -1, 3})
      for (int second : new int[] {0, 1, 2, -1, 3}) {
        var spots =
            new AasAvoidSpots(
                List.of(new AvoidSpot(center, 1, first), new AvoidSpot(center, 1, second)));
        assertEquals(first == 1 ? 1 : second, spots.type(ZERO, reach(2, 10, 20)));
      }
  }

  @Test
  void boundedSpotCollectionIsCopiedAndInputsMustFitFloat32() {
    var input = new ArrayList<AvoidSpot>();
    input.add(new AvoidSpot(new Vec3(5, 0, 0), 1, 2));
    var spots = new AasAvoidSpots(input);
    input.clear();
    assertEquals(2, spots.type(ZERO, reach(2, 10, 20)));
    assertDoesNotThrow(() -> new AasAvoidSpots(Collections.nCopies(32, new AvoidSpot(ZERO, 1, 1))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new AasAvoidSpots(Collections.nCopies(33, new AvoidSpot(ZERO, 1, 1))));
    assertThrows(
        IllegalArgumentException.class, () -> spots.type(new Vec3(1e100, 0, 0), reach(2, 10, 20)));
  }

  private static AasAvoidSpots spots(Vec3 origin, float radius, int type) {
    return new AasAvoidSpots(List.of(new AvoidSpot(origin, radius, type)));
  }

  private static Reachability reach(int type, double start, double end) {
    return new Reachability(1, 0, 0, new Vec3(start, 0, 0), new Vec3(end, 0, 0), type, 1, 0);
  }
}

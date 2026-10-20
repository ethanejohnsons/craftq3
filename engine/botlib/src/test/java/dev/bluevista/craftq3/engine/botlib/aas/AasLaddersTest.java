package dev.bluevista.craftq3.engine.botlib.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.*;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Ladder contact over an authored one-area map: a 20x20 ladder face lies in the plane x = 0, and
 * everything with x below -1.5 is solid.
 */
final class AasLaddersTest {
  @Test
  void pointsNearTheLadderFaceAndInsideItsEdgesAreAgainstTheLadder() {
    var ladders = ladders(Contents.AREAFLAG_LADDER, Contents.PRESENCE_NORMAL);
    assertTrue(ladders.against(new Vec3f(2, 0, 0)));
    assertTrue(ladders.against(new Vec3f(0, 9.9f, -9.9f)));
    assertFalse(ladders.against(new Vec3f(0, 10.5f, 0)), "outside the face edges");
    assertFalse(ladders.against(new Vec3f(0, 0, -10.5f)), "outside the face edges");
  }

  @Test
  void theDistanceToTheFacePlaneIsTruncatedToAnIntegerBeforeTheThreeUnitTest() {
    var ladders = ladders(Contents.AREAFLAG_LADDER, Contents.PRESENCE_NORMAL);
    assertTrue(ladders.against(new Vec3f(2.99f, 0, 0)));
    assertFalse(ladders.against(new Vec3f(3, 0, 0)));
  }

  @Test
  void aPointInSolidIsNudgedSidewaysToFindItsArea() {
    var ladders = ladders(Contents.AREAFLAG_LADDER, Contents.PRESENCE_NORMAL);
    // x = -2 is solid; the +1 x nudge lands in the ladder area, and the plane distance still uses
    // the original point (|-2| < 3).
    assertTrue(ladders.against(new Vec3f(-2, 0, 0)));
    assertFalse(ladders.against(new Vec3f(-3, 0, 0)), "nudges move at most one unit");
  }

  @Test
  void onlyNormalPresenceLadderAreasCount() {
    var origin = new Vec3f(1, 0, 0);
    assertFalse(ladders(0, Contents.PRESENCE_NORMAL).against(origin));
    assertFalse(ladders(Contents.AREAFLAG_LADDER, Contents.PRESENCE_CROUCH).against(origin));
    assertTrue(
        ladders(Contents.AREAFLAG_LADDER, Contents.PRESENCE_NORMAL | Contents.PRESENCE_CROUCH)
            .against(origin));
  }

  private static AasLadders ladders(int areaFlags, int presence) {
    var zero = Vec3f.ZERO;
    // Counter-clockwise seen from +x, so each edge's separating plane faces the face's inside.
    var vertices =
        List.of(
            new Vec3f(0, -10, -10),
            new Vec3f(0, -10, 10),
            new Vec3f(0, 10, 10),
            new Vec3f(0, 10, -10));
    var map =
        new AasWorld(
            5,
            0,
            List.of(),
            List.of(),
            vertices,
            List.of(new Plane(new Vec3f(1, 0, 0), 0, 0), new Plane(new Vec3f(1, 0, 0), -1.5f, 0)),
            List.of(new Edge(0, 0), new Edge(0, 1), new Edge(1, 2), new Edge(2, 3), new Edge(3, 0)),
            new Indices(new int[] {1, 2, 3, 4}),
            List.of(new Face(0, 0, 0, 0, 0, 0), new Face(0, Contents.FACE_LADDER, 4, 0, 1, 0)),
            new Indices(new int[] {1}),
            List.of(
                new Area(0, 0, 0, zero, zero, zero),
                new Area(1, 1, 0, new Vec3f(-1.5f, -10, -10), new Vec3f(10, 10, 10), zero)),
            List.of(
                new AreaSettings(0, 0, 0, 0, 0, 0, 0),
                new AreaSettings(0, areaFlags, presence, 1, 0, 0, 0)),
            List.of(),
            List.of(new Node(0, 0, 0), new Node(1, -1, 0)),
            List.of(),
            new Indices(new int[0]),
            List.of());
    return new AasLadders(new AasNavigation(map));
  }
}

package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.botlib.testing.*;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class MoverQueriesTest {
  static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void firstRetainedMoverTypeWinsIncludingEntityZero() {
    var f = new Fixture();
    f.entities.put(9, new MoverQueries.Entity(0, 11, ZERO));
    f.entities.put(12, new MoverQueries.Entity(4, 11, new Vec3f(1, 2, 3)));
    f.entities.put(19, new MoverQueries.Entity(4, 11, new Vec3f(4, 5, 6)));
    assertEquals(new Vec3f(1, 2, 3), f.queries.originForModel(11).orElseThrow());
    f.entities.put(0, new MoverQueries.Entity(4, 11, new Vec3f(9, 8, 7)));
    assertEquals(new Vec3f(9, 8, 7), f.queries.originForModel(11).orElseThrow());
    assertTrue(f.queries.originForModel(12).isEmpty());
  }

  @Test
  void signedPackedEndpointsAndAxisPriorityOnlyTranslateTheChosenCoordinate() {
    var f = new Fixture();
    f.entities.put(12, new MoverQueries.Entity(4, 11, new Vec3f(100, 200, 300)));
    for (int axis = 0; axis < 3; axis++) {
      int face = 11 | (axis == 0 ? 65536 | 131072 : axis == 1 ? 131072 : 0);
      var g = f.queries.bobbing(reach(face, (-120 << 16) | 345)).orElseThrow();
      assertEquals(axis, g.axis());
      assertEquals(
          axis == 0
              ? new Vec3f(-120, 0, 0)
              : axis == 1 ? new Vec3f(0, -120, 0) : new Vec3f(0, 0, -120),
          g.start());
      assertEquals(
          axis == 0
              ? new Vec3f(345, 0, 0)
              : axis == 1 ? new Vec3f(0, 345, 0) : new Vec3f(0, 0, 345),
          g.end());
      assertEquals(
          axis == 0
              ? new Vec3f(100, 0, 0)
              : axis == 1 ? new Vec3f(0, 200, 0) : new Vec3f(0, 0, 300),
          g.current());
    }
  }

  @Test
  void contactChecksInclusiveExpandedXYBoundsWithAHeightIndependentTrace() {
    var f = new Fixture();
    f.entities.put(12, new MoverQueries.Entity(4, 11, new Vec3f(100, 200, 300)));
    f.hit = 12;
    assertTrue(f.queries.onMover(new Vec3f(148, 248, 1000), 7, reach(11, 0)));
    var r = f.requests.getFirst();
    assertEquals(new Vec3f(148, 248, 1024), r.start());
    assertEquals(new Vec3f(148, 248, 952), r.end());
    assertEquals(new Vec3f(-16, -16, -8), r.mins());
    assertEquals(new Vec3f(16, 16, 8), r.maxs());
    assertEquals(65537, r.contentsMask());
    assertEquals(7, r.ignoreEntity());
    assertFalse(f.queries.onMover(new Vec3f(Math.nextUp(148f), 248, 1000), 7, reach(11, 0)));
    assertEquals(1, f.requests.size());
  }

  @Test
  void contactUsesReturnedModelEvenAtFractionOneButRejectsSolidStarts() {
    var f = new Fixture();
    f.entities.put(12, new MoverQueries.Entity(4, 11, ZERO));
    f.entities.put(13, new MoverQueries.Entity(0, 11, ZERO));
    f.hit = 13;
    assertTrue(f.queries.onMover(ZERO, 0, reach(11, 0)));
    f.solid = true;
    assertFalse(f.queries.onMover(ZERO, 0, reach(11, 0)));
    f.solid = false;
    f.hit = 14;
    assertFalse(f.queries.onMover(ZERO, 0, reach(11, 0)));
  }

  @Test
  void missingMoverSkipsBoundsAndContactAndInvalidConfigurationIsRejected() {
    var f = new Fixture();
    assertTrue(f.queries.bobbing(reach(11, 0)).isEmpty());
    assertFalse(f.queries.onMover(ZERO, 0, reach(11, 0)));
    assertTrue(f.requests.isEmpty());
    assertThrows(IllegalArgumentException.class, () -> f.queries.onMover(ZERO, -1, reach(11, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MoverQueries.ModelBounds(new Vec3f(1, 0, 0), ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MoverQueries(f, m -> null, e -> Optional.empty(), 1025));
  }

  static Reachability reach(int face, int edge) {
    return new Reachability(1, face, edge, ZERO, ZERO, 19, 1, 0);
  }

  static final class Fixture implements TraceWorld {
    final HashMap<Integer, MoverQueries.Entity> entities = new HashMap<>();
    final ArrayList<TraceRequest> requests = new ArrayList<>();
    int hit;
    boolean solid;
    final MoverQueries queries =
        new MoverQueries(
            this,
            m -> new MoverQueries.ModelBounds(new Vec3f(-32, -32, -8), new Vec3f(32, 32, 8)),
            e -> Optional.ofNullable(entities.get(e)),
            1024);

    public TraceResult trace(TraceRequest r) {
      requests.add(r);
      return new TraceResult(
          1,
          r.end(),
          solid,
          false,
          Optional.of(new TraceResult.Hit(TraceResult.Plane.NONE, 1, 0, hit, 0, -1, -1, -1, "")));
    }

    public int pointContents(Vec3f p, int mask, int entity) {
      return 0;
    }
  }
}

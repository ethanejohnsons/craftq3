package dev.bluevista.craftq3.botlib.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceResult;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JumpPadItemAreasTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final Vec3 MIN = new Vec3(-15, -15, -15), MAX = new Vec3(15, 15, 15);

  @Test
  void launchUsesBrushCenterForVelocityAndCrouchTraceForOrigin() {
    var world = new World();
    world.launchFraction = .25f;
    var bsp =
        bsp(
            List.of(
                Map.of("model", "*1", "target", "t", "speed", "99999", "origin", "999 999 999"),
                Map.of("classname", "arbitrary", "targetname", "t", "origin", "110 220 430")),
            new Vec3(-10, -20, 10),
            new Vec3(30, 60, 50));
    var result = new JumpPadLaunch(bsp, world, ignored -> {}).resolve(0, 800).orElseThrow();
    assertEquals(new Vec3(10, 20, 78.125), result.origin());
    assertEquals(new Vec3(110.000008f, 220.000015f, 800), result.velocity());
    assertEquals(new Vec3(-10, -20, 10), result.minimum());
    assertEquals(new Vec3(30, 60, 50), result.maximum());
    assertEquals(
        List.of(new Query(new Vec3(10, 20, 94), new Vec3(10, 20, 30), 4, -1)), world.queries);
  }

  @Test
  void launchStartSolidUsesCenterAndFirstExactTargetName() {
    var world = new World();
    world.launchSolid = true;
    var messages = new ArrayList<String>();
    var bsp =
        bsp(
            List.of(
                Map.of("model", "*1", "target", "t"),
                Map.of("targetname", "T", "origin", "0 0 1000"),
                Map.of("targetname", "t", "origin", "0 0 100"),
                Map.of("targetname", "t", "origin", "0 0 1000")),
            MIN,
            MAX);
    var result = new JumpPadLaunch(bsp, world, messages::add).resolve(0, 800).orElseThrow();
    assertEquals(new Vec3(0, 0, .125), result.origin());
    assertEquals(new Vec3(0, 0, 400), result.velocity());
    assertEquals(1, messages.size());
    assertTrue(messages.getFirst().contains("starts solid"));
  }

  @Test
  void missingAndNonfiniteLaunchMetadataRemainExplicit() {
    var world = new World();
    for (String model : List.of("", "1", "*999", "*9999999999")) {
      var provider =
          new JumpPadLaunch(bsp(List.of(Map.of("model", model)), MIN, MAX), world, ignored -> {});
      assertTrue(provider.resolve(0, 800).isEmpty());
    }
    assertTrue(world.queries.isEmpty());
    for (String origin : List.of("0 0 0", "not a vector", "NaN 0 100")) {
      var provider =
          new JumpPadLaunch(
              bsp(
                  List.of(
                      Map.of("model", "*1", "target", "t"),
                      Map.of("targetname", "t", "origin", origin)),
                  MIN,
                  MAX),
              world,
              ignored -> {});
      assertTrue(provider.resolve(0, 800).isEmpty());
    }
    var below =
        new JumpPadLaunch(
            bsp(
                List.of(
                    Map.of("model", "*1", "target", "t"),
                    Map.of("targetname", "t", "origin", "0 0 -100")),
                MIN,
                MAX),
            world,
            ignored -> {});
    assertThrows(IllegalArgumentException.class, () -> below.resolve(0, 800));
    assertThrows(IllegalArgumentException.class, () -> below.resolve(-1, 800));
    assertThrows(IllegalArgumentException.class, () -> below.resolve(0, Float.NaN));
    var missing =
        new JumpPadLaunch(
            bsp(List.of(Map.of("model", "*1", "target", "missing")), MIN, MAX),
            world,
            ignored -> {});
    assertTrue(missing.resolve(0, 800).isEmpty());
  }

  @Test
  void earlyTrajectoryUsesLastEqualVolumeAreaWithoutReachabilityOrPresenceFilter() {
    var world = new World();
    int[] settingsCalls = {0};
    var resolver =
        new JumpPadItemAreas(
            navigation(128, 128),
            pads(),
            world,
            () -> {
              settingsCalls[0]++;
              return AasMovementPredictor.Settings.defaults();
            },
            ignored -> {});
    assertEquals(2, resolver.bestArea(new Vec3(0, 0, 50), MIN, MAX).orElseThrow());
    assertEquals(1, world.launchCount);
    assertEquals(1, settingsCalls[0]);
    assertTrue(world.queries.size() > world.launchCount);
    assertTrue(world.queries.stream().allMatch(q -> q.entity() == -1));
  }

  @Test
  void firstEligiblePadWinsAndNonPadAreasAvoidTrajectoryWork() {
    var world = new World();
    var resolver =
        new JumpPadItemAreas(
            navigation(0, 0),
            pads(),
            world,
            AasMovementPredictor.Settings::defaults,
            ignored -> {});
    assertEquals(0, resolver.bestArea(new Vec3(0, 0, 50), MIN, MAX).orElseThrow());
    assertEquals(2, world.launchCount);
    assertEquals(0, world.presences);
    var onlyFirst =
        new JumpPadItemAreas(
            navigation(128, 0),
            pads(),
            new World(),
            AasMovementPredictor.Settings::defaults,
            ignored -> {});
    assertEquals(1, onlyFirst.bestArea(new Vec3(0, 0, 50), MIN, MAX).orElseThrow());
  }

  @Test
  void unreachableTrajectoryCompletesAndInvalidItemBoxIsRejectedBeforeWorldQueries() {
    var world = new World();
    var resolver =
        new JumpPadItemAreas(
            navigation(128, 128),
            pads(),
            world,
            AasMovementPredictor.Settings::defaults,
            ignored -> {});
    assertThrows(IllegalArgumentException.class, () -> resolver.bestArea(ZERO, MAX, MIN));
    assertTrue(world.queries.isEmpty());
    assertEquals(0, resolver.bestArea(new Vec3(10000, 0, 0), MIN, MAX).orElseThrow());
    assertEquals(2, world.launchCount);
    assertEquals(122, world.queries.size());
  }

  @Test
  void absoluteBrushBoundsAddZeroAndMissingTargetOriginUsesTheZeroVector() {
    var bsp =
        bsp(
            List.of(Map.of("model", "*1", "target", "t"), Map.of("targetname", "t")),
            new Vec3(-0.0, -0.0, -50),
            new Vec3(0, 0, -10));
    var launch = new JumpPadLaunch(bsp, new World(), ignored -> {}).resolve(0, 800).orElseThrow();
    assertEquals(0, Float.floatToRawIntBits((float) launch.minimum().x()));
    assertEquals(0, Float.floatToRawIntBits((float) launch.minimum().y()));
    assertEquals(219.089035f, (float) launch.velocity().z());
  }

  @Test
  void suspendedArmorIsPublishedWithItsOriginalPositionAndTheLaunchApproachArea() {
    var entities = new ArrayList<>(pads().entities());
    entities.add(Map.of("classname", "item_armor_body", "origin", "0 0 50", "spawnflags", "1"));
    var map = bsp(entities, MIN, MAX);
    var navigation = navigation(128, 128);
    var jump =
        new JumpPadItemAreas(
            navigation, map, new World(), AasMovementPredictor.Settings::defaults, ignored -> {});
    var collision =
        new TraceWorld() {
          public TraceResult trace(TraceRequest request) {
            return new TraceResult(1, request.end(), false, false, Optional.empty());
          }

          public int pointContents(Vec3 point, int mask, int ignored) {
            return 0;
          }
        };
    var item = new ItemInfo("item_armor_body", "Armor", "", 1, 0, 0, 30, MIN, MAX, 0);
    var placement = new ItemPlacement(navigation, collision, jump, ignored -> {});
    var registry =
        new ItemRegistry(new ItemConfig(List.of(item), List.of()), placement, ignored -> {});
    registry.initialize(map.entities());
    var goal = registry.nextGoal(-1, "Armor", 0).orElseThrow();
    assertEquals(new Vec3(0, 0, 50), goal.origin());
    assertEquals(2, goal.area());
    assertEquals(MIN, goal.mins());
    assertEquals(MAX, goal.maxs());
  }

  private static BspMap pads() {
    return bsp(
        List.of(
            Map.of("classname", "TRIGGER_PUSH", "model", "*1", "target", "t"),
            Map.of("classname", "trigger_push", "model", "*1", "target", "t"),
            Map.of("classname", "trigger_push", "model", "*1", "target", "t"),
            Map.of("targetname", "t", "origin", "0 0 100")),
        MIN,
        MAX);
  }

  private static BspMap bsp(List<Map<String, String>> entities, Vec3 low, Vec3 high) {
    var model = new BspMap.Model(new BspMap.Bounds(low, high), 0, 0, 0, 0);
    return new BspMap(
        entities,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(model, model),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new BspMap.Visibility(0, 0, new BspMap.Bytes(new byte[0])));
  }

  private static AasNavigation navigation(int leftContents, int rightContents) {
    var indices = new AasMap.Indices(new int[0]);
    return new AasNavigation(
        new AasMap(
            5,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new AasMap.Plane(new Vec3(1, 0, 0), 0, 0),
                new AasMap.Plane(new Vec3(-1, 0, 0), 0, 0)),
            List.of(),
            indices,
            List.of(),
            indices,
            List.of(
                new AasMap.Area(0, 0, 0, ZERO, ZERO, ZERO),
                new AasMap.Area(1, 0, 0, MIN, MAX, ZERO),
                new AasMap.Area(2, 0, 0, MIN, MAX, ZERO)),
            List.of(
                new AasMap.AreaSettings(0, 0, 0, 0, 0, 0, 0),
                new AasMap.AreaSettings(leftContents, 0, 0, 0, 0, 0, 0),
                new AasMap.AreaSettings(rightContents, 0, 0, 0, 0, 0, 0)),
            List.of(),
            List.of(new AasMap.Node(0, 0, 0), new AasMap.Node(0, -1, -2)),
            List.of(),
            indices,
            List.of()));
  }

  private record Query(Vec3 start, Vec3 end, int presence, int entity) {}

  private static final class World implements AasMovementPredictor.World {
    final List<Query> queries = new ArrayList<>();
    float launchFraction = 1;
    boolean launchSolid;
    int launchCount, presences;

    public AasPresenceTrace.Result trace(Vec3 start, Vec3 end, int presence, int entity) {
      queries.add(new Query(start, end, presence, entity));
      boolean launch = presence == 4 && start.z() - end.z() == 64;
      if (launch) launchCount++;
      float fraction = launch ? launchFraction : 1;
      var endpoint =
          new Vec3(
              (float) start.x() + fraction * ((float) end.x() - (float) start.x()),
              (float) start.y() + fraction * ((float) end.y() - (float) start.y()),
              (float) start.z() + fraction * ((float) end.z() - (float) start.z()));
      return new AasPresenceTrace.Result(launch && launchSolid, fraction, endpoint, 0, 0, 0, 0, 0);
    }

    public int contents(Vec3 point) {
      return 0;
    }

    public int presence(Vec3 point) {
      presences++;
      return 6;
    }

    public Vec3 planeNormal(int plane) {
      return new Vec3(0, 0, 1);
    }

    public int area(Vec3 point) {
      return 1;
    }
  }
}

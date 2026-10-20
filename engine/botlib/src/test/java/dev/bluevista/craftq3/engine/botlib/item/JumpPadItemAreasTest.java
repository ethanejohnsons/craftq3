package dev.bluevista.craftq3.engine.botlib.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.botlib.BotWorld;
import dev.bluevista.craftq3.engine.botlib.BspEntities;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.aas.AasPresenceTrace;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.engine.botlib.testing.TraceRequest;
import dev.bluevista.craftq3.engine.botlib.testing.TraceResult;
import dev.bluevista.craftq3.engine.botlib.testing.TraceWorld;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class JumpPadItemAreasTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);
  private static final Vec3f MIN = new Vec3f(-15, -15, -15), MAX = new Vec3f(15, 15, 15);

  @Test
  void launchUsesBrushCenterForVelocityAndCrouchTraceForOrigin() {
    var world = new World();
    world.launchFraction = .25f;
    var bsp =
        bsp(
            List.of(
                Map.of("model", "*1", "target", "t", "speed", "99999", "origin", "999 999 999"),
                Map.of("classname", "arbitrary", "targetname", "t", "origin", "110 220 430")),
            new Vec3f(-10, -20, 10),
            new Vec3f(30, 60, 50));
    var result = launch(bsp, world, ignored -> {}).resolve(0, 800).orElseThrow();
    assertEquals(new Vec3f(10, 20, 78.125f), result.origin());
    assertEquals(new Vec3f(110.000008f, 220.000015f, 800), result.velocity());
    assertEquals(new Vec3f(-10, -20, 10), result.minimum());
    assertEquals(new Vec3f(30, 60, 50), result.maximum());
    assertEquals(
        List.of(new Query(new Vec3f(10, 20, 94), new Vec3f(10, 20, 30), 4, -1)), world.queries);
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
    var result = launch(bsp, world, messages::add).resolve(0, 800).orElseThrow();
    assertEquals(new Vec3f(0, 0, .125f), result.origin());
    assertEquals(new Vec3f(0, 0, 400), result.velocity());
    assertEquals(1, messages.size());
    assertTrue(messages.getFirst().contains("starts solid"));
  }

  @Test
  void missingAndNonfiniteLaunchMetadataRemainExplicit() {
    var world = new World();
    for (String model : List.of("", "1", "*999", "*9999999999")) {
      var provider = launch(bsp(List.of(Map.of("model", model)), MIN, MAX), world, ignored -> {});
      assertTrue(provider.resolve(0, 800).isEmpty());
    }
    assertTrue(world.queries.isEmpty());
    for (String origin : List.of("0 0 0", "not a vector", "NaN 0 100")) {
      var provider =
          launch(
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
        launch(
            bsp(
                List.of(
                    Map.of("model", "*1", "target", "t"),
                    Map.of("targetname", "t", "origin", "0 0 -100")),
                MIN,
                MAX),
            world,
            ignored -> {});
    // A target below the pad cannot launch anything; the original arithmetic is not finite.
    assertTrue(below.resolve(0, 800).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> below.resolve(-1, 800));
    assertThrows(IllegalArgumentException.class, () -> below.resolve(0, Float.NaN));
    var missing =
        launch(
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
        areas(
            navigation(128, 128),
            pads(),
            world,
            () -> {
              settingsCalls[0]++;
              return AasMovementPredictor.Settings.defaults();
            },
            ignored -> {});
    assertEquals(2, resolver.bestArea(new Vec3f(0, 0, 50), MIN, MAX).orElseThrow());
    assertEquals(1, world.launchCount);
    assertEquals(1, settingsCalls[0]);
    assertTrue(world.queries.size() > world.launchCount);
    assertTrue(world.queries.stream().allMatch(q -> q.entity() == -1));
  }

  @Test
  void firstEligiblePadWinsAndNonPadAreasAvoidTrajectoryWork() {
    var world = new World();
    var resolver =
        areas(
            navigation(0, 0),
            pads(),
            world,
            AasMovementPredictor.Settings::defaults,
            ignored -> {});
    assertEquals(0, resolver.bestArea(new Vec3f(0, 0, 50), MIN, MAX).orElseThrow());
    assertEquals(2, world.launchCount);
    assertEquals(0, world.presences);
    var onlyFirst =
        areas(
            navigation(128, 0),
            pads(),
            new World(),
            AasMovementPredictor.Settings::defaults,
            ignored -> {});
    assertEquals(1, onlyFirst.bestArea(new Vec3f(0, 0, 50), MIN, MAX).orElseThrow());
  }

  @Test
  void unreachableTrajectoryCompletesAndInvalidItemBoxIsRejectedBeforeWorldQueries() {
    var world = new World();
    var resolver =
        areas(
            navigation(128, 128),
            pads(),
            world,
            AasMovementPredictor.Settings::defaults,
            ignored -> {});
    assertThrows(IllegalArgumentException.class, () -> resolver.bestArea(ZERO, MAX, MIN));
    assertTrue(world.queries.isEmpty());
    assertEquals(0, resolver.bestArea(new Vec3f(10000, 0, 0), MIN, MAX).orElseThrow());
    assertEquals(2, world.launchCount);
    assertEquals(122, world.queries.size());
  }

  @Test
  void absoluteBrushBoundsAddZeroAndMissingTargetOriginUsesTheZeroVector() {
    var bsp =
        bsp(
            List.of(Map.of("model", "*1", "target", "t"), Map.of("targetname", "t")),
            new Vec3f(-0.0f, -0.0f, -50),
            new Vec3f(0, 0, -10));
    var launch = launch(bsp, new World(), ignored -> {}).resolve(0, 800).orElseThrow();
    assertEquals(0, Float.floatToRawIntBits(launch.minimum().x()));
    assertEquals(0, Float.floatToRawIntBits(launch.minimum().y()));
    assertEquals(219.089035f, launch.velocity().z());
  }

  @Test
  void suspendedArmorIsPublishedWithItsOriginalPositionAndTheLaunchApproachArea() {
    var entities = new ArrayList<>(pads().entities().list());
    entities.add(Map.of("classname", "item_armor_body", "origin", "0 0 50", "spawnflags", "1"));
    var map = bsp(entities, MIN, MAX);
    var navigation = navigation(128, 128);
    var jump =
        areas(navigation, map, new World(), AasMovementPredictor.Settings::defaults, ignored -> {});
    var collision =
        new TraceWorld() {
          public TraceResult trace(TraceRequest request) {
            return new TraceResult(1, request.end(), false, false, Optional.empty());
          }

          public int pointContents(Vec3f point, int mask, int ignored) {
            return 0;
          }
        };
    var item = new ItemInfo("item_armor_body", "Armor", "", 1, 0, 0, 30, MIN, MAX, 0);
    var placement = new ItemPlacement(navigation, collision, jump, ignored -> {});
    var registry =
        new ItemRegistry(new ItemConfig(List.of(item), List.of()), placement, ignored -> {});
    registry.initialize(map.entities().list());
    var goal = registry.nextGoal(-1, "Armor", 0).orElseThrow();
    assertEquals(new Vec3f(0, 0, 50), goal.origin());
    assertEquals(2, goal.area());
    assertEquals(MIN, goal.mins());
    assertEquals(MAX, goal.maxs());
  }

  private record Bsp(BspEntities entities, IntFunction<Optional<BotWorld.ModelBounds>> models) {}

  private static JumpPadLaunch launch(
      Bsp bsp, AasMovementPredictor.World world, Consumer<String> diagnostics) {
    return new JumpPadLaunch(bsp.entities(), bsp.models(), world, diagnostics);
  }

  private static JumpPadItemAreas areas(
      AasNavigation navigation,
      Bsp bsp,
      AasMovementPredictor.World world,
      Supplier<AasMovementPredictor.Settings> settings,
      Consumer<String> diagnostics) {
    return new JumpPadItemAreas(
        navigation, bsp.entities(), bsp.models(), world, settings, diagnostics);
  }

  private static Bsp pads() {
    return bsp(
        List.of(
            Map.of("classname", "TRIGGER_PUSH", "model", "*1", "target", "t"),
            Map.of("classname", "trigger_push", "model", "*1", "target", "t"),
            Map.of("classname", "trigger_push", "model", "*1", "target", "t"),
            Map.of("targetname", "t", "origin", "0 0 100")),
        MIN,
        MAX);
  }

  /** Inline models 0 and 1 share the bounds; any other model is unknown. */
  private static Bsp bsp(List<Map<String, String>> entities, Vec3f low, Vec3f high) {
    var bounds = new BotWorld.ModelBounds(low, high, ZERO);
    return new Bsp(
        new BspEntities(entities),
        model -> model >= 0 && model < 2 ? Optional.of(bounds) : Optional.empty());
  }

  private static AasNavigation navigation(int leftContents, int rightContents) {
    var indices = new AasWorld.Indices(new int[0]);
    return new AasNavigation(
        new AasWorld(
            5,
            0,
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new AasWorld.Plane(new Vec3f(1, 0, 0), 0, 0),
                new AasWorld.Plane(new Vec3f(-1, 0, 0), 0, 0)),
            List.of(),
            indices,
            List.of(),
            indices,
            List.of(
                new AasWorld.Area(0, 0, 0, ZERO, ZERO, ZERO),
                new AasWorld.Area(1, 0, 0, MIN, MAX, ZERO),
                new AasWorld.Area(2, 0, 0, MIN, MAX, ZERO)),
            List.of(
                new AasWorld.AreaSettings(0, 0, 0, 0, 0, 0, 0),
                new AasWorld.AreaSettings(leftContents, 0, 0, 0, 0, 0, 0),
                new AasWorld.AreaSettings(rightContents, 0, 0, 0, 0, 0, 0)),
            List.of(),
            List.of(new AasWorld.Node(0, 0, 0), new AasWorld.Node(0, -1, -2)),
            List.of(),
            indices,
            List.of()));
  }

  private record Query(Vec3f start, Vec3f end, int presence, int entity) {}

  private static final class World implements AasMovementPredictor.World {
    final List<Query> queries = new ArrayList<>();
    float launchFraction = 1;
    boolean launchSolid;
    int launchCount, presences;

    public AasPresenceTrace.Result trace(Vec3f start, Vec3f end, int presence, int entity) {
      queries.add(new Query(start, end, presence, entity));
      boolean launch = presence == 4 && start.z() - end.z() == 64;
      if (launch) launchCount++;
      float fraction = launch ? launchFraction : 1;
      var endpoint =
          new Vec3f(
              start.x() + fraction * (end.x() - start.x()),
              start.y() + fraction * (end.y() - start.y()),
              start.z() + fraction * (end.z() - start.z()));
      return new AasPresenceTrace.Result(launch && launchSolid, fraction, endpoint, 0, 0, 0, 0, 0);
    }

    public int contents(Vec3f point) {
      return 0;
    }

    public int presence(Vec3f point) {
      presences++;
      return 6;
    }

    public Vec3f planeNormal(int plane) {
      return new Vec3f(0, 0, 1);
    }

    public int area(Vec3f point) {
      return 1;
    }
  }
}

package dev.bluevista.craftq3.botlib.goal;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.item.ItemInfo;
import dev.bluevista.craftq3.botlib.item.ItemRegistry;
import dev.bluevista.craftq3.botlib.item.ItemRegistry.LevelItem;
import dev.bluevista.craftq3.botlib.weight.WeightConfig;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BotItemSelectorTest {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  @Test
  void longTermRanksByWeightOverTimeAndRetainsFirstTie() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.items = List.of(item(0, 0, 0, 0, 40), item(1, 0, 0, 0, 41));
      fixture.cost[100][1] = 100;
      fixture.cost[100][2] = 200;
      fixture.weights[0] = 100;
      fixture.weights[1] = 200;
      assertTrue(fixture.choose());
      assertEquals(1, fixture.top().number());
      fixture.goals.reset(fixture.handle);
      fixture.weights[1] = 201;
      assertTrue(fixture.choose());
      assertEquals(2, fixture.top().number());
      assertEquals(30, fixture.goals.avoidTime(fixture.handle, 2));
    }
  }

  @Test
  void droppedBoostPrecedesRoamMultiplierAndSetsTenSecondAvoidance() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.items = List.of(item(0, ItemRegistry.ROAM, 3, 35, 40), item(1, 0, 0, 0, 41));
      fixture.cost[100][1] = 100;
      fixture.cost[100][2] = 100;
      fixture.weights[0] = -100;
      fixture.weights[1] = 2000;
      assertTrue(fixture.choose());
      assertEquals(1, fixture.top().number());
      assertEquals(Goal.ITEM | Goal.ROAM | Goal.DROPPED, fixture.top().flags());
      assertEquals(10, fixture.goals.avoidTime(fixture.handle, 1));
    }
  }

  @Test
  void nearbyUsesStrictTravelUnitsAndChecksOnlyUndroppedOnwardRoute() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.items = List.of(item(0, 0, 0, 0, 40));
      fixture.cost[100][1] = 100;
      fixture.cost[100][5] = 500;
      fixture.cost[1][5] = 500;
      Goal target = new Goal(ZERO, 5, ZERO, ZERO, 0, 0, 0, 0);
      assertFalse(fixture.nearby(target, 100));
      assertTrue(fixture.nearby(target, 100.1f));
      fixture.goals.reset(fixture.handle);
      fixture.cost[1][5] = 501;
      assertFalse(fixture.nearby(target, 1000));
      fixture.items = List.of(item(0, 0, 0, 35, 40));
      assertTrue(fixture.nearby(target, 1000));
      fixture.goals.reset(fixture.handle);
      fixture.items = List.of(item(0, 0, 0, 0, 40));
      assertTrue(fixture.nearby(null, 1000));
    }
  }

  @Test
  void avoidCutoffPreservesNativeDoubleComparisonAgainstFloatClock() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.items = List.of(item(0, 0, 0, 0, 40));
      fixture.cost[100][1] = 489;
      fixture.goals.setAvoidTime(fixture.handle, 1, 4.401f);
      assertFalse(fixture.choose()); // Float4.401 exceeds double489*.009.
      fixture.goals.reset(fixture.handle);
      fixture.cost[100][1] = 1000;
      fixture.goals.setAvoidTime(fixture.handle, 1, 9);
      assertTrue(fixture.choose());
    }
  }

  @Test
  void resetRetainsLastReachableAreaButFreeReleasesIt() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.items = List.of(item(0, 0, 0, 0, 40));
      fixture.cost[100][1] = 100;
      fixture.area = 0;
      assertFalse(fixture.choose());
      fixture.area = 100;
      assertTrue(fixture.choose());
      fixture.area = 0;
      fixture.goals.reset(fixture.handle);
      assertTrue(fixture.choose());
      fixture.goals.free(fixture.handle);
      assertEquals(fixture.handle, fixture.goals.allocate(13));
      fixture.goals.weights(fixture.handle, fixture.config);
      assertFalse(fixture.choose());
    }
  }

  @Test
  void unlinkedItemsAndGameExclusionsAreSkippedWhileRoamRemainsAvailable() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.cost[100][1] = 100;
      fixture.items = List.of(item(0, 0, 0, 0, 0));
      assertFalse(fixture.choose());
      fixture.items = List.of(item(0, ItemRegistry.ROAM, 1, 0, 0));
      assertTrue(fixture.choose());
      fixture.goals.reset(fixture.handle);
      fixture.items = List.of(item(0, ItemRegistry.NOT_FREE, 0, 0, 40));
      assertFalse(fixture.choose());
      fixture.game = 3;
      assertTrue(fixture.choose());
      fixture.goals.reset(fixture.handle);
      fixture.items = List.of(item(0, ItemRegistry.NOT_BOT, 0, 0, 40));
      assertFalse(fixture.choose());
    }
  }

  @Test
  void absentWeightsShortCircuitAndFullStackStillReportsSuccessfulChoice() throws Exception {
    try (var fixture = new Fixture()) {
      fixture.items = List.of(item(0, 0, 0, 0, 40));
      fixture.cost[100][1] = 100;
      fixture.goals.freeWeights(fixture.handle);
      assertFalse(fixture.choose());
      assertEquals(0, fixture.queries);
      fixture.goals.weights(fixture.handle, fixture.config);
      for (int i = 0; i < 7; i++)
        fixture.goals.push(fixture.handle, new Goal(ZERO, 9, ZERO, ZERO, 0, 99, 0, 0));
      assertTrue(fixture.choose());
      assertEquals(99, fixture.top().number());
      assertEquals(30, fixture.goals.avoidTime(fixture.handle, 1));
    }
  }

  private static LevelItem item(int index, int flags, float roam, float timeout, int entity) {
    var origin = new Vec3(100 * (index + 1), 0, 0);
    var info =
        new ItemInfo(
            index == 0 ? "alpha" : "beta",
            "Item",
            "",
            index + 1,
            0,
            0,
            30,
            new Vec3(-15, -15, -15),
            new Vec3(15, 15, 15),
            index);
    return new LevelItem(
        index + 1,
        index + 1,
        info,
        flags,
        roam,
        new ItemRegistry.Placement(origin, origin, index + 1),
        entity,
        timeout);
  }

  private static final class Fixture implements AutoCloseable, BotItemSelector.Routing {
    final BotGoals goals = new BotGoals(() -> 5, s -> {});
    final int handle = goals.allocate(13);
    final WeightConfig config;
    final BotItemSelector selector;
    List<LevelItem> items = List.of();
    int area = 100, game, queries;
    final int[][] cost = new int[101][101];
    final float[] weights = {100, 100};

    Fixture() throws Exception {
      config =
          WeightConfig.load(
              new VirtualFileSystem() {
                public Optional<Origin> which(VirtualPath path) {
                  return Optional.of(new Origin("test", "authored", false));
                }

                public List<VirtualPath> list(String directory) {
                  return List.of();
                }

                public List<Origin> searchOrder() {
                  return List.of();
                }

                public byte[] read(VirtualPath path) {
                  return "weight \"alpha\" {return 0;} weight \"beta\" {return 0;}"
                      .getBytes(StandardCharsets.ISO_8859_1);
                }

                public void close() {}
              },
              "weights.c");
      goals.weights(handle, config);
      selector =
          new BotItemSelector(
              goals, () -> items, this, (c, i, inventory) -> weights[i], () -> game, () -> 1000);
    }

    boolean choose() {
      return selector.chooseLongTerm(handle, ZERO, new int[256], 123);
    }

    boolean nearby(Goal goal, float max) {
      return selector.chooseNearby(handle, ZERO, new int[256], 123, goal, max);
    }

    Goal top() {
      return goals.top(handle).orElseThrow();
    }

    public int reachableArea(Vec3 origin, int client) {
      queries++;
      return area;
    }

    public boolean hasReachability(int number) {
      return true;
    }

    public int travelTime(int start, Vec3 origin, int goal, int flags) {
      return cost[start][goal];
    }

    public void close() {
      goals.close();
    }
  }
}

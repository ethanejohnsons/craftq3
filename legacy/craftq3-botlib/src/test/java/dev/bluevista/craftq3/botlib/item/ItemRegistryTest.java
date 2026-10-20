package dev.bluevista.craftq3.botlib.item;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ItemRegistryTest {
  private static final ItemInfo INFO =
      new ItemInfo(
          "test_item",
          "Test Item",
          "",
          5,
          0,
          0,
          30,
          new Vec3(-15, -15, -15),
          new Vec3(15, 15, 15),
          0);

  @Test
  void configuredRoamItemsRetainOriginalMapWeightAndFlags() {
    var metadata =
        new ItemInfo("item_botroam", "Roam", "", 1, 8, 0, 20, INFO.mins(), INFO.maxs(), 0);
    var registry =
        new ItemRegistry(
            new ItemConfig(List.of(metadata), List.of()),
            (info, origin, suspended) -> Optional.of(new ItemRegistry.Placement(origin, origin, 7)),
            s -> {});
    registry.initialize(
        List.of(
            Map.of(
                "classname",
                "item_botroam",
                "origin",
                "0 0 100",
                "notfree",
                "1",
                "weight",
                "2.5suffix")));
    var item = registry.items().getFirst();
    assertEquals(ItemRegistry.ROAM | ItemRegistry.NOT_FREE, item.flags());
    assertEquals(2.5f, item.weight());
    assertEquals(3, item.goal().flags());
  }

  @Test
  void discoveredOrderNumbersFlagsAndDisplayNameQueriesMatchNative() {
    var registry = registry();
    registry.initialize(
        List.of(
            Map.of("classname", "worldspawn"),
            item(""),
            item("notfree"),
            item("notteam"),
            item("notsingle"),
            item("notbot")));
    assertEquals(
        List.of(5, 4, 3, 2, 1),
        registry.items().stream().map(ItemRegistry.LevelItem::number).toList());
    assertEquals(
        List.of(8, 4, 2, 1, 0),
        registry.items().stream().map(ItemRegistry.LevelItem::flags).toList());
    assertEquals(List.of(4, 3, 1), goals(registry, 0));
    assertEquals(List.of(3, 2, 1), goals(registry, 2));
    assertEquals(List.of(4, 2, 1), goals(registry, 3));
    assertEquals(List.of(4, 2, 1), goals(registry, 4));
    assertTrue(registry.nextGoal(0, "Test Item", 0).isEmpty());
    assertTrue(registry.nextGoal(-1, "test_item", 0).isEmpty());
    var goal = registry.nextGoal(-1, "TEST ITEM", 0).orElseThrow();
    assertEquals(new Vec3(1, 2, 61.5), goal.origin());
    assertEquals(7, goal.area());
    assertEquals(0, goal.entity());
    assertEquals(0, goal.itemInfo());
  }

  @Test
  void rejectedSuspendedItemsDoNotConsumeNumbersAndBadOriginsAreDiagnosed() {
    var diagnostics = new ArrayList<String>();
    var registry =
        new ItemRegistry(
            new ItemConfig(List.of(INFO), List.of()),
            (item, origin, suspended) ->
                suspended
                    ? Optional.empty()
                    : Optional.of(new ItemRegistry.Placement(origin, origin, 0)),
            diagnostics::add);
    registry.initialize(
        List.of(
            item(""),
            item("spawnflags"),
            Map.of("classname", "test_item"),
            Map.of("classname", "test_item", "origin", "NaN 0 0"),
            item("")));
    assertEquals(
        List.of(2, 1), registry.items().stream().map(ItemRegistry.LevelItem::number).toList());
    assertEquals(2, diagnostics.size());
    assertEquals(0, registry.items().getFirst().goal().area());
  }

  @Test
  void mapReplacementIsBoundedAndTransactional() {
    var registry = registry();
    registry.initialize(List.of(item("")));
    var tooMany = new ArrayList<Map<String, String>>();
    for (int i = 0; i < 257; i++) tooMany.add(item(""));
    assertThrows(IllegalArgumentException.class, () -> registry.initialize(tooMany));
    assertEquals(1, registry.items().size());
    assertThrows(UnsupportedOperationException.class, () -> registry.items().clear());
    registry.initialize(List.of());
    assertTrue(registry.items().isEmpty());
  }

  @Test
  void liveAssociationRadiusLifetimeAndEntityModelReplacementMatchNative() {
    var registry = registry();
    registry.initialize(List.of(item("")));
    ItemRegistry.LivePlacementResolver live =
        (info, origin) ->
            new ItemRegistry.Placement(
                origin, new Vec3(origin.x(), origin.y(), origin.z() + .5), 7);
    registry.update(5, List.of(entity(50, 5, 30, 2, 61)), live); // 29 units from map placement.
    assertEquals(1, registry.items().size());
    assertEquals(50, registry.items().getFirst().entity());
    registry.update(6, List.of(entity(50, 5, 100, 2, 61)), live);
    assertEquals(1, registry.items().getFirst().number());
    assertEquals(100, registry.items().getFirst().placement().origin().x());
    registry.update(7, List.of(), live);
    assertEquals(50, registry.items().getFirst().entity());
    registry.initialize(List.of(item("")));
    registry.update(
        5, List.of(entity(50, 5, 31, 2, 61)), live); // Exactly30 creates a separate dropped item.
    assertEquals(
        List.of(51, 1), registry.items().stream().map(ItemRegistry.LevelItem::number).toList());
    assertEquals(35, registry.items().getFirst().timeout());
    assertEquals(30, registry.automaticAvoidDuration(51).orElseThrow());
    registry.update(6, List.of(entity(50, 5, 32, 2, 61)), live);
    assertEquals(35, registry.items().getFirst().timeout());
    registry.update(35, List.of(), live);
    assertEquals(2, registry.items().size());
    registry.update(36, List.of(), live);
    assertEquals(1, registry.items().size());
    registry.update(37, List.of(entity(50, 5, 1, 2, 61)), live);
    registry.update(38, List.of(entity(50, 999, 1, 2, 61)), live);
    assertTrue(registry.items().isEmpty());
  }

  @Test
  void existingEntityLinkTakesPriorityOverAnotherNearbyMapItem() {
    var registry = registry();
    registry.initialize(List.of(item(""), Map.of("classname", "test_item", "origin", "100 2 64")));
    ItemRegistry.LivePlacementResolver live =
        (info, origin) -> new ItemRegistry.Placement(origin, origin, 7);
    registry.update(5, List.of(entity(50, 5, 1, 2, 61)), live);
    registry.update(6, List.of(entity(50, 5, 100, 2, 61)), live);
    assertEquals(0, registry.byNumber(2).orElseThrow().entity());
    assertEquals(50, registry.byNumber(1).orElseThrow().entity());
    assertEquals(100, registry.byNumber(1).orElseThrow().placement().origin().x());
  }

  @Test
  void liveUpdatesSkipMovingAndNonItemEntitiesButDoNotDiscardHiddenItems() {
    var registry = registry();
    registry.initialize(List.of(item("")));
    ItemRegistry.LivePlacementResolver live =
        (info, origin) -> new ItemRegistry.Placement(origin, origin, 7);
    var origin = new Vec3(1, 2, 61);
    registry.update(
        5, List.of(new ItemRegistry.WorldEntity(50, 2, 0, 5, origin, new Vec3(2, 2, 61))), live);
    assertEquals(0, registry.items().getFirst().entity());
    registry.update(6, List.of(new ItemRegistry.WorldEntity(50, 1, 0, 5, origin, origin)), live);
    assertEquals(0, registry.items().getFirst().entity());
    registry.update(7, List.of(new ItemRegistry.WorldEntity(50, 2, 128, 5, origin, origin)), live);
    assertEquals(50, registry.items().getFirst().entity());
    assertThrows(
        IllegalArgumentException.class,
        () -> registry.update(8, List.of(entity(50, 5, 1, 2, 61), entity(50, 5, 1, 2, 61)), live));
    assertEquals(50, registry.items().getFirst().entity());
  }

  @Test
  void automaticAvoidTimeUsesObservedZeroDefaultAndMinimum() {
    for (float time : new float[] {-2, 0, .1f, 5, 10, 30, 60}) {
      var metadata =
          new ItemInfo(
              INFO.classname(),
              INFO.name(),
              INFO.model(),
              5,
              0,
              0,
              time,
              INFO.mins(),
              INFO.maxs(),
              0);
      var registry =
          new ItemRegistry(
              new ItemConfig(List.of(metadata), List.of()),
              (item, origin, suspended) ->
                  Optional.of(new ItemRegistry.Placement(origin, origin, 1)),
              s -> {});
      registry.initialize(List.of(item("")));
      assertEquals(
          time == 0 ? 30 : Math.max(10, time), registry.automaticAvoidDuration(1).orElseThrow());
      assertTrue(registry.automaticAvoidDuration(999).isEmpty());
    }
  }

  private static ItemRegistry.WorldEntity entity(int number, int model, float x, float y, float z) {
    var origin = new Vec3(x, y, z);
    return new ItemRegistry.WorldEntity(number, 2, 0, model, origin, origin);
  }

  private static Map<String, String> item(String flag) {
    return flag.isEmpty()
        ? Map.of("classname", "test_item", "origin", "1 2 64")
        : Map.of("classname", "test_item", "origin", "1 2 64", flag, "1");
  }

  private static ItemRegistry registry() {
    return new ItemRegistry(
        new ItemConfig(List.of(INFO), List.of()),
        (item, origin, suspended) ->
            Optional.of(
                new ItemRegistry.Placement(
                    new Vec3(origin.x(), origin.y(), origin.z() - 3),
                    new Vec3(origin.x(), origin.y(), origin.z() - 2.5),
                    7)),
        s -> {});
  }

  private static List<Integer> goals(ItemRegistry registry, int type) {
    var result = new ArrayList<Integer>();
    int cursor = -1;
    while (true) {
      var goal = registry.nextGoal(cursor, "Test Item", type);
      if (goal.isEmpty()) return result;
      cursor = goal.orElseThrow().number();
      result.add(cursor);
    }
  }
}

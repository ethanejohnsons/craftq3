package dev.bluevista.craftq3.botlib.goal;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BotMapGoalsTest {
  @Test
  void campsReverseMapOrderAndReturnNextOrdinalWithOwnedZeroedMetadata() {
    var goals = new BotMapGoals(p -> 2, s -> {});
    goals.initialize(
        List.of(
            entity("info_camp", "1 2 3", ""),
            entity("info_camp", "4 5 6", ""),
            Map.of("classname", "info_camp")));
    assertEquals(new Vec3(0, 0, 0), goals.nextCamp(-5).orElseThrow().goal().origin());
    assertEquals(1, goals.nextCamp(0).orElseThrow().nextCursor());
    var second = goals.nextCamp(1).orElseThrow();
    assertEquals(2, second.nextCursor());
    assertEquals(
        new Goal(new Vec3(4, 5, 6), 2, new Vec3(-8, -8, -8), new Vec3(8, 8, 8), 0, 0, 0, 0),
        second.goal());
    assertTrue(goals.nextCamp(3).isEmpty());
    assertTrue(goals.nextCamp(Integer.MAX_VALUE).isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> goals.camps().clear());
  }

  @Test
  void locationsMatchLastCaseInsensitiveNameAndRetainSolidOrigins() {
    var goals = new BotMapGoals(p -> 0, s -> {});
    goals.initialize(
        List.of(
            entity("target_location", "1 2 3", "Base"),
            entity("target_location", "4 5 6", "Base"),
            entity("target_location", "7 8 9", "x".repeat(300)),
            entity("target_location", "1 2 invalid", "")));
    assertEquals(new Vec3(4, 5, 6), goals.location("BASE").orElseThrow().origin());
    assertEquals(0, goals.location("base").orElseThrow().area());
    assertTrue(goals.location("x".repeat(127)).isPresent());
    assertTrue(goals.location("x".repeat(300)).isEmpty());
    assertEquals(new Vec3(1, 2, 0), goals.location("").orElseThrow().origin());
    assertTrue(goals.location("missing").isEmpty());
  }

  @Test
  void solidCampsAreDiagnosedAndReplacementIsTransactional() {
    var messages = new ArrayList<String>();
    var goals = new BotMapGoals(p -> p.x() > 0 ? 2 : 0, messages::add);
    goals.initialize(List.of(entity("info_camp", "0 0 0", ""), entity("info_camp", "1 0 0", "")));
    assertEquals(1, goals.camps().size());
    assertEquals(1, messages.size());
    var tooMany = new ArrayList<Map<String, String>>();
    for (int i = 0; i <= BotMapGoals.MAX_GOALS; i++) tooMany.add(entity("info_camp", "1 0 0", ""));
    assertThrows(IllegalArgumentException.class, () -> goals.initialize(tooMany));
    assertEquals(1, goals.camps().size());
  }

  private static Map<String, String> entity(String classname, String origin, String message) {
    return Map.of("classname", classname, "origin", origin, "message", message);
  }
}

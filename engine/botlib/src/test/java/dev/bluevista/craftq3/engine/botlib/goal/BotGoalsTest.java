package dev.bluevista.craftq3.engine.botlib.goal;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

final class BotGoalsTest {
  @Test
  void allocates64HandlesReusingFreedSlotsWithNoRetainedState() {
    var diagnostics = new ArrayList<String>();
    try (var goals = new BotGoals(() -> 1, diagnostics::add)) {
      for (int handle = 1; handle <= 64; handle++)
        assertEquals(handle, goals.allocate(100 + handle));
      assertEquals(0, goals.allocate(1));
      goals.push(10, goal(99));
      goals.setAvoidTime(10, 99, 10);
      goals.free(10);
      assertEquals(10, goals.allocate(999));
      assertTrue(goals.top(10).isEmpty());
      assertEquals(0, goals.avoidTime(10, 99));
      assertEquals(999, goals.snapshot(10).orElseThrow().client());
      goals.free(1000);
      assertTrue(goals.top(-1).isEmpty());
      assertEquals(64, goals.allocatedCount());
      assertFalse(diagnostics.isEmpty());
    }
  }

  @Test
  void effectiveNativeStackHasSevenSlotsAndOverflowDoesNotDiscardExistingGoals() {
    var diagnostics = new ArrayList<String>();
    try (var goals = new BotGoals(() -> 1, diagnostics::add)) {
      int handle = goals.allocate(0);
      assertTrue(goals.top(handle).isEmpty());
      assertTrue(goals.second(handle).isEmpty());
      for (int number = 1; number <= 7; number++) assertTrue(goals.push(handle, goal(number)));
      assertFalse(goals.push(handle, goal(8)));
      assertEquals(7, goals.top(handle).orElseThrow().number());
      assertEquals(6, goals.second(handle).orElseThrow().number());
      var snapshot = goals.snapshot(handle).orElseThrow();
      goals.pop(handle);
      assertEquals(6, goals.top(handle).orElseThrow().number());
      assertEquals(7, snapshot.stack().size());
      assertThrows(UnsupportedOperationException.class, () -> snapshot.stack().clear());
      goals.empty(handle);
      goals.pop(handle);
      assertTrue(goals.top(handle).isEmpty());
      assertEquals(1, diagnostics.size());
    }
  }

  @Test
  void avoidTimersRetainExpiredEntriesAndZeroTimeMatchesNativeSlotAvailability() {
    float[] now = {0};
    try (var goals = new BotGoals(() -> now[0], ignored -> {})) {
      int handle = goals.allocate(0);
      goals.setAvoidTime(handle, 1, 10);
      assertEquals(0, goals.avoidTime(handle, 1));
      now[0] = 1;
      goals.setAvoidTime(handle, 1, 10);
      assertEquals(10, goals.avoidTime(handle, 1));
      now[0] = 5;
      assertEquals(6, goals.avoidTime(handle, 1));
      goals.setAvoidTime(handle, 1, 20);
      assertEquals(20, goals.avoidTime(handle, 1));
      now[0] = 26;
      assertEquals(0, goals.avoidTime(handle, 1));
      now[0] = 20;
      assertEquals(5, goals.avoidTime(handle, 1));
      goals.removeAvoid(handle, 1);
      assertEquals(0, goals.avoidTime(handle, 1));
      goals.setAvoidTime(handle, 2, 10);
      goals.resetAvoid(handle);
      assertEquals(0, goals.avoidTime(handle, 2));
    }
  }

  @Test
  void avoidanceTableCapsAt256AndRefreshesMatchingEntriesBeforeReusingExpiredSlots() {
    float[] now = {1};
    try (var goals = new BotGoals(() -> now[0], ignored -> {})) {
      int handle = goals.allocate(0);
      for (int number = 1; number <= 256; number++) goals.setAvoidTime(handle, number, 10);
      goals.setAvoidTime(handle, 257, 10);
      assertEquals(0, goals.avoidTime(handle, 257));
      goals.removeAvoid(handle, 1);
      goals.setAvoidTime(handle, 200, 20);
      assertEquals(20, goals.avoidTime(handle, 200));
      goals.setAvoidTime(handle, 257, 10);
      assertEquals(10, goals.avoidTime(handle, 257));
      assertEquals(256, goals.snapshot(handle).orElseThrow().avoidGoals().size());
      now[0] = 11;
      goals.setAvoidTime(handle, 300, 10);
      assertEquals(0, goals.avoidTime(handle, 300));
      now[0] = 11.25f;
      goals.setAvoidTime(handle, 300, 10);
      assertEquals(10, goals.avoidTime(handle, 300));
    }
  }

  @Test
  void resetClearsBothStacksAndAvoidanceWhileResolversSupplyAutomaticDurations() {
    try (var goals =
        new BotGoals(
            () -> 3,
            number -> number == 7 ? OptionalDouble.of(12) : OptionalDouble.empty(),
            ignored -> {})) {
      int handle = goals.allocate(0);
      goals.push(handle, goal(7));
      goals.setAvoidTime(handle, 7, -1);
      assertEquals(12, goals.avoidTime(handle, 7));
      goals.setAvoidTime(handle, 7, -50);
      assertEquals(12, goals.avoidTime(handle, 7));
      goals.setAvoidTime(handle, 8, -1);
      assertEquals(0, goals.avoidTime(handle, 8));
      goals.reset(handle);
      assertTrue(goals.top(handle).isEmpty());
      assertEquals(0, goals.avoidTime(handle, 7));
      assertThrows(IllegalArgumentException.class, () -> goals.setAvoidTime(handle, 7, Float.NaN));
    }
    var closed = new BotGoals(() -> 0, ignored -> {});
    closed.close();
    assertThrows(IllegalStateException.class, () -> closed.allocate(0));
    try (var badClock = new BotGoals(() -> Double.NaN, ignored -> {})) {
      int handle = badClock.allocate(0);
      assertThrows(IllegalArgumentException.class, () -> badClock.avoidTime(handle, 1));
    }
  }

  @Test
  void goalResetRetainsImmutableWeightsUntilExplicitFree(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
    var directory = java.nio.file.Files.createDirectories(temp.resolve("baseq3/botfiles"));
    java.nio.file.Files.writeString(
        directory.resolve("weights.c"), "weight \"test\" { return 3; }");
    try (var fs = dev.bluevista.craftq3.engine.fs.SearchPath.mount(temp, "baseq3");
        var goals = new BotGoals(() -> 1, ignored -> {})) {
      var weights =
          dev.bluevista.craftq3.engine.botlib.weight.WeightConfig.load(
              new dev.bluevista.craftq3.engine.formats.FileViewAssets(fs.view()), "weights.c");
      int handle = goals.allocate(0);
      goals.weights(handle, weights);
      goals.push(handle, goal(1));
      goals.reset(handle);
      assertSame(weights, goals.weights(handle).orElseThrow());
      assertTrue(goals.top(handle).isEmpty());
      goals.freeWeights(handle);
      assertTrue(goals.weights(handle).isEmpty());
      goals.weights(handle, weights);
      goals.free(handle);
      int reused = goals.allocate(1);
      assertEquals(handle, reused);
      assertTrue(goals.weights(reused).isEmpty());
    }
  }

  private static Goal goal(int number) {
    return new Goal(
        new Vec3f(number, 0, 0), 1, new Vec3f(-1, -1, -1), new Vec3f(1, 1, 1), -1, number, 0, 0);
  }
}

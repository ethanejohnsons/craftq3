package dev.bluevista.craftq3.botlib.weight;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.script.ScriptException;
import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WeightConfigTest {
  @Test
  void interpolatesBetweenCasesAndChangesToDefaultAtTheFinalThreshold() throws Exception {
    var config =
        load(
            "weight \"ramp\" { switch(0) { case 10: return 100; case 20: return 200; default: return 0; } }");
    // Results independently observed through unchanged native FuzzyWeight on this authored fixture.
    int[] inventory = new int[1];
    int[] inputs = {-20, 0, 5, 10, 15, 19, 20, 500000, 999999, 1000000};
    float[] expected = {100, 100, 100, 100, 150, 190, 0, 0, 0, 0};
    for (int i = 0; i < inputs.length; i++) {
      inventory[0] = inputs[i];
      assertEquals(expected[i], config.evaluate(0, inventory), "input " + inputs[i]);
    }
  }

  @Test
  void nestedBranchWeightsAreInterpolatedAndBalanceMetadataRemainsImmutable() throws Exception {
    var config =
        load(
            """
        weight "nested" { switch(0) {
          case 10: { switch(1) { case 10: return 10; case 20: return 20; default: return 40; } }
          case 20: return 100; default: return 500;
        } }
        weight "balanced" { return balance(30, 10, 50); }
        """);
    assertEquals(57.5f, config.evaluate(config.find("nested"), new int[] {15, 15}));
    assertEquals(500, config.evaluate(0, new int[] {20, 15}));
    assertEquals(30, config.evaluate(1, new int[] {1000, 1000}));
    var value = (WeightConfig.Value) config.weights().get(1).root();
    assertTrue(value.balanced());
    assertEquals(10, value.minimum());
    assertEquals(50, value.maximum());
    assertThrows(UnsupportedOperationException.class, () -> config.weights().clear());
  }

  @Test
  void sourceOrderAndNativeDefaultSentinelAreNotSortedAway() throws Exception {
    var config =
        load(
            """
        weight "descending" { switch(0) { case 20: return 100; case 10: return 200; default: return 50; } }
        weight "sentinel" { switch(0) { case 10: return 10; case 999999: return 100; default: return 200; } }
        weight "early" { switch(0) { default: return 50; case 10: return 100; case 20: return 200; } }
        """);
    assertEquals(100, config.evaluate(0, new int[] {15}));
    assertEquals(50, config.evaluate(0, new int[] {20}));
    assertEquals(100, config.evaluate(1, new int[] {10}));
    assertEquals(200, config.evaluate(1, new int[] {999999}));
    assertEquals(50, config.evaluate(2, new int[] {20}));
    assertEquals(200, config.evaluate(2, new int[] {999999}));
  }

  @Test
  void missingDefaultsAndDuplicateNamesHaveExplicitOriginalBehavior() throws Exception {
    var config =
        load(
            """
        weight "missing" { switch(0) { case 10: return 20; case 20: return 40; } }
        weight "same" { return 3; }
        weight "same" { return 5; }
        """);
    assertEquals(30, config.evaluate(0, new int[] {15}));
    assertEquals(0, config.evaluate(0, new int[] {20}));
    assertEquals(1, config.find("same"));
    assertEquals(-1, config.find("SAME"));
    assertEquals(2, config.diagnostics().size());
    assertEquals(5, config.evaluate(2, new int[] {0}));
  }

  @Test
  void originalScalarRoundingEvaluationTokensAndNegativeMagnitudeArePreserved() throws Exception {
    var config =
        load(
            """
        weight "precision" { switch(0) { case 10: return 1.2345; case 17: return 923.567; default: return 5.4; } }
        weight "evaluated" { return $evalfloat(1.234); }
        weight "negative" { return -20; }
        weight "inverted" { return balance(30,50,10); }
        """);
    assertEquals(132.996292f, config.evaluate(0, new int[] {11}));
    assertEquals(396.519867f, config.evaluate(0, new int[] {13}));
    assertEquals(1.234f, config.evaluate(1, new int[] {0}));
    assertEquals(20, config.evaluate(2, new int[] {0}));
    assertEquals(1, config.diagnostics().size());
    var inverted = (WeightConfig.Value) config.weights().get(3).root();
    assertEquals(50, inverted.minimum());
    assertEquals(10, inverted.maximum());
  }

  @Test
  void invalidGrammarIndicesAndBudgetsFailAndReleaseTheBorrowedSourceHandle() throws Exception {
    for (String text :
        List.of(
            "",
            "weight \"x\" { switch(0) {} }",
            "weight \"x\" { return random(); }",
            "weight \"x\" { switch(-1) { default: return 1; } }",
            "weight \"x\" { switch(256) { default: return 1; } }",
            "weight \"x\" { switch(0) { case -10: return 1; } }")) {
      try (var sources = new ScriptSources(memory(Map.of("weights.c", text)))) {
        assertThrows(ScriptException.class, () -> WeightConfig.load(sources, "weights.c"), text);
        assertEquals(0, sources.openCount());
      }
    }
    try (var sources =
        new ScriptSources(
            memory(Map.of("weights.c", "weight \"a\" { return 1; } weight \"b\" { return 2; }")))) {
      assertThrows(
          ScriptException.class,
          () -> WeightConfig.load(sources, "weights.c", new WeightConfig.Limits(1, 2, 2, 1)));
      assertEquals(0, sources.openCount());
    }
    var config = load("weight \"index\" { switch(10) { default: return 1; } }");
    assertEquals(11, config.requiredInventorySize());
    assertThrows(IllegalArgumentException.class, () -> config.evaluate(0, new int[10]));
    assertThrows(IllegalArgumentException.class, () -> config.evaluate(-1, new int[256]));
  }

  @Test
  void undecidedUsesBalanceBoundsAndConsumesRandomEvenForConstantIntervals() throws Exception {
    var config =
        load(
            """
        weight "constant" { return 50; }
        weight "balanced" { return balance(100, 20, 80); }
        weight "equal" { return balance(50, 20, 20); }
        """);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    java.util.function.IntSupplier random =
        () -> {
          calls.incrementAndGet();
          return 0;
        };
    assertEquals(50, config.evaluateUndecided(0, new int[0], random));
    assertEquals(20, config.evaluateUndecided(1, new int[0], random));
    assertEquals(20, config.evaluateUndecided(2, new int[0], random));
    assertEquals(3, calls.get());
    assertEquals(100, config.evaluate(1, new int[0]));
    assertEquals(50, config.evaluate(2, new int[0]));
  }

  @Test
  void undecidedMasksLowFifteenBitsAndRetainsInvertedBalanceBounds() throws Exception {
    var config = load("weight \"inverse\" { return balance(100, 80, 20); }");
    assertEquals(80, config.evaluateUndecided(0, new int[0], () -> 0));
    assertEquals(20, config.evaluateUndecided(0, new int[0], () -> 32767));
    assertEquals(20, config.evaluateUndecided(0, new int[0], () -> -1));
    assertEquals(80, config.evaluateUndecided(0, new int[0], () -> 32768));
    assertEquals(80, config.evaluateUndecided(0, new int[0], () -> Integer.MIN_VALUE));
    assertEquals(49.9990845f, config.evaluateUndecided(0, new int[0], () -> 16384));
  }

  @Test
  void undecidedEvaluatesNestedLeavesInLowerThenUpperOrder() throws Exception {
    var config = load(undecidedFixture());
    int[] inventory = new int[256];
    inventory[0] = inventory[1] = 50;
    int[] samples = {0, 32767, 0};
    var cursor = new java.util.concurrent.atomic.AtomicInteger();
    assertEquals(
        252.5f, config.evaluateUndecided(5, inventory, () -> samples[cursor.getAndIncrement()]));
    assertEquals(3, cursor.get());
    assertArrayEquals(new int[] {50, 50}, java.util.Arrays.copyOf(inventory, 2));
  }

  @Test
  void undecidedConsumesBothInterpolationEndpointsEvenAtZeroUpperContribution() throws Exception {
    var config = load(undecidedFixture());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    assertEquals(
        10,
        config.evaluateUndecided(
            4,
            new int[256],
            () -> {
              calls.incrementAndGet();
              return 0;
            }));
    assertEquals(2, calls.get());
    int[] inventory = new int[256];
    inventory[0] = -1;
    calls.set(0);
    assertEquals(
        10,
        config.evaluateUndecided(
            4,
            inventory,
            () -> {
              calls.incrementAndGet();
              return 0;
            }));
    assertEquals(1, calls.get());
  }

  @Test
  void undecidedDefaultSentinelConsumesDiscardedLowerBranchBeforeDefault() throws Exception {
    var config = load(undecidedFixture());
    int[] inventory = new int[256];
    inventory[0] = 100;
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    assertEquals(
        400,
        config.evaluateUndecided(
            4,
            inventory,
            () -> {
              calls.incrementAndGet();
              return 32767;
            }));
    assertEquals(2, calls.get());
    var missingDefault =
        load("weight \"x\" { switch(0) { case 10: return balance(20, 30, 40); } }");
    calls.set(0);
    assertEquals(
        0,
        missingDefault.evaluateUndecided(
            0,
            inventory,
            () -> {
              calls.incrementAndGet();
              return 32767;
            }));
    assertEquals(2, calls.get());
  }

  @Test
  void undecidedUpperNestedDecisionRemainsDeterministic() throws Exception {
    var config =
        load(
            """
        weight "upper" { switch(0) { case 10: return balance(1,5,10);
          case 20: switch(1) { case 10: return balance(2,20,30); default: return balance(3,30,40); }
          default: return balance(4,40,50); } }
        """);
    int[] inventory = new int[256];
    inventory[0] = 15;
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    assertEquals(
        3.5f,
        config.evaluateUndecided(
            0,
            inventory,
            () -> {
              calls.incrementAndGet();
              return 0;
            }));
    assertEquals(1, calls.get());
    inventory[0] = 20;
    calls.set(0);
    assertEquals(
        40,
        config.evaluateUndecided(
            0,
            inventory,
            () -> {
              calls.incrementAndGet();
              return 0;
            }));
    assertEquals(2, calls.get());
  }

  @Test
  void finalThresholdExhaustionReturnsBaseWithoutRandomnessOrChildDescent() throws Exception {
    var config =
        load(
            """
        weight "direct" { return balance(99,20,80); }
        weight "outer" { switch(0) { case 10: return balance(1,5,10);
          default: switch(1) { case 10: return balance(2,20,30); default: return balance(3,30,40); } } }
        """);
    int[] inventory = new int[256];
    inventory[0] = 999999;
    java.util.function.IntSupplier noCall =
        () -> {
          throw new AssertionError("Unexpected sample");
        };
    assertEquals(99, config.evaluateUndecided(0, inventory, noCall));
    assertEquals(0, config.evaluateUndecided(1, inventory, noCall));
    assertEquals(0, config.evaluate(1, inventory));
    inventory[0] = Integer.MAX_VALUE;
    assertEquals(99, config.evaluateUndecided(0, inventory, noCall));
    assertEquals(0, config.evaluateUndecided(1, inventory, noCall));
  }

  @Test
  void undecidedRejectsInvalidArgumentsBeforeConsumingTheBorrowedStream() throws Exception {
    var config = load(undecidedFixture());
    java.util.function.IntSupplier noCall =
        () -> {
          throw new AssertionError("Unexpected random draw");
        };
    assertThrows(
        IllegalArgumentException.class, () -> config.evaluateUndecided(-1, new int[256], noCall));
    assertThrows(
        IllegalArgumentException.class, () -> config.evaluateUndecided(99, new int[256], noCall));
    assertThrows(
        IllegalArgumentException.class, () -> config.evaluateUndecided(5, new int[1], noCall));
    assertThrows(NullPointerException.class, () -> config.evaluateUndecided(0, new int[256], null));
    assertThrows(NullPointerException.class, () -> config.evaluateUndecided(0, null, noCall));
    assertThrows(
        IllegalStateException.class,
        () ->
            config.evaluateUndecided(
                0,
                new int[256],
                () -> {
                  throw new IllegalStateException("stream");
                }));
    assertEquals(50, config.evaluate(0, new int[256]));
  }

  @Test
  void undecidedSeededSequenceMatchesCapturedNativeResultsAndConsumption() throws Exception {
    var config = load(undecidedFixture());
    int[] inventory = new int[256];
    inventory[0] = inventory[1] = 50;
    var state = new java.util.concurrent.atomic.AtomicInteger(1234);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    java.util.function.IntSupplier random =
        () -> {
          calls.incrementAndGet();
          return state.updateAndGet(value -> value * 1664525 + 1013904223) >>> 1;
        };
    float[] expected = {50, 68.7112045f, 21.6077156f, 20, 72.8933105f, 302.195038f};
    int[] expectedCalls = {1, 1, 1, 1, 2, 3};
    for (int i = 0; i < expected.length; i++) {
      calls.set(0);
      assertEquals(
          Float.floatToRawIntBits(expected[i]),
          Float.floatToRawIntBits(config.evaluateUndecided(i, inventory, random)));
      assertEquals(expectedCalls[i], calls.get());
    }
  }

  @Test
  void undecidedStreamsAreCallerOwnedAndDoNotAlterDeterministicConfiguration() throws Exception {
    var config = load(undecidedFixture());
    var before = config.weights();
    int[] inventory = new int[256];
    inventory[0] = inventory[1] = 50;
    assertEquals(20, config.evaluateUndecided(1, inventory, () -> 0));
    assertEquals(80, config.evaluateUndecided(1, inventory, () -> 32767));
    assertEquals(20, config.evaluateUndecided(1, inventory, () -> 0));
    assertEquals(100, config.evaluate(1, inventory));
    assertSame(before, config.weights());
    assertEquals(new WeightConfig.Value(100, 20, 80, true), config.weights().get(1).root());
  }

  private static String undecidedFixture() {
    return """
        weight "constant" { return 50; }
        weight "balanced" { return balance(100, 20, 80); }
        weight "inverse" { return balance(100, 80, 20); }
        weight "equal" { return balance(50, 20, 20); }
        weight "interpolate" { switch (0) { case 0: return balance(1, 10, 20); case 100: return balance(2, 100, 200); default: return balance(3, 300, 400); } }
        weight "nested" { switch (0) { case 0: switch (1) { case 0: return balance(1, 10, 20); case 100: return balance(2, 100, 200); default: return balance(3, 300, 400); } case 100: return balance(4, 400, 500); default: return balance(5, 500, 600); } }
        """;
  }

  private static WeightConfig load(String text) throws Exception {
    return WeightConfig.load(memory(Map.of("weights.c", text)), "weights.c");
  }

  private static VirtualFileSystem memory(Map<String, String> files) {
    return new VirtualFileSystem() {
      @Override
      public Optional<Origin> which(VirtualPath path) {
        return files.containsKey(path.value())
            ? Optional.of(new Origin("test", "memory", false))
            : Optional.empty();
      }

      @Override
      public List<VirtualPath> list(String directory) {
        return files.keySet().stream().map(VirtualPath::new).toList();
      }

      @Override
      public List<Origin> searchOrder() {
        return List.of();
      }

      @Override
      public byte[] read(VirtualPath path) throws NoSuchFileException {
        String value = files.get(path.value());
        if (value == null) throw new NoSuchFileException(path.value());
        return value.getBytes(StandardCharsets.ISO_8859_1);
      }

      @Override
      public void close() {}
    };
  }
}

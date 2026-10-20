package dev.bluevista.craftq3.engine.botlib.weight;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

final class GeneticSelectionTest {
  @Test
  void parentsAreDrawnByRankAndTheChildByReversedRank() {
    // random 0: the roulette walks until the remaining sum reaches zero, so it lands on the last
    // entry with a nonzero ranking.
    var selection = GeneticSelection.select(new float[] {1, 2, 3, 4}, constant(0)).orElseThrow();
    assertEquals(3, selection.parent1());
    assertEquals(2, selection.parent2());
    // The remaining rankings {1, 2} reverse to {1, 0}: the worst bot, entry 0, becomes the child.
    assertEquals(0, selection.child());
  }

  @Test
  void aFullRandomDrawPicksTheFirstEligibleEntry() {
    var selection =
        GeneticSelection.select(new float[] {5, -1, 1, 1}, constant(0x7fff)).orElseThrow();
    assertEquals(0, selection.parent1());
    assertEquals(2, selection.parent2());
    assertEquals(3, selection.child());
  }

  @Test
  void allZeroRankingsFallBackToARandomEligibleEntry() {
    var selection = GeneticSelection.select(new float[] {0, 0, 0}, constant(0)).orElseThrow();
    assertEquals(0, selection.parent1());
    assertEquals(1, selection.parent2());
    assertEquals(2, selection.child());
  }

  @Test
  void tooFewOrTooManyRankingsSelectNothing() {
    assertEquals(Optional.empty(), GeneticSelection.select(new float[] {1, -1, 2}, constant(0)));
    assertEquals(
        Optional.empty(),
        GeneticSelection.select(new float[GeneticSelection.MAX_RANKS + 1], constant(0)));
    assertTrue(
        GeneticSelection.select(new float[GeneticSelection.MAX_RANKS], constant(0)).isPresent());
  }

  private static IntSupplier constant(int bits) {
    return () -> bits;
  }
}

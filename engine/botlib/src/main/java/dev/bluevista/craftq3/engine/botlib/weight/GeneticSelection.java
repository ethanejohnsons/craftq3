package dev.bluevista.craftq3.engine.botlib.weight;

import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * Genetic selection for bot interbreeding ({@code GeneticParentsAndChildSelection}): two parents
 * are drawn with probability proportional to their ranking, then the child (the bot whose fuzzy
 * logic is replaced) is drawn from the rest with the rankings reversed, so the worst bot is the
 * likeliest child. Negative rankings mark bots that take no part.
 */
public final class GeneticSelection {
  /** The original's ranking table size. */
  public static final int MAX_RANKS = 256;

  public record Selection(int parent1, int parent2, int child) {}

  private GeneticSelection() {}

  /**
   * Empty, as the original's false return, when there are more than {@link #MAX_RANKS} rankings or
   * fewer than three valid ones. {@code randomBits} supplies the original's {@code rand()}.
   */
  public static Optional<Selection> select(float[] ranks, IntSupplier randomBits) {
    Objects.requireNonNull(ranks);
    Objects.requireNonNull(randomBits);
    if (ranks.length > MAX_RANKS) return Optional.empty();
    int valid = 0;
    for (float rank : ranks) if (rank >= 0) valid++;
    if (valid < 3) return Optional.empty();
    float[] rankings = ranks.clone();
    int parent1 = pick(rankings, randomBits);
    rankings[parent1] = -1;
    int parent2 = pick(rankings, randomBits);
    rankings[parent2] = -1;
    float max = 0;
    for (float rank : rankings) if (rank >= 0 && rank > max) max = rank;
    for (int i = 0; i < rankings.length; i++) if (rankings[i] >= 0) rankings[i] = max - rankings[i];
    return Optional.of(new Selection(parent1, parent2, pick(rankings, randomBits)));
  }

  /**
   * {@code GeneticSelection}: a roulette draw over non-negative rankings, else a random valid one.
   */
  static int pick(float[] rankings, IntSupplier randomBits) {
    float sum = 0;
    for (float rank : rankings) if (rank >= 0) sum += rank;
    if (sum > 0) {
      float select = random(randomBits) * sum;
      for (int i = 0; i < rankings.length; i++) {
        if (rankings[i] < 0) continue;
        sum -= rankings[i];
        if (sum <= select) return i;
      }
    }
    int index = (int) (random(randomBits) * rankings.length);
    for (int i = 0; i < rankings.length; i++) {
      if (index < rankings.length && rankings[index] >= 0) return index;
      index = (index + 1) % rankings.length;
    }
    return 0;
  }

  private static float random(IntSupplier randomBits) {
    return (randomBits.getAsInt() & 0x7fff) / (float) 0x7fff;
  }
}

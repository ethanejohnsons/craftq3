package dev.bluevista.craftq3.botlib.script;

/** Per-load budgets, with maxTokens also bounding all retained source tokens in one service. */
public record ScriptLimits(
    int maxFileBytes,
    int maxTotalBytes,
    int maxTokens,
    int maxExpansionSteps,
    int maxNesting,
    int maxIncludes,
    int maxMacros,
    int maxHandles,
    int maxTokenChars) {
  public static final ScriptLimits DEFAULT =
      new ScriptLimits(1024 * 1024, 8 * 1024 * 1024, 250_000, 1_000_000, 64, 128, 4096, 64, 1023);

  public ScriptLimits {
    if (maxFileBytes < 1
        || maxTotalBytes < maxFileBytes
        || maxTokens < 1
        || maxExpansionSteps < 1
        || maxNesting < 1
        || maxNesting > 256
        || maxIncludes < 1
        || maxMacros < 1
        || maxHandles < 1
        || maxTokenChars < 3
        || maxTokenChars > 1023) throw new IllegalArgumentException("Invalid script budgets");
  }
}

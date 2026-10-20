package dev.bluevista.craftq3.engine.testkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Locates the user's Quake III installation for {@link RealData} tests. */
public final class RealDataRoot {
  public static final String ENVIRONMENT_VARIABLE = "CRAFTQ3_Q3ROOT";

  private RealDataRoot() {}

  /** The installation root (the directory containing {@code baseq3}), if configured. */
  public static Optional<Path> find() {
    String value = System.getenv(ENVIRONMENT_VARIABLE);
    if (value == null || value.isBlank()) return Optional.empty();
    Path root = Path.of(value);
    return Files.isDirectory(root.resolve("baseq3")) ? Optional.of(root) : Optional.empty();
  }

  /** The installation root, failing the calling test if it is absent. */
  public static Path require() {
    return find()
        .orElseThrow(
            () -> new IllegalStateException(ENVIRONMENT_VARIABLE + " does not name a Q3 install"));
  }

  /** Enables a {@link RealData} test only when real data is configured. */
  public static final class Condition implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
      return find()
          .map(root -> ConditionEvaluationResult.enabled("real data at " + root))
          .orElseGet(
              () ->
                  ConditionEvaluationResult.disabled(
                      ENVIRONMENT_VARIABLE + " is not set to a Quake III installation"));
    }
  }
}

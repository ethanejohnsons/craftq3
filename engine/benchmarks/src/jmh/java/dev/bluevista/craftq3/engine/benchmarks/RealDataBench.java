package dev.bluevista.craftq3.engine.benchmarks;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The user's Quake III installation for the real-data benchmarks ({@code Real*Benchmark}), named by
 * {@code CRAFTQ3_Q3ROOT}. The build excludes those benchmarks when the variable is unset, so {@link
 * #root()} only fails when a benchmark is selected explicitly without data.
 */
final class RealDataBench {
  static final String ENVIRONMENT_VARIABLE = "CRAFTQ3_Q3ROOT";

  private RealDataBench() {}

  static Path root() {
    String value = System.getenv(ENVIRONMENT_VARIABLE);
    if (value == null || value.isBlank())
      throw new IllegalStateException(
          ENVIRONMENT_VARIABLE + " is not set; real-data benchmarks need a Quake III installation");
    Path root = Path.of(value);
    if (!Files.isDirectory(root.resolve("baseq3")))
      throw new IllegalStateException(ENVIRONMENT_VARIABLE + " has no baseq3 directory: " + root);
    return root;
  }
}

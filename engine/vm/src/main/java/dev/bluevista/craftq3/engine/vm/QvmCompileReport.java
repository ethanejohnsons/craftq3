package dev.bluevista.craftq3.engine.vm;

import java.util.Map;
import java.util.TreeMap;

/**
 * What compilation ({@link QvmInstance.ExecutionMode#COMPILE}) made of an image: how many
 * procedures run as JVM bytecode and why the others are left to the interpreter.
 *
 * @param module the image's name
 * @param procedures procedures in the image
 * @param compiled procedures compiled to JVM methods
 * @param fallbacks interpreted procedures by reason
 * @param classes hidden classes defined
 * @param bytecodeBytes total JVM code length of the compiled methods
 * @param compileNanos wall time spent analysing, generating and defining
 */
public record QvmCompileReport(
    String module,
    int procedures,
    int compiled,
    Map<String, Integer> fallbacks,
    int classes,
    long bytecodeBytes,
    long compileNanos) {
  public QvmCompileReport {
    fallbacks = Map.copyOf(fallbacks);
  }

  /** Procedures left to the interpreter. */
  public int interpreted() {
    return procedures - compiled;
  }

  @Override
  public String toString() {
    return module
        + ": "
        + compiled
        + "/"
        + procedures
        + " procedures compiled into "
        + classes
        + " classes ("
        + bytecodeBytes
        + " bytes of bytecode, "
        + compileNanos / 1_000_000
        + " ms)"
        + (fallbacks.isEmpty() ? "" : ", interpreted: " + new TreeMap<>(fallbacks));
  }
}

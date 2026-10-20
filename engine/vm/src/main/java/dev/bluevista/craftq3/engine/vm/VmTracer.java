package dev.bluevista.craftq3.engine.vm;

/**
 * Observes every executed instruction, for diagnostics only. With no tracer installed the
 * interpreter does no per-instruction bookkeeping at all.
 */
@FunctionalInterface
public interface VmTracer {
  /**
   * Called before the instruction at {@code pc} executes. A procedure's {@code ENTER} is reported
   * right after the {@code CALL} that reaches it.
   *
   * @param operandDepth operand stack entries in use (across nested invocations)
   * @param programStack the current procedure's frame base
   */
  void instruction(int pc, Opcode opcode, int operand, int operandDepth, int programStack);
}

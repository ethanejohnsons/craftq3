package dev.bluevista.craftq3.engine.vm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link VmTracer} that keeps an opcode histogram and the most recent instructions in a ring
 * buffer, for fault diagnostics. Used on the VM's owner thread only.
 */
public final class TraceRecorder implements VmTracer {
  /** One recorded instruction. */
  public record Step(int pc, Opcode opcode, int operand, int operandDepth, int programStack) {}

  private final long[] counts = new long[Opcode.count()];
  private final int[] pcs;
  private final int[] opcodes;
  private final int[] immediates;
  private final int[] depths;
  private final int[] stacks;
  private long recorded;

  public TraceRecorder(int capacity) {
    if (capacity < 1) throw new IllegalArgumentException("Trace capacity must be positive");
    pcs = new int[capacity];
    opcodes = new int[capacity];
    immediates = new int[capacity];
    depths = new int[capacity];
    stacks = new int[capacity];
  }

  @Override
  public void instruction(int pc, Opcode opcode, int operand, int operandDepth, int programStack) {
    counts[opcode.ordinal()]++;
    int slot = (int) (recorded % pcs.length);
    pcs[slot] = pc;
    opcodes[slot] = opcode.ordinal();
    immediates[slot] = operand;
    depths[slot] = operandDepth;
    stacks[slot] = programStack;
    recorded++;
  }

  /** Total instructions observed. */
  public long recorded() {
    return recorded;
  }

  /** Executions per opcode, omitting opcodes never executed. */
  public Map<Opcode, Long> histogram() {
    var histogram = new EnumMap<Opcode, Long>(Opcode.class);
    for (Opcode opcode : Opcode.values())
      if (counts[opcode.ordinal()] != 0) histogram.put(opcode, counts[opcode.ordinal()]);
    return histogram;
  }

  /** The most recent instructions, oldest first, at most the capacity. */
  public List<Step> recent() {
    int size = (int) Math.min(recorded, pcs.length);
    var steps = new ArrayList<Step>(size);
    for (long i = recorded - size; i < recorded; i++) {
      int slot = (int) (i % pcs.length);
      steps.add(
          new Step(
              pcs[slot], Opcode.of(opcodes[slot]), immediates[slot], depths[slot], stacks[slot]));
    }
    return List.copyOf(steps);
  }

  public void clear() {
    java.util.Arrays.fill(counts, 0);
    recorded = 0;
  }
}

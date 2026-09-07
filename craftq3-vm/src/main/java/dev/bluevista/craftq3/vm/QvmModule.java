package dev.bluevista.craftq3.vm;

import java.util.BitSet;
import java.util.List;

/** Validated code is immutable and occupies a different address space from mutable VM data. */
public final class QvmModule {
  public record Header(
      int version,
      int instructionCount,
      int codeOffset,
      int codeLength,
      int dataOffset,
      int dataLength,
      int literalLength,
      int bssLength,
      int jumpTargetBytes) {}

  public record Instruction(Opcode opcode, int operand, int fileOffset) {}

  private final String name;
  private final Header header;
  private final List<Instruction> instructions;
  private final byte[] initialData;
  private final int memorySize;
  private final int[] owners;
  private final BitSet jumpTargets;

  QvmModule(
      String name,
      Header header,
      List<Instruction> instructions,
      byte[] initialData,
      int memorySize,
      int[] owners,
      BitSet jumpTargets) {
    this.name = name;
    this.header = header;
    this.instructions = List.copyOf(instructions);
    this.initialData = initialData.clone();
    this.memorySize = memorySize;
    this.owners = owners.clone();
    this.jumpTargets = (BitSet) jumpTargets.clone();
  }

  public String name() {
    return name;
  }

  public Header header() {
    return header;
  }

  public List<Instruction> instructions() {
    return instructions;
  }

  public byte[] initialData() {
    return initialData.clone();
  }

  public int memorySize() {
    return memorySize;
  }

  public int functionAt(int pc) {
    return owners[pc];
  }

  public boolean jumpTarget(int pc) {
    return jumpTargets.get(pc);
  }

  public int jumpTargetCount() {
    return jumpTargets.cardinality();
  }
}

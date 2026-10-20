package dev.bluevista.craftq3.engine.vm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** A small original QVM assembler for tests. It contains no proprietary game code or data. */
final class QvmAssembler {
  static final Syscalls NO_SYSCALLS =
      (vm, number, args) -> {
        throw new IllegalStateException("Unexpected syscall " + number);
      };

  private record Instruction(Opcode opcode, int operand) {}

  private final List<Instruction> instructions = new ArrayList<>();
  private byte[] data = new byte[64];
  private byte[] literal = new byte[0];
  private int bss = QvmImage.PROGRAM_STACK_SIZE;
  private int stack = QvmImage.PROGRAM_STACK_SIZE;
  private int version = 1;
  private int[] targets = new int[0];

  QvmAssembler op(Opcode opcode) {
    return op(opcode, 0);
  }

  QvmAssembler op(Opcode opcode, int operand) {
    instructions.add(new Instruction(opcode, operand));
    return this;
  }

  QvmAssembler data(int... words) {
    var buffer = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int word : words) buffer.putInt(word);
    data = buffer.array();
    return this;
  }

  QvmAssembler literal(byte... bytes) {
    literal = bytes.clone();
    return this;
  }

  QvmAssembler bss(int bytes) {
    bss = bytes;
    return this;
  }

  /** Program stack size used when parsing; small stacks keep synthetic images small. */
  QvmAssembler stack(int bytes) {
    stack = bytes;
    return this;
  }

  QvmAssembler v2(int... values) {
    version = 2;
    targets = values.clone();
    return this;
  }

  int size() {
    return instructions.size();
  }

  byte[] bytes() {
    int header = version == 1 ? 32 : 36;
    int code = instructions.stream().mapToInt(i -> 1 + i.opcode().operandBytes()).sum();
    code = (code + 3) & ~3;
    var buffer =
        ByteBuffer.allocate(header + code + data.length + literal.length + targets.length * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(version == 1 ? QvmImage.MAGIC_V1 : QvmImage.MAGIC_V2);
    buffer.putInt(instructions.size()).putInt(header).putInt(code);
    buffer.putInt(header + code).putInt(data.length).putInt(literal.length).putInt(bss);
    if (version == 2) buffer.putInt(targets.length * 4);
    for (var instruction : instructions) {
      buffer.put((byte) instruction.opcode().ordinal());
      if (instruction.opcode().operandBytes() == 4) buffer.putInt(instruction.operand());
      else if (instruction.opcode().operandBytes() == 1) buffer.put((byte) instruction.operand());
    }
    buffer.position(header + code);
    buffer.put(data).put(literal);
    for (int target : targets) buffer.putInt(target);
    return buffer.array();
  }

  QvmImage image() {
    return QvmImage.parse("synthetic", bytes(), stack);
  }

  QvmInstance vm() {
    return new QvmInstance(image(), NO_SYSCALLS);
  }

  QvmInstance vm(Syscalls syscalls) {
    return new QvmInstance(image(), syscalls);
  }

  QvmInstance vm(Syscalls syscalls, QvmInstance.Limits limits) {
    return new QvmInstance(
        image(), syscalls, limits, dev.bluevista.craftq3.engine.common.time.Clock.system());
  }
}

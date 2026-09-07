package dev.bluevista.craftq3.vm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Original, small test bytecode assembler. It contains no proprietary game code or data. */
final class QvmTestModule {
  private record Instruction(Opcode opcode, int operand) {}

  private final List<Instruction> instructions = new ArrayList<>();
  private byte[] data = new byte[64];
  private byte[] literal = new byte[0];
  private int bss = 65536;
  private int version = 1;
  private int[] targets = new int[0];

  QvmTestModule op(Opcode opcode) {
    return op(opcode, 0);
  }

  QvmTestModule op(Opcode opcode, int operand) {
    instructions.add(new Instruction(opcode, operand));
    return this;
  }

  QvmTestModule patch(int index, int operand) {
    instructions.set(index, new Instruction(instructions.get(index).opcode(), operand));
    return this;
  }

  QvmTestModule data(int... words) {
    var buffer = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int word : words) buffer.putInt(word);
    data = buffer.array();
    return this;
  }

  QvmTestModule literal(byte... bytes) {
    literal = bytes.clone();
    return this;
  }

  QvmTestModule bss(int bytes) {
    bss = bytes;
    return this;
  }

  QvmTestModule v2(int... values) {
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
    buffer.putInt(version == 1 ? QvmReader.MAGIC : QvmReader.MAGIC_V2);
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

  QvmModule module() throws QvmFormatException {
    return QvmReader.read("synthetic", bytes());
  }

  QvmInterpreter vm() throws QvmFormatException {
    return new QvmInterpreter(
        module(),
        (memory, syscall, args) -> {
          throw new IllegalStateException("Unexpected syscall " + syscall);
        });
  }
}

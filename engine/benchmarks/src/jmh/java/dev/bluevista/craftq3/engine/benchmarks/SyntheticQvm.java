package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.vm.Opcode;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles small original QVM images for the interpreter benchmarks. It contains no proprietary
 * game code or data.
 */
final class SyntheticQvm {
  private record Instruction(Opcode opcode, int operand) {}

  private final List<Instruction> instructions = new ArrayList<>();
  private int bss = QvmImage.PROGRAM_STACK_SIZE;

  SyntheticQvm op(Opcode opcode) {
    return op(opcode, 0);
  }

  SyntheticQvm op(Opcode opcode, int operand) {
    instructions.add(new Instruction(opcode, operand));
    return this;
  }

  SyntheticQvm bss(int bytes) {
    bss = bytes;
    return this;
  }

  int size() {
    return instructions.size();
  }

  QvmImage image(String name) {
    byte[] data = new byte[64];
    int header = 32;
    int code = 0;
    for (Instruction instruction : instructions) code += 1 + instruction.opcode().operandBytes();
    code = (code + 3) & ~3;
    ByteBuffer buffer =
        ByteBuffer.allocate(header + code + data.length).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(QvmImage.MAGIC_V1);
    buffer.putInt(instructions.size()).putInt(header).putInt(code);
    buffer.putInt(header + code).putInt(data.length).putInt(0).putInt(bss);
    for (Instruction instruction : instructions) {
      buffer.put((byte) instruction.opcode().ordinal());
      if (instruction.opcode().operandBytes() == 4) buffer.putInt(instruction.operand());
      else if (instruction.opcode().operandBytes() == 1) buffer.put((byte) instruction.operand());
    }
    buffer.position(header + code);
    buffer.put(data);
    return QvmImage.parse(name, buffer.array(), QvmImage.PROGRAM_STACK_SIZE);
  }
}

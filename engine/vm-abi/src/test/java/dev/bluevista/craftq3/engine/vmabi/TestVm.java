package dev.bluevista.craftq3.engine.vmabi;

import dev.bluevista.craftq3.engine.vm.Opcode;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.Syscalls;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Builds tiny synthetic QVMs for tests; no game code or data. */
public final class TestVm {
  /** Program stack of the synthetic images (small keeps memory small). */
  public static final int STACK = 0x1000;

  /** Guest memory of {@link #instance}: 64 KiB, the low part free for test structs. */
  public static final int MEMORY = 0x10000;

  private record Instruction(Opcode opcode, int operand) {}

  private final List<Instruction> code = new ArrayList<>();
  private int bss = MEMORY - 4;
  private String name = "synthetic";

  public TestVm op(Opcode opcode, int operand) {
    code.add(new Instruction(opcode, operand));
    return this;
  }

  public TestVm op(Opcode opcode) {
    return op(opcode, 0);
  }

  public TestVm name(String value) {
    name = value;
    return this;
  }

  /** Adds {@code CONST -1-number; CALL; POP} so the image imports trap {@code number}. */
  public TestVm imports(int number) {
    return op(Opcode.CONST, -1 - number).op(Opcode.CALL).op(Opcode.POP);
  }

  public byte[] bytes() {
    List<Instruction> all = new ArrayList<>(code);
    if (all.isEmpty() || all.getFirst().opcode() != Opcode.ENTER)
      all.addFirst(new Instruction(Opcode.ENTER, 8));
    if (all.getLast().opcode() != Opcode.LEAVE) {
      all.add(new Instruction(Opcode.CONST, 0));
      all.add(new Instruction(Opcode.LEAVE, all.getFirst().operand()));
    }
    int header = 32;
    int length = all.stream().mapToInt(i -> 1 + i.opcode().operandBytes()).sum();
    length = (length + 3) & ~3;
    byte[] data = new byte[4];
    ByteBuffer out =
        ByteBuffer.allocate(header + length + data.length).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(QvmImage.MAGIC_V1).putInt(all.size()).putInt(header).putInt(length);
    out.putInt(header + length).putInt(data.length).putInt(0).putInt(bss);
    for (Instruction instruction : all) {
      out.put((byte) instruction.opcode().ordinal());
      if (instruction.opcode().operandBytes() == 4) out.putInt(instruction.operand());
      else if (instruction.opcode().operandBytes() == 1) out.put((byte) instruction.operand());
    }
    out.position(header + length);
    out.put(data);
    return out.array();
  }

  public QvmImage image() {
    return QvmImage.parse(name, bytes(), STACK);
  }

  public QvmInstance vm(Syscalls syscalls) {
    return new QvmInstance(image(), syscalls);
  }

  /** A VM whose code does nothing, for driving handlers directly with its memory. */
  public static QvmInstance instance() {
    return new TestVm().vm((vm, number, args) -> 0);
  }

  /** A VM that calls trap {@code number} with up to three arguments and returns its result. */
  public static QvmInstance caller(Syscalls syscalls, int number, int... args) {
    TestVm code = new TestVm().op(Opcode.ENTER, 8 + 4 * 4);
    for (int i = 0; i < args.length; i++) code.op(Opcode.CONST, args[i]).op(Opcode.ARG, 8 + 4 * i);
    code.op(Opcode.CONST, -1 - number).op(Opcode.CALL).op(Opcode.LEAVE, 8 + 4 * 4);
    return code.vm(syscalls);
  }
}

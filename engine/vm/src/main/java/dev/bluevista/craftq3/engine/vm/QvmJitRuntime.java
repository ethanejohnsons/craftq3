package dev.bluevista.craftq3.engine.vm;

import java.lang.invoke.VarHandle;

/**
 * Small static helpers that code compiled by {@link QvmCompiler} calls; the JIT inlines them. Each
 * mirrors one interpreter step of {@link QvmInstance} exactly: guest addresses are masked into the
 * data segment and wide accesses fault when they would run past its end. The {@code rd}/{@code wr}
 * helpers take addresses the compiler proved to be in range.
 */
final class QvmJitRuntime {
  private static final VarHandle INT = QvmMemory.INT;
  private static final VarHandle SHORT = QvmMemory.SHORT;

  private QvmJitRuntime() {}

  // ---- proven in range ----

  static int rd1(byte[] mem, int address) {
    return mem[address] & 0xff;
  }

  static int rd2(byte[] mem, int address) {
    return (short) SHORT.get(mem, address) & 0xffff;
  }

  static int rd4(byte[] mem, int address) {
    return (int) INT.get(mem, address);
  }

  /** Value pushed after the address. */
  static void wr1(byte[] mem, int address, int value) {
    mem[address] = (byte) value;
  }

  static void wr2(byte[] mem, int address, int value) {
    SHORT.set(mem, address, (short) value);
  }

  static void wr4(byte[] mem, int address, int value) {
    INT.set(mem, address, value);
  }

  /** Value already on the stack below the segment and address. */
  static void put1(int value, byte[] mem, int address) {
    mem[address] = (byte) value;
  }

  static void put2(int value, byte[] mem, int address) {
    SHORT.set(mem, address, (short) value);
  }

  static void put4(int value, byte[] mem, int address) {
    INT.set(mem, address, value);
  }

  // ---- masked and checked ----

  static int ld1(int address, byte[] mem) {
    return mem[address & (mem.length - 1)] & 0xff;
  }

  static int ld2(int address, byte[] mem, QvmInstance vm, int pc) {
    int offset = address & (mem.length - 1);
    if (offset > mem.length - 2) throw vm.memoryFault(pc, offset, 2);
    return (short) SHORT.get(mem, offset) & 0xffff;
  }

  static int ld4(int address, byte[] mem, QvmInstance vm, int pc) {
    int offset = address & (mem.length - 1);
    if (offset > mem.length - 4) throw vm.memoryFault(pc, offset, 4);
    return (int) INT.get(mem, offset);
  }

  static void st1(int address, int value, byte[] mem) {
    mem[address & (mem.length - 1)] = (byte) value;
  }

  static void st2(int address, int value, byte[] mem, QvmInstance vm, int pc) {
    int offset = address & (mem.length - 1);
    if (offset > mem.length - 2) throw vm.memoryFault(pc, offset, 2);
    SHORT.set(mem, offset, (short) value);
  }

  static void st4(int address, int value, byte[] mem, QvmInstance vm, int pc) {
    int offset = address & (mem.length - 1);
    if (offset > mem.length - 4) throw vm.memoryFault(pc, offset, 4);
    INT.set(mem, offset, value);
  }

  static void blockCopy(
      int destination, int source, QvmInstance vm, int length, int executed, int pc) {
    vm.jitBlockCopy(destination, source, length, executed, pc);
  }

  // ---- arithmetic ----

  static int divi(int left, int right, QvmInstance vm, int pc) {
    if (right == 0) throw vm.jitFault(QvmInstance.FAULT_DIVISION, pc, 0);
    return left / right;
  }

  static int divu(int left, int right, QvmInstance vm, int pc) {
    if (right == 0) throw vm.jitFault(QvmInstance.FAULT_DIVISION, pc, 0);
    return Integer.divideUnsigned(left, right);
  }

  static int modi(int left, int right, QvmInstance vm, int pc) {
    if (right == 0) throw vm.jitFault(QvmInstance.FAULT_DIVISION, pc, 0);
    return left % right;
  }

  static int modu(int left, int right, QvmInstance vm, int pc) {
    if (right == 0) throw vm.jitFault(QvmInstance.FAULT_DIVISION, pc, 0);
    return Integer.remainderUnsigned(left, right);
  }

  static int addf(int left, int right) {
    return Float.floatToIntBits(Float.intBitsToFloat(left) + Float.intBitsToFloat(right));
  }

  static int subf(int left, int right) {
    return Float.floatToIntBits(Float.intBitsToFloat(left) - Float.intBitsToFloat(right));
  }

  static int mulf(int left, int right) {
    return Float.floatToIntBits(Float.intBitsToFloat(left) * Float.intBitsToFloat(right));
  }

  static int divf(int left, int right) {
    return Float.floatToIntBits(Float.intBitsToFloat(left) / Float.intBitsToFloat(right));
  }

  static int cvif(int value) {
    return Float.floatToRawIntBits((float) value);
  }

  static int cvfi(int value) {
    return QvmInstance.truncate(Float.intBitsToFloat(value));
  }

  static boolean eqf(int left, int right) {
    return Float.intBitsToFloat(left) == Float.intBitsToFloat(right);
  }

  static boolean nef(int left, int right) {
    return Float.intBitsToFloat(left) != Float.intBitsToFloat(right);
  }

  static boolean ltf(int left, int right) {
    return Float.intBitsToFloat(left) < Float.intBitsToFloat(right);
  }

  static boolean lef(int left, int right) {
    return Float.intBitsToFloat(left) <= Float.intBitsToFloat(right);
  }

  static boolean gtf(int left, int right) {
    return Float.intBitsToFloat(left) > Float.intBitsToFloat(right);
  }

  static boolean gef(int left, int right) {
    return Float.intBitsToFloat(left) >= Float.intBitsToFloat(right);
  }
}

package dev.bluevista.craftq3.engine.vm;

/**
 * QVM's on-disk opcode numbers (not Java bytecode). Declaration order is the file ABI: {@link
 * #ordinal()} is the byte stored in the code segment. The interpreter dispatches on the matching
 * {@link Op} constants.
 */
public enum Opcode {
  UNDEF,
  IGNORE,
  BREAK,
  ENTER,
  LEAVE,
  CALL,
  PUSH,
  POP,
  CONST,
  LOCAL,
  JUMP,
  EQ,
  NE,
  LTI,
  LEI,
  GTI,
  GEI,
  LTU,
  LEU,
  GTU,
  GEU,
  EQF,
  NEF,
  LTF,
  LEF,
  GTF,
  GEF,
  LOAD1,
  LOAD2,
  LOAD4,
  STORE1,
  STORE2,
  STORE4,
  ARG,
  BLOCK_COPY,
  SEX8,
  SEX16,
  NEGI,
  ADD,
  SUB,
  DIVI,
  DIVU,
  MODI,
  MODU,
  MULI,
  MULU,
  BAND,
  BOR,
  BXOR,
  BCOM,
  LSH,
  RSHI,
  RSHU,
  NEGF,
  ADDF,
  SUBF,
  DIVF,
  MULF,
  CVIF,
  CVFI;

  private static final Opcode[] BY_CODE = values();

  /** The opcode stored as {@code code}, or {@code null} if no opcode has that number. */
  public static Opcode of(int code) {
    return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
  }

  /** Number of opcodes; codes are {@code 0..count()-1}. */
  public static int count() {
    return BY_CODE.length;
  }

  /** Width of the immediate operand that follows the opcode byte in the code segment. */
  public int operandBytes() {
    if (this == ARG) return 1;
    return this == ENTER
            || this == LEAVE
            || this == CONST
            || this == LOCAL
            || this == BLOCK_COPY
            || conditionalBranch()
        ? 4
        : 0;
  }

  /** {@code EQ} through {@code GEF}: pops two operands and branches to the immediate target. */
  public boolean conditionalBranch() {
    return ordinal() >= EQ.ordinal() && ordinal() <= GEF.ordinal();
  }
}

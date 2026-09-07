package dev.bluevista.craftq3.vm;

/** QVM's on-disk opcode numbers, not Java bytecode. Declaration order is the public file ABI. */
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

  public boolean conditionalBranch() {
    return ordinal() >= EQ.ordinal() && ordinal() <= GEF.ordinal();
  }
}

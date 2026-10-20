package dev.bluevista.craftq3.engine.vm;

/**
 * Compile-time opcode numbers for the dispatch {@code switch}; each equals the ordinal of the
 * {@link Opcode} of the same name (checked by a test). {@link #END} is an internal sentinel placed
 * after the last instruction so running off the end of code needs no bounds check.
 */
final class Op {
  static final int UNDEF = 0;
  static final int IGNORE = 1;
  static final int BREAK = 2;
  static final int ENTER = 3;
  static final int LEAVE = 4;
  static final int CALL = 5;
  static final int PUSH = 6;
  static final int POP = 7;
  static final int CONST = 8;
  static final int LOCAL = 9;
  static final int JUMP = 10;
  static final int EQ = 11;
  static final int NE = 12;
  static final int LTI = 13;
  static final int LEI = 14;
  static final int GTI = 15;
  static final int GEI = 16;
  static final int LTU = 17;
  static final int LEU = 18;
  static final int GTU = 19;
  static final int GEU = 20;
  static final int EQF = 21;
  static final int NEF = 22;
  static final int LTF = 23;
  static final int LEF = 24;
  static final int GTF = 25;
  static final int GEF = 26;
  static final int LOAD1 = 27;
  static final int LOAD2 = 28;
  static final int LOAD4 = 29;
  static final int STORE1 = 30;
  static final int STORE2 = 31;
  static final int STORE4 = 32;
  static final int ARG = 33;
  static final int BLOCK_COPY = 34;
  static final int SEX8 = 35;
  static final int SEX16 = 36;
  static final int NEGI = 37;
  static final int ADD = 38;
  static final int SUB = 39;
  static final int DIVI = 40;
  static final int DIVU = 41;
  static final int MODI = 42;
  static final int MODU = 43;
  static final int MULI = 44;
  static final int MULU = 45;
  static final int BAND = 46;
  static final int BOR = 47;
  static final int BXOR = 48;
  static final int BCOM = 49;
  static final int LSH = 50;
  static final int RSHI = 51;
  static final int RSHU = 52;
  static final int NEGF = 53;
  static final int ADDF = 54;
  static final int SUBF = 55;
  static final int DIVF = 56;
  static final int MULF = 57;
  static final int CVIF = 58;
  static final int CVFI = 59;

  /** Internal: the instruction slot after the last real instruction. */
  static final int END = 60;

  private Op() {}
}

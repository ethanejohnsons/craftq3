package dev.bluevista.craftq3.engine.vm;

import dev.bluevista.craftq3.engine.common.io.Ranges;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A parsed and validated QVM (v1 or v2). Immutable and shareable across any number of {@link
 * QvmInstance}s on any threads. Code is decoded once into flat opcode and operand arrays and lives
 * in its own address space, separate from the data segment.
 *
 * <p>Data layout, as in Quake III: the data segment holds data, then literals, then BSS, and its
 * size is {@code data + lit + bss} rounded up to a power of two. The program stack occupies the top
 * {@link #programStackSize()} bytes of the segment. q3asm reserves that stack at the end of BSS, so
 * the image is accepted when either its BSS is large enough to contain the reservation or the whole
 * of data, literals and BSS lies below the stack; anything else would let the stack overwrite guest
 * data and is rejected.
 */
public final class QvmImage {
  public static final int MAGIC_V1 = 0x12721444;
  public static final int MAGIC_V2 = 0x12721445;
  public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
  public static final int MAX_MEMORY_BYTES = 64 * 1024 * 1024;
  public static final int MAX_INSTRUCTIONS = 2_000_000;

  /** Quake III's program stack size; q3asm reserves the same amount at the end of BSS. */
  public static final int PROGRAM_STACK_SIZE = 0x10000;

  /** Smallest program stack that still holds the vmMain frame plus one procedure frame. */
  public static final int MIN_PROGRAM_STACK_SIZE = 128;

  /** Largest procedure frame (ENTER/LEAVE immediate) accepted. */
  public static final int MAX_FRAME_BYTES = 1024 * 1024;

  private static final int HEADER_V1_BYTES = 32;
  private static final int HEADER_V2_BYTES = 36;
  private static final int MAX_NAME_LENGTH = 255;

  /** Flag: the instruction is a target of the v2 jump table. */
  static final byte FLAG_JUMP_TABLE_TARGET = 1;

  /**
   * Flag: a {@code JUMP} directly preceded by {@code CONST} that no branch, table entry or direct
   * jump can reach except by falling through, so its target is the constant (v2 needs no table
   * entry for it).
   */
  static final byte FLAG_DIRECT_JUMP = 2;

  private final String name;
  private final int version;
  private final int instructionCount;
  private final int dataLength;
  private final int literalLength;
  private final int bssLength;
  private final int jumpTableTargets;
  private final int memorySize;
  private final int programStackSize;

  // Package-private, never mutated after construction; read by QvmInstance's dispatch loop.
  final int[] opcodes;
  final int[] operands;
  final int[] procedures;
  final byte[] flags;
  final byte[] initialData;

  private QvmImage(
      String name,
      int version,
      int dataLength,
      int literalLength,
      int bssLength,
      int jumpTableTargets,
      int memorySize,
      int programStackSize,
      int[] opcodes,
      int[] operands,
      int[] procedures,
      byte[] flags,
      byte[] initialData) {
    this.name = name;
    this.version = version;
    this.instructionCount = procedures.length;
    this.dataLength = dataLength;
    this.literalLength = literalLength;
    this.bssLength = bssLength;
    this.jumpTableTargets = jumpTableTargets;
    this.memorySize = memorySize;
    this.programStackSize = programStackSize;
    this.opcodes = opcodes;
    this.operands = operands;
    this.procedures = procedures;
    this.flags = flags;
    this.initialData = initialData;
  }

  /** How far down the program stack of a parsed module may grow. */
  public enum StackPolicy {
    /** Exactly {@link #PROGRAM_STACK_SIZE}, the region q3asm reserves. */
    RESERVED,
    /**
     * As the original interpreter, which has no stack-bottom check: the stack starts at the top of
     * the power-of-two data segment and may use everything down to the end of the guest's globals
     * (the 64 KiB q3asm reserves at the end of BSS plus the rounding slack). Some builds (Q3Rally)
     * need more than the reservation. Never smaller than {@link #RESERVED}.
     */
    NATIVE_EXTENT
  }

  /** Parses with Quake III's {@link #PROGRAM_STACK_SIZE}. */
  public static QvmImage parse(String name, byte[] bytes) {
    return parse(name, bytes, PROGRAM_STACK_SIZE);
  }

  /**
   * Parses with Quake III's {@link #PROGRAM_STACK_SIZE}, then applies {@code policy}. Validation is
   * that of the reserved stack, so a module accepted under {@link StackPolicy#RESERVED} is accepted
   * under every policy.
   *
   * @throws QvmFormatException naming the module and the first problem found
   */
  public static QvmImage parse(String name, byte[] bytes, StackPolicy policy) {
    QvmImage image = parse(name, bytes);
    return switch (policy) {
      case RESERVED -> image;
      case NATIVE_EXTENT -> image.withStackSize(nativeStackExtent(image));
    };
  }

  /**
   * The bytes between the top of the data segment and the end of the guest's globals, word-aligned.
   * When BSS holds the q3asm reservation, the globals end that far below the end of BSS; but a
   * stack larger than BSS itself must lie entirely above data, literals and BSS.
   */
  private static int nativeStackExtent(QvmImage image) {
    long total = (long) image.dataLength + image.literalLength + image.bssLength;
    long globalsEnd = image.bssLength >= PROGRAM_STACK_SIZE ? total - PROGRAM_STACK_SIZE : total;
    long available = (image.memorySize - globalsEnd) & ~3L;
    if (image.bssLength < available) available = (image.memorySize - total) & ~3L;
    return (int) Math.max(available, image.programStackSize);
  }

  /** This image with a larger program stack; the decoded code and data are shared. */
  private QvmImage withStackSize(int stackSize) {
    if (stackSize == programStackSize) return this;
    return new QvmImage(
        name,
        version,
        dataLength,
        literalLength,
        bssLength,
        jumpTableTargets,
        memorySize,
        stackSize,
        opcodes,
        operands,
        procedures,
        flags,
        initialData);
  }

  /**
   * Parses and validates a QVM file. {@code programStackSize} other than {@link
   * #PROGRAM_STACK_SIZE} is for small synthetic modules only.
   *
   * @throws QvmFormatException naming the module and the first problem found
   */
  public static QvmImage parse(String name, byte[] bytes, int programStackSize) {
    if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH)
      throw new QvmFormatException(name, "Invalid module name");
    return new Parser(name, bytes, programStackSize).parse();
  }

  public String name() {
    return name;
  }

  /** 1 or 2. */
  public int version() {
    return version;
  }

  public int instructionCount() {
    return instructionCount;
  }

  public int dataLength() {
    return dataLength;
  }

  public int literalLength() {
    return literalLength;
  }

  public int bssLength() {
    return bssLength;
  }

  /** Number of distinct instructions named by the v2 jump table (0 for v1). */
  public int jumpTableTargets() {
    return jumpTableTargets;
  }

  /** Data segment size: {@code data + lit + bss} rounded up to a power of two. */
  public int memorySize() {
    return memorySize;
  }

  public int programStackSize() {
    return programStackSize;
  }

  /** Lowest address the program stack may reach: {@code memorySize - programStackSize}. */
  public int stackBottom() {
    return memorySize - programStackSize;
  }

  public Opcode opcode(int pc) {
    checkPc(pc);
    return Opcode.of(opcodes[pc]);
  }

  public int operand(int pc) {
    checkPc(pc);
    return operands[pc];
  }

  /** The instruction index of the {@code ENTER} that begins the procedure containing {@code pc}. */
  public int procedureAt(int pc) {
    checkPc(pc);
    return procedures[pc];
  }

  public boolean jumpTableTarget(int pc) {
    checkPc(pc);
    return (flags[pc] & FLAG_JUMP_TABLE_TARGET) != 0;
  }

  /** A copy of the initialized data and literal bytes. */
  public byte[] initialData() {
    return initialData.clone();
  }

  private void checkPc(int pc) {
    if (pc < 0 || pc >= instructionCount)
      throw new IndexOutOfBoundsException("Instruction " + pc + " of " + instructionCount);
  }

  private static final class Parser {
    private final String name;
    private final byte[] bytes;
    private final int programStackSize;
    private final ByteBuffer input;

    Parser(String name, byte[] bytes, int programStackSize) {
      this.name = name;
      this.bytes = bytes;
      this.programStackSize = programStackSize;
      this.input = bytes == null ? null : ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    QvmImage parse() {
      if (programStackSize < MIN_PROGRAM_STACK_SIZE
          || programStackSize > MAX_MEMORY_BYTES
          || (programStackSize & 3) != 0)
        throw bad("Invalid program stack size " + programStackSize);
      if (bytes == null || bytes.length < HEADER_V1_BYTES || bytes.length > MAX_FILE_BYTES)
        throw bad("Invalid QVM file size " + (bytes == null ? "null" : bytes.length));
      int magic = input.getInt(0);
      if (magic != MAGIC_V1 && magic != MAGIC_V2)
        throw bad(
            "Unsupported magic 0x"
                + Integer.toHexString(magic)
                + " (native modules are forbidden)");
      int version = magic == MAGIC_V1 ? 1 : 2;
      int headerBytes = version == 1 ? HEADER_V1_BYTES : HEADER_V2_BYTES;
      if (bytes.length < headerBytes) throw bad("Truncated v2 header");
      int count = input.getInt(4), codeOffset = input.getInt(8), codeLength = input.getInt(12);
      int dataOffset = input.getInt(16), dataLength = input.getInt(20);
      int litLength = input.getInt(24), bssLength = input.getInt(28);
      int jumpBytes = version == 2 ? input.getInt(32) : 0;

      if (count <= 0 || count > MAX_INSTRUCTIONS || codeLength < count)
        throw bad("Invalid instruction count " + count + " for code length " + codeLength);
      if (dataLength < 0
          || litLength < 0
          || bssLength < 0
          || jumpBytes < 0
          || (dataLength & 3) != 0
          || (jumpBytes & 3) != 0) throw bad("Invalid data/literal/BSS/jump-table lengths");
      section(codeOffset, codeLength, headerBytes, "code");
      long initialized = (long) dataLength + litLength;
      section(dataOffset, initialized + jumpBytes, headerBytes, "data and jump table");
      if ((codeOffset & 3) != 0 || (dataOffset & 3) != 0)
        throw bad("Section offsets must be word-aligned");
      if (codeOffset < dataOffset + initialized + jumpBytes
          && dataOffset < (long) codeOffset + codeLength) throw bad("Overlapping sections");

      int memorySize = layout(dataLength, litLength, bssLength);

      int[] opcodes = new int[count + 1];
      int[] operands = new int[count + 1];
      int[] procedures = new int[count];
      decode(count, codeOffset, codeOffset + codeLength, opcodes, operands, procedures);
      opcodes[count] = Op.END;
      checkBranches(count, opcodes, operands, procedures);

      byte[] flags = new byte[count];
      int tableTargets = 0;
      if (version == 2) {
        int tableStart = (int) (dataOffset + initialized);
        for (int offset = 0; offset < jumpBytes; offset += 4) {
          int target = input.getInt(tableStart + offset);
          if (target < 0 || target >= count) throw bad("Invalid v2 jump-table target " + target);
          if ((flags[target] & FLAG_JUMP_TABLE_TARGET) == 0) tableTargets++;
          flags[target] |= FLAG_JUMP_TABLE_TARGET;
        }
        markDirectJumps(count, opcodes, operands, flags);
      }
      return new QvmImage(
          name,
          version,
          dataLength,
          litLength,
          bssLength,
          tableTargets,
          memorySize,
          programStackSize,
          opcodes,
          operands,
          procedures,
          flags,
          Arrays.copyOfRange(bytes, dataOffset, (int) (dataOffset + initialized)));
    }

    private int layout(int dataLength, int litLength, int bssLength) {
      long initialized = (long) dataLength + litLength;
      long total = initialized + bssLength;
      if (total > MAX_MEMORY_BYTES)
        throw bad("Data segment of " + total + " bytes exceeds " + MAX_MEMORY_BYTES);
      int memorySize = Integer.highestOneBit((int) Math.max(1, total));
      if (memorySize < total) memorySize <<= 1;
      long stackBottom = (long) memorySize - programStackSize;
      // q3asm reserves the stack inside BSS; otherwise everything must sit below the stack.
      long guestEnd = bssLength >= programStackSize ? total - programStackSize : total;
      if (stackBottom < 0 || guestEnd > stackBottom)
        throw bad(
            "Data, literals and BSS ("
                + total
                + " bytes) overlap the "
                + programStackSize
                + "-byte program stack at the top of the "
                + memorySize
                + "-byte data segment");
      return memorySize;
    }

    private void decode(
        int count, int cursor, int codeEnd, int[] opcodes, int[] operands, int[] procedures) {
      int procedure = -1;
      for (int pc = 0; pc < count; pc++) {
        if (cursor >= codeEnd) throw bad("Truncated instruction " + pc);
        int code = bytes[cursor++] & 0xff;
        Opcode opcode = Opcode.of(code);
        if (opcode == null) throw bad("Unknown opcode " + code + " at instruction " + pc);
        int width = opcode.operandBytes();
        if (cursor > codeEnd - width) throw bad("Truncated immediate at instruction " + pc);
        int operand = width == 4 ? input.getInt(cursor) : width == 1 ? bytes[cursor] & 0xff : 0;
        cursor += width;
        switch (opcode) {
          case ENTER, LEAVE -> {
            if (operand < 8 || (operand & 3) != 0 || operand > MAX_FRAME_BYTES)
              throw bad("Invalid frame size " + operand + " at instruction " + pc);
          }
          case ARG -> {
            if (operand < 8 || (operand & 3) != 0)
              throw bad("Invalid argument slot " + operand + " at instruction " + pc);
          }
          case LOCAL -> {
            // Negative offsets (below the frame) occur in shipped code (jbpow cgame); Q3 allows
            // them and every later access is masked, so only overflow-sized values are refused.
            if (operand < -MAX_MEMORY_BYTES || operand > MAX_MEMORY_BYTES)
              throw bad("Invalid local offset " + operand + " at instruction " + pc);
          }
          case BLOCK_COPY -> {
            if (operand < 0 || operand > MAX_MEMORY_BYTES)
              throw bad("Invalid block copy length " + operand + " at instruction " + pc);
          }
          default -> {
            if (opcode.conditionalBranch() && (operand < 0 || operand >= count))
              throw bad("Branch target " + operand + " outside code at instruction " + pc);
          }
        }
        if (opcode == Opcode.ENTER) procedure = pc;
        if (procedure < 0) throw bad("Code must begin with ENTER");
        opcodes[pc] = code;
        operands[pc] = operand;
        procedures[pc] = procedure;
      }
      if (codeEnd - cursor > 3) throw bad("Instruction count leaves excess code bytes");
      while (cursor < codeEnd) if (bytes[cursor++] != 0) throw bad("Nonzero code padding");
    }

    /** Conditional branches are checked here once, so the interpreter never re-checks them. */
    private void checkBranches(int count, int[] opcodes, int[] operands, int[] procedures) {
      for (int pc = 0; pc < count; pc++) {
        if (opcodes[pc] < Op.EQ || opcodes[pc] > Op.GEF) continue;
        int target = operands[pc];
        if (procedures[target] != procedures[pc] || opcodes[target] == Op.ENTER)
          throw bad("Conditional branch crosses a procedure boundary at instruction " + pc);
      }
    }

    /**
     * Marks {@code CONST x; JUMP} pairs whose JUMP is reachable only by falling through from the
     * CONST. Every instruction a transfer may land on is a conditional-branch target, a jump-table
     * target, the constant of some {@code CONST; JUMP} pair, a procedure entry or a return address
     * (which follows a CALL), so a JUMP outside that set always jumps to its constant.
     */
    private static void markDirectJumps(int count, int[] opcodes, int[] operands, byte[] flags) {
      boolean[] landing = new boolean[count];
      for (int pc = 0; pc < count; pc++) {
        int op = opcodes[pc];
        if (op >= Op.EQ && op <= Op.GEF) landing[operands[pc]] = true;
        if ((flags[pc] & FLAG_JUMP_TABLE_TARGET) != 0) landing[pc] = true;
        if (op == Op.JUMP && pc > 0 && opcodes[pc - 1] == Op.CONST) {
          int target = operands[pc - 1];
          if (target >= 0 && target < count) landing[target] = true;
        }
      }
      for (int pc = 1; pc < count; pc++)
        if (opcodes[pc] == Op.JUMP && opcodes[pc - 1] == Op.CONST && !landing[pc])
          flags[pc] |= FLAG_DIRECT_JUMP;
    }

    private void section(int offset, long length, int headerBytes, String what) {
      if (offset < headerBytes || !Ranges.contains(bytes.length, offset, length))
        throw bad("Invalid " + what + " section bounds");
    }

    private QvmFormatException bad(String message) {
      return new QvmFormatException(name, message);
    }
  }
}

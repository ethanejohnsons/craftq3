package dev.bluevista.craftq3.vm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

/** Independent QVM v1/v2 decoder. Offsets and lengths are checked in wide arithmetic first. */
public final class QvmReader {
  public static final int MAGIC = 0x12721444;
  public static final int MAGIC_V2 = 0x12721445;
  public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
  public static final int MAX_MEMORY_BYTES = 64 * 1024 * 1024;
  public static final int MAX_INSTRUCTIONS = 2_000_000;

  private QvmReader() {}

  public static QvmModule read(String name, byte[] bytes) throws QvmFormatException {
    if (name == null || name.isBlank() || name.length() > 255) throw bad("Invalid module name");
    if (bytes == null || bytes.length < 32 || bytes.length > MAX_FILE_BYTES)
      throw bad("Invalid QVM file size");
    ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int magic = input.getInt();
    if (magic != MAGIC && magic != MAGIC_V2)
      throw bad("Unsupported QVM magic (native modules are forbidden)");
    int headerBytes = magic == MAGIC ? 32 : 36;
    if (bytes.length < headerBytes) throw bad("Truncated QVM v2 header");
    int count = input.getInt(), codeOffset = input.getInt(), codeLength = input.getInt();
    int dataOffset = input.getInt(), dataLength = input.getInt(), litLength = input.getInt();
    int bssLength = input.getInt(), jumpBytes = magic == MAGIC_V2 ? input.getInt() : 0;
    if (count <= 0 || count > MAX_INSTRUCTIONS || codeLength < count)
      throw bad("Invalid instruction count/code length");
    if (dataLength < 0
        || litLength < 0
        || bssLength < 0
        || jumpBytes < 0
        || (dataLength & 3) != 0
        || (jumpBytes & 3) != 0) throw bad("Invalid data/literal/BSS/jump-table lengths");
    range(codeOffset, codeLength, bytes.length, headerBytes, "code");
    long initialized = (long) dataLength + litLength;
    range(dataOffset, initialized + jumpBytes, bytes.length, headerBytes, "data and jump table");
    if ((codeOffset & 3) != 0 || (dataOffset & 3) != 0)
      throw bad("QVM section offsets must be word-aligned");
    if (codeOffset < (long) dataOffset + initialized + jumpBytes
        && dataOffset < (long) codeOffset + codeLength) throw bad("Overlapping QVM sections");
    long requestedMemory = initialized + bssLength;
    if (requestedMemory < 128 || requestedMemory > MAX_MEMORY_BYTES)
      throw bad("QVM memory exceeds limits or has no stack space");
    int memorySize = 128;
    while (memorySize < requestedMemory) memorySize *= 2;
    ArrayList<QvmModule.Instruction> instructions = new ArrayList<>(count);
    int[] owners = new int[count];
    Opcode[] opcodes = Opcode.values();
    int function = -1;
    int cursor = codeOffset;
    int codeEnd = codeOffset + codeLength;
    for (int pc = 0; pc < count; pc++) {
      if (cursor >= codeEnd) throw bad("Truncated instruction " + pc);
      int fileOffset = cursor;
      int value = bytes[cursor++] & 255;
      if (value >= opcodes.length) throw bad("Unknown opcode " + value + " at instruction " + pc);
      Opcode opcode = opcodes[value];
      int width = opcode.operandBytes();
      if (cursor > codeEnd - width) throw bad("Truncated immediate at instruction " + pc);
      int operand = width == 4 ? input.getInt(cursor) : width == 1 ? bytes[cursor] & 255 : 0;
      cursor += width;
      if (opcode == Opcode.ENTER || opcode == Opcode.LEAVE) {
        if (operand < 8 || (operand & 3) != 0 || operand > 1024 * 1024)
          throw bad("Invalid frame size at instruction " + pc);
      }
      if (opcode == Opcode.ARG && (operand < 8 || (operand & 3) != 0))
        throw bad("Invalid argument slot at instruction " + pc);
      if ((opcode == Opcode.LOCAL || opcode == Opcode.BLOCK_COPY)
          && (operand < 0 || operand > MAX_MEMORY_BYTES))
        throw bad("Invalid memory immediate at instruction " + pc);
      if (opcode.conditionalBranch() && (operand < 0 || operand >= count))
        throw bad("Branch target outside code at instruction " + pc);
      if (opcode == Opcode.ENTER) function = pc;
      if (function < 0) throw bad("QVM entrypoint must begin with ENTER");
      owners[pc] = function;
      instructions.add(new QvmModule.Instruction(opcode, operand, fileOffset));
    }
    if (codeEnd - cursor > 3) throw bad("Instruction count leaves excess code bytes");
    while (cursor < codeEnd) if (bytes[cursor++] != 0) throw bad("Nonzero code alignment padding");
    for (int pc = 0; pc < count; pc++) {
      var instruction = instructions.get(pc);
      if (instruction.opcode().conditionalBranch()
          && (owners[instruction.operand()] != owners[pc]
              || instructions.get(instruction.operand()).opcode() == Opcode.ENTER)) {
        throw bad("Conditional branch crosses a procedure boundary at instruction " + pc);
      }
    }
    BitSet jumpTargets = new BitSet(count);
    int jumpStart = (int) (dataOffset + initialized);
    for (int offset = 0; offset < jumpBytes; offset += 4) {
      int target = input.getInt(jumpStart + offset);
      if (target < 0 || target >= count) throw bad("Invalid v2 jump-table target");
      jumpTargets.set(target);
    }
    return new QvmModule(
        name,
        new QvmModule.Header(
            magic == MAGIC ? 1 : 2,
            count,
            codeOffset,
            codeLength,
            dataOffset,
            dataLength,
            litLength,
            bssLength,
            jumpBytes),
        instructions,
        Arrays.copyOfRange(bytes, dataOffset, (int) (dataOffset + initialized)),
        memorySize,
        owners,
        jumpTargets);
  }

  private static void range(int offset, long length, int fileLength, int headerLength, String what)
      throws QvmFormatException {
    if (offset < headerLength || length < 0 || (long) offset + length > fileLength)
      throw bad("Invalid " + what + " section bounds");
  }

  private static QvmFormatException bad(String message) {
    return new QvmFormatException(message);
  }
}

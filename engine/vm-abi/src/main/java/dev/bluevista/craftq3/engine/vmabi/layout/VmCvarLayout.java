package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.console.Cvar;
import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code vmCvar_t}: the guest's cached view of a cvar. Identical in both profiles. */
public final class VmCvarLayout {
  public static final int HANDLE = 0;
  public static final int MODIFICATION_COUNT = 4;
  public static final int VALUE = 8;
  public static final int INTEGER = 12;
  public static final int STRING = 16;

  /** {@code MAX_CVAR_VALUE_STRING}. */
  public static final int STRING_BYTES = 256;

  public static final int BYTES = STRING + STRING_BYTES;

  private VmCvarLayout() {}

  /**
   * Copies a cvar into the guest struct ({@code Cvar_Update}). Values longer than {@link
   * #STRING_BYTES} - 1 are truncated (the original engine drops the map instead).
   *
   * @return true when the value had to be truncated
   */
  public static boolean write(QvmMemory memory, int address, Cvar cvar) {
    return write(
        memory,
        address,
        cvar.handle(),
        cvar.modificationCount(),
        cvar.value(),
        cvar.integer(),
        cvar.string());
  }

  public static boolean write(
      QvmMemory memory,
      int address,
      int handle,
      int modificationCount,
      float value,
      int integer,
      String string) {
    memory.resolve(address, BYTES);
    memory.writeInt(address + HANDLE, handle);
    memory.writeInt(address + MODIFICATION_COUNT, modificationCount);
    memory.writeFloat(address + VALUE, value);
    memory.writeInt(address + INTEGER, integer);
    int written = memory.writeString(address + STRING, string, STRING_BYTES);
    return written < string.length();
  }

  public static int handle(QvmMemory memory, int address) {
    return memory.readInt(address + HANDLE);
  }

  public static int modificationCount(QvmMemory memory, int address) {
    return memory.readInt(address + MODIFICATION_COUNT);
  }

  public static String string(QvmMemory memory, int address) {
    return VmStructs.readChars(memory, address + STRING, STRING_BYTES);
  }
}

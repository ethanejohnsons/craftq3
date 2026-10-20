package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** Reading and writing guest C strings with the bounds the services use. */
public final class GuestText {
  /**
   * Longest guest string a service reads (including the terminator): larger than any engine buffer
   * ({@code BIG_INFO_STRING} is 8192), small enough to stop runaway reads.
   */
  public static final int MAX_STRING = 64 * 1024;

  /** {@code MAX_QPATH}-style paths are bounded by the file system; this caps the read. */
  public static final int MAX_PATH = 1024;

  private GuestText() {}

  /** A NUL-terminated string; null pointers read as empty, like most engine paths treat them. */
  public static String read(QvmMemory memory, int address) {
    return address == 0 ? "" : memory.readString(address, MAX_STRING);
  }

  public static String readPath(QvmMemory memory, int address) {
    return address == 0 ? "" : memory.readString(address, MAX_PATH);
  }

  /**
   * {@code Q_strncpyz}: writes {@code text} truncated to {@code size - 1} bytes plus a NUL. A
   * non-positive size or null buffer writes nothing.
   */
  public static void write(QvmMemory memory, int address, String text, int size) {
    if (address == 0 || size <= 0) return;
    memory.writeString(address, text, size);
  }
}

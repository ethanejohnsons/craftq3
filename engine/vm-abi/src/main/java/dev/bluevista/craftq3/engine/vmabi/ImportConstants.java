package dev.bluevista.craftq3.engine.vmabi;

/** Documented neutral values shared by several import tables. */
public final class ImportConstants {
  /**
   * Reported by {@code trap_MemoryRemaining}: 32 MiB, more than any shipped module checks for, so
   * no low-memory warning or fallback path is taken.
   */
  public static final int MEMORY_REMAINING = 32 * 1024 * 1024;

  /** {@code trap_VerifyCDKey} result: accepted, as ioquake3 builds without key checks report. */
  public static final int CD_KEY_VALID = 1;

  private ImportConstants() {}
}

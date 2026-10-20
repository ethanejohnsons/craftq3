package dev.bluevista.craftq3.engine.vmabi;

/**
 * One trap a QVM may call: implemented by the enums {@link QagameImport}, {@link BotlibImport},
 * {@link CgameImport}, {@link UiImport} (the 1.32 numbering) and {@link RetailImport} (imports that
 * exist only in the retail numbering). A guest {@code CALL} to address {@code -1 - n} is trap
 * {@code n}; {@link ImportTable} maps a profile's numbers onto these entries.
 */
public interface VmImport {
  /** Argument count is not known (only for obsolete retail traps). */
  int UNKNOWN_ARGS = -1;

  /** The enum constant name, e.g. {@code G_PRINT}. */
  String name();

  VmModule module();

  /** The trap number in the 1.32 interface, or -1 when the import exists only in retail. */
  int number();

  /** Argument words the trap reads, or {@link #UNKNOWN_ARGS}. */
  int argCount();

  Policy policy();
}

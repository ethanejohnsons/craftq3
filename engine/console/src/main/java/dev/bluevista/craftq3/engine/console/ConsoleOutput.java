package dev.bluevista.craftq3.engine.console;

/** Where console text goes ({@code Com_Printf}). Each call is one line without its newline. */
@FunctionalInterface
public interface ConsoleOutput {
  /** Drops everything; for tables that have no visible console. */
  ConsoleOutput DISCARD = line -> {};

  void print(String line);
}

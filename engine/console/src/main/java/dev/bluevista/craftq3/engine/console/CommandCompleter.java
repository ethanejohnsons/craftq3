package dev.bluevista.craftq3.engine.console;

import java.util.Collection;

/** Supplies tab-completion candidates for one argument of a command. */
@FunctionalInterface
public interface CommandCompleter {
  /**
   * Candidates for argument {@code argument} (1 or more) of {@code line}. The registry filters them
   * by the partial text and sorts them, so implementations may return every possibility.
   */
  Collection<String> candidates(CommandArgs line, int argument);
}

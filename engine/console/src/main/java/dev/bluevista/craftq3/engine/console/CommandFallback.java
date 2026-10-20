package dev.bluevista.craftq3.engine.console;

/**
 * Receives commands that are neither registered commands nor cvars, in registration order: a VM
 * console-command hook ({@code CG_CONSOLE_COMMAND}, {@code GAME_CONSOLE_COMMAND}) or forwarding to
 * the server.
 */
@FunctionalInterface
public interface CommandFallback {
  /** Returns true when the command was handled; the next fallback is not consulted. */
  boolean handle(CommandArgs args);
}

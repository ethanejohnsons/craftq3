package dev.bluevista.craftq3.engine.game.bot;

import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;

/**
 * Binds the qagame botlib imports ({@code BotlibImport}, numbers 200 and up) for one game module. A
 * server owns one instance for its lifetime; botlib state survives map changes as the original
 * library does.
 */
public interface BotSupport extends AutoCloseable {
  /** Binds every botlib import for a module of {@code profile} served by {@code host}. */
  void bind(Bindings bindings, AbiProfile profile, BotHost host);

  /** Bot {@code client} left; forget its per-client state. */
  void clientFreed(int client);

  /** A new level is starting (the game will call {@code BotLibLoadMap}). */
  default void levelStarted() {}

  @Override
  void close();
}

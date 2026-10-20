package dev.bluevista.craftq3.engine.game.bot;

import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.BotlibImport;

/**
 * A server without botlib: {@code BOTLIB_SETUP} reports that the library is not set up, so the game
 * disables its bot code, and every other botlib import fails (and therefore degrades) as calling an
 * absent library would. Under {@code vm_strictSyscalls 1} such a call faults the module.
 */
public final class NoBotlib implements BotSupport {
  @Override
  public void bind(Bindings bindings, AbiProfile profile, BotHost host) {
    for (BotlibImport entry : BotlibImport.values()) {
      if (!entry.policy().requiresHandler()) continue;
      if (entry == BotlibImport.BOTLIB_SETUP)
        bindings.bind(entry, (vm, args) -> BotlibImport.BLERR_LIBRARY_NOT_SETUP);
      else
        bindings.bind(
            entry,
            (vm, args) -> {
              throw new UnsupportedOperationException("botlib is not available: " + entry.name());
            });
    }
  }

  @Override
  public void clientFreed(int client) {}

  @Override
  public void close() {}
}

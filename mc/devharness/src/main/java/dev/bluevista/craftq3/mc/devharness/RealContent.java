package dev.bluevista.craftq3.mc.devharness;

import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.server.Q3Content;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The maintainer's installation as the server mounted it, for the real-data GameTests. Empty (the
 * test passes as skipped) when {@code CRAFTQ3_Q3ROOT} is not set; never committed, see PORTING.
 */
final class RealContent {
  private static final Logger LOGGER = LoggerFactory.getLogger(RealContent.class);

  private RealContent() {}

  static Optional<Q3Content> mount() {
    String root = System.getenv("CRAFTQ3_Q3ROOT");
    if (root == null || root.isBlank()) {
      LOGGER.warn("CRAFTQ3_Q3ROOT is not set; skipping a real-data GameTest");
      return Optional.empty();
    }
    // Blocks this tick for the first mount (seconds); later tests reuse it.
    return Optional.of(CraftQ3Mod.instance().services().content().content().join());
  }
}

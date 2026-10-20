package dev.bluevista.craftq3.mc.fabric;

import net.fabricmc.api.ModInitializer;

/**
 * Common entry point, loaded on both the client and a dedicated server. Server-side simulation,
 * payloads and the building dimension are registered here; nothing here may touch client classes.
 */
public final class CraftQ3Mod implements ModInitializer {
  public static final String MOD_ID = "craftq3";

  private boolean initialized;

  @Override
  public void onInitialize() {
    initialized = true;
  }

  /** Whether {@link #onInitialize()} has run on this instance (checked by devharness). */
  public boolean initialized() {
    return initialized;
  }
}

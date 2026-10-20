package dev.bluevista.craftq3.mc.fabric.client.audio;

/**
 * Per-engine CraftQ3 state that {@code SoundEngineMixin} adds to {@code SoundEngine}; cast the
 * engine to this interface. It is instance state, so nothing here is static.
 */
public interface SoundEngineHooks {
  /** While true, {@code SoundEngine.play} starts nothing: vanilla sounds are suppressed. */
  void craftq3$setVanillaMuted(boolean muted);

  boolean craftq3$vanillaMuted();

  /**
   * Installs the listener for engine lifecycle events, replacing any previous one (null clears).
   */
  void craftq3$setLifecycle(SoundEngineLifecycle lifecycle);
}

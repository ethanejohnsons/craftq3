package dev.bluevista.craftq3.mc.fabric.client.audio;

/**
 * Lifecycle callbacks from {@code SoundEngineMixin}, all on the thread that drives the sound engine
 * (the render thread).
 */
public interface SoundEngineLifecycle {
  /** {@code SoundEngine.destroy} is starting: the OpenAL context is about to be destroyed. */
  void beforeDestroy();

  /**
   * Inside {@code SoundEngine.stopAll}, after the sound executor thread has been joined and before
   * a new one starts. Nothing else touches OpenAL at this point, so the callee may release sources
   * and buffers directly. Tasks queued on the old executor have been dropped.
   */
  void executorStopped();

  /** {@code SoundEngine.loadLibrary} returned; {@code loaded} says whether OpenAL is up. */
  void libraryLoaded(boolean loaded);

  /** {@code SoundEngine.emergencyShutdown}: OpenAL is about to vanish without a quiescent point. */
  void emergencyShutdown();
}

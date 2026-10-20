/**
 * Quake III sound semantics (channels, loops, attenuation, streams) over an audio device SPI.
 *
 * <p>{@link dev.bluevista.craftq3.engine.audio.SoundSystem} implements the behaviour the cgame and
 * ui modules expect from the engine's sound imports; an {@link
 * dev.bluevista.craftq3.engine.audio.AudioDevice} plays the result. The Minecraft mod implements
 * the device over Minecraft's OpenAL sound engine; {@link
 * dev.bluevista.craftq3.engine.audio.NullAudioDevice} serves headless runs and {@link
 * dev.bluevista.craftq3.engine.audio.SoftwareMixer} mixes to 16-bit stereo PCM in pure Java.
 *
 * <p>All positions are Quake III world coordinates (x forward, y left, z up, in game units).
 */
package dev.bluevista.craftq3.engine.audio;

/**
 * Quake III without a window, for CI and the compatibility sweep.
 *
 * <ul>
 *   <li>{@link dev.bluevista.craftq3.engine.headless.HeadlessServer}: the dedicated server main
 *       ({@code ./gradlew :engine:headless:run --args='+map q3dm17'}), UDP on port 27960 with rcon,
 *       the console on standard input.
 *   <li>{@link dev.bluevista.craftq3.engine.headless.HeadlessClient}: a scripted session with a
 *       {@link dev.bluevista.craftq3.engine.headless.RecordingRenderSink} and a null audio device,
 *       on a manual clock (reproducible, as fast as the CPU allows) or in real time.
 *   <li>{@link dev.bluevista.craftq3.engine.headless.CompatSweep}: the plan's §4 sweep over every
 *       game directory of {@code $CRAFTQ3_Q3ROOT} ({@code ./gradlew :engine:headless:compatSweep}),
 *       writing {@code build/compat/report.json} and {@code report.md}.
 * </ul>
 */
package dev.bluevista.craftq3.engine.headless;

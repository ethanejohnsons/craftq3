package dev.bluevista.craftq3.engine.session;

import java.util.List;
import java.util.Objects;

/**
 * How an {@link EngineSession} runs.
 *
 * @param seed seeds the server, the connection nonces and the qport; 0 draws one from the clock
 * @param synchronousServer steps the local server on the client thread inside {@link
 *     EngineSession#frame} instead of on the {@code cq3-sim} thread; with a manual clock and a
 *     fixed seed a scripted run is reproducible
 * @param commandLine startup commands ({@link CommandLine#parse}); {@code set} lines apply before
 *     and after the configs, the rest run once the configs ran
 * @param safeMode skips {@code q3config.cfg} (the original {@code +set safe})
 * @param decodeImages decodes textures for the frames' assets (false for headless runs)
 * @param attract with no startup commands, plays {@code idlogo} and then {@code intro} once ({@code
 *     com_introplayed}), as the original does
 * @param netEnabled the default of {@code net_enabled}: whether {@code map} also listens on UDP
 * @param writeConfig writes {@code q3config.cfg} when the session closes cleanly
 * @param servers creates the local server; null when this build has none
 */
public record SessionOptions(
    long seed,
    boolean synchronousServer,
    List<String> commandLine,
    boolean safeMode,
    boolean decodeImages,
    boolean attract,
    boolean netEnabled,
    boolean writeConfig,
    LocalServer.Factory servers) {
  public SessionOptions {
    commandLine = List.copyOf(commandLine);
  }

  /** Interactive defaults: threaded server, images decoded, configs written, UDP hosting on. */
  public static SessionOptions defaults(LocalServer.Factory servers) {
    return new SessionOptions(0, false, List.of(), false, true, false, true, true, servers);
  }

  /**
   * Reproducible test runs: fixed seed, synchronous server stepping, no images, no hosting unless
   * asked, no config written.
   */
  public static SessionOptions deterministic(long seed, LocalServer.Factory servers) {
    return new SessionOptions(seed, true, List.of(), false, false, false, false, false, servers);
  }

  public SessionOptions withSeed(long value) {
    return new SessionOptions(
        value,
        synchronousServer,
        commandLine,
        safeMode,
        decodeImages,
        attract,
        netEnabled,
        writeConfig,
        servers);
  }

  public SessionOptions withSynchronousServer(boolean value) {
    return new SessionOptions(
        seed,
        value,
        commandLine,
        safeMode,
        decodeImages,
        attract,
        netEnabled,
        writeConfig,
        servers);
  }

  public SessionOptions withCommandLine(List<String> value) {
    return new SessionOptions(
        seed,
        synchronousServer,
        Objects.requireNonNull(value),
        safeMode,
        decodeImages,
        attract,
        netEnabled,
        writeConfig,
        servers);
  }

  public SessionOptions withSafeMode(boolean value) {
    return new SessionOptions(
        seed,
        synchronousServer,
        commandLine,
        value,
        decodeImages,
        attract,
        netEnabled,
        writeConfig,
        servers);
  }

  public SessionOptions withDecodeImages(boolean value) {
    return new SessionOptions(
        seed,
        synchronousServer,
        commandLine,
        safeMode,
        value,
        attract,
        netEnabled,
        writeConfig,
        servers);
  }

  public SessionOptions withAttract(boolean value) {
    return new SessionOptions(
        seed,
        synchronousServer,
        commandLine,
        safeMode,
        decodeImages,
        value,
        netEnabled,
        writeConfig,
        servers);
  }

  public SessionOptions withNetEnabled(boolean value) {
    return new SessionOptions(
        seed,
        synchronousServer,
        commandLine,
        safeMode,
        decodeImages,
        attract,
        value,
        writeConfig,
        servers);
  }

  public SessionOptions withWriteConfig(boolean value) {
    return new SessionOptions(
        seed,
        synchronousServer,
        commandLine,
        safeMode,
        decodeImages,
        attract,
        netEnabled,
        value,
        servers);
  }

  public SessionOptions withServers(LocalServer.Factory value) {
    return new SessionOptions(
        seed,
        synchronousServer,
        commandLine,
        safeMode,
        decodeImages,
        attract,
        netEnabled,
        writeConfig,
        value);
  }
}

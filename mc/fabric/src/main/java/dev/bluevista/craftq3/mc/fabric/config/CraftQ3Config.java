package dev.bluevista.craftq3.mc.fabric.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's settings, kept in {@code <config>/craftq3/craftq3.properties} (UTF-8), where {@code
 * <config>} is the loader's config directory ({@code config/} in the game or server directory).
 *
 * <ul>
 *   <li>{@code installation}: the Quake III installation, meaning the directory that contains
 *       {@code baseq3}. A relative path resolves against {@code <config>}. Empty means not set.
 *   <li>{@code game}: the game directory to mount on top of {@code baseq3}; default {@code baseq3}.
 * </ul>
 *
 * <p>The system properties {@code craftq3.installation} and {@code craftq3.game} override the file
 * for one run (for example from a launcher or a dev run configuration) and are never saved. This
 * package is the only place the mod reads system properties.
 *
 * <p>Instances are immutable. {@link #load} never throws: a missing or unreadable file gives the
 * defaults with a warning, so a broken config can never stop Minecraft from loading a world.
 */
public final class CraftQ3Config {
  public static final String DIRECTORY = "craftq3";
  public static final String FILE_NAME = "craftq3.properties";
  public static final String KEY_INSTALLATION = "installation";
  public static final String KEY_GAME = "game";
  public static final String DEFAULT_GAME = "baseq3";
  public static final String PROPERTY_INSTALLATION = "craftq3.installation";
  public static final String PROPERTY_GAME = "craftq3.game";

  private static final Logger LOGGER = LoggerFactory.getLogger(CraftQ3Config.class);

  private final Path configDirectory;
  private final String installation;
  private final String game;
  private final String installationOverride;
  private final String gameOverride;

  private CraftQ3Config(
      Path configDirectory,
      String installation,
      String game,
      String installationOverride,
      String gameOverride) {
    this.configDirectory = Objects.requireNonNull(configDirectory, "configDirectory");
    this.installation = installation == null ? "" : installation.strip();
    this.game = game == null || game.isBlank() ? DEFAULT_GAME : game.strip();
    this.installationOverride = blankToNull(installationOverride);
    this.gameOverride = blankToNull(gameOverride);
  }

  /** The defaults, without any file or override. */
  public static CraftQ3Config defaults(Path configDirectory) {
    return new CraftQ3Config(configDirectory, "", DEFAULT_GAME, null, null);
  }

  /**
   * Reads the file under {@code configDirectory} (the loader's config directory) and the system
   * property overrides. Never throws.
   */
  public static CraftQ3Config load(Path configDirectory) {
    Properties properties = new Properties();
    Path file = file(configDirectory);
    if (Files.isRegularFile(file)) {
      try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        properties.load(reader);
      } catch (IOException | RuntimeException e) {
        LOGGER.warn("CraftQ3: cannot read {}; using defaults", file, e);
        properties.clear();
      }
    }
    return new CraftQ3Config(
        configDirectory,
        properties.getProperty(KEY_INSTALLATION, ""),
        properties.getProperty(KEY_GAME, DEFAULT_GAME),
        System.getProperty(PROPERTY_INSTALLATION),
        System.getProperty(PROPERTY_GAME));
  }

  /** The properties file under {@code configDirectory}. */
  public static Path file(Path configDirectory) {
    return configDirectory.resolve(DIRECTORY).resolve(FILE_NAME);
  }

  /** Writes the stored values (never the overrides) atomically where the file system allows. */
  public void save() throws IOException {
    Path file = file(configDirectory);
    Files.createDirectories(file.getParent());
    Properties properties = new Properties();
    properties.setProperty(KEY_INSTALLATION, installation);
    properties.setProperty(KEY_GAME, game);
    Path temp = Files.createTempFile(file.getParent(), FILE_NAME, ".tmp");
    try {
      try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        properties.store(
            writer,
            " CraftQ3. installation = the directory containing baseq3 (relative paths resolve"
                + " against the config directory); game = mod directory, default baseq3");
      }
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** A copy with a new stored installation (as typed: relative paths stay relative). */
  public CraftQ3Config withInstallation(String newInstallation) {
    return new CraftQ3Config(
        configDirectory, newInstallation, game, installationOverride, gameOverride);
  }

  /** A copy with a new stored game directory. */
  public CraftQ3Config withGame(String newGame) {
    return new CraftQ3Config(
        configDirectory, installation, newGame, installationOverride, gameOverride);
  }

  public Path configDirectory() {
    return configDirectory;
  }

  /** The installation as stored in the file, possibly relative or empty. */
  public String storedInstallation() {
    return installation;
  }

  /** The game as stored in the file. */
  public String storedGame() {
    return game;
  }

  /** The installation in effect: the override if set, else the stored value. */
  public String installation() {
    return installationOverride != null ? installationOverride : installation;
  }

  /** The game directory in effect. */
  public String game() {
    return gameOverride != null ? gameOverride : game;
  }

  /** True when a system property overrides the stored installation or game. */
  public boolean overridden() {
    return installationOverride != null || gameOverride != null;
  }

  /** The effective installation as an absolute path, or empty when unset or not a valid path. */
  public Optional<Path> installationPath() {
    return resolve(installation());
  }

  /** Resolves {@code value} as an installation path the way the config does. */
  public Optional<Path> resolve(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    try {
      return Optional.of(configDirectory.resolve(value.strip()).toAbsolutePath().normalize());
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  @Override
  public String toString() {
    return "CraftQ3Config[installation=" + installation() + ", game=" + game() + "]";
  }
}

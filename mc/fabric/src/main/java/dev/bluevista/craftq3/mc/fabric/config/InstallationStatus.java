package dev.bluevista.craftq3.mc.fabric.config;

import dev.bluevista.craftq3.engine.fs.Mount;
import dev.bluevista.craftq3.engine.fs.MountReport;
import dev.bluevista.craftq3.engine.fs.PackMount;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of mounting an installation with {@link SearchPath#mount}: how many archives each game
 * directory contributed and what the mount skipped ({@link MountReport}). Probing never throws and
 * never keeps archives open.
 *
 * @param root the installation probed, or null when none is configured
 * @param game the game directory mounted on top of {@code baseq3}
 * @param mounted whether the search path could be built
 * @param baseArchives PK3s mounted from {@code baseq3}
 * @param gameArchives PK3s mounted from {@code game} (0 for {@code baseq3})
 * @param directories loose directories mounted
 * @param skipped archives the mount left out, with reasons
 * @param skippedEntries archive members the mount left out
 * @param error why the mount failed, or null
 */
public record InstallationStatus(
    Path root,
    String game,
    boolean mounted,
    int baseArchives,
    int gameArchives,
    int directories,
    List<MountReport.SkippedArchive> skipped,
    int skippedEntries,
    String error) {
  public InstallationStatus {
    skipped = List.copyOf(skipped);
  }

  /** No installation configured. */
  public static InstallationStatus unconfigured(String game) {
    return new InstallationStatus(
        null, game, false, 0, 0, 0, List.of(), 0, "no installation configured");
  }

  /** Total PK3s mounted. */
  public int archives() {
    return baseArchives + gameArchives;
  }

  /** Mounted and at least one PK3 found: maps and QVMs can load. */
  public boolean usable() {
    return mounted && archives() > 0;
  }

  /**
   * Mounts {@code game} from {@code root}. When {@code root} is itself a {@code baseq3} directory
   * without a nested {@code baseq3}, its parent is probed instead (see {@link #root()}).
   */
  public static InstallationStatus probe(Path root, String game) {
    Objects.requireNonNull(root, "root");
    String effectiveGame = game == null || game.isBlank() ? SearchPath.BASE_GAME : game;
    Path probed = installationRoot(root);
    if (!Files.isDirectory(probed))
      return failure(probed, effectiveGame, "not a directory: " + probed);
    try (SearchPath path = SearchPath.mount(probed, effectiveGame)) {
      int base = 0;
      int mod = 0;
      for (PackMount pack : path.packs()) {
        if (pack.game().equals(SearchPath.BASE_GAME)) base++;
        else mod++;
      }
      int directories = 0;
      for (Mount mount : path.mounts()) if (!(mount instanceof PackMount)) directories++;
      MountReport report = path.report();
      return new InstallationStatus(
          probed,
          effectiveGame,
          true,
          base,
          mod,
          directories,
          report.skippedArchives(),
          report.skippedEntries(),
          null);
    } catch (NoSuchFileException e) {
      return failure(probed, effectiveGame, "missing game directory: " + e.getFile());
    } catch (IOException | RuntimeException e) {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return failure(probed, effectiveGame, message);
    }
  }

  /** {@code path}, or its parent when {@code path} is a {@code baseq3} directory itself. */
  public static Path installationRoot(Path path) {
    Path name = path.getFileName();
    if (name != null
        && name.toString().toLowerCase(Locale.ROOT).equals(SearchPath.BASE_GAME)
        && !Files.isDirectory(path.resolve(SearchPath.BASE_GAME))
        && path.getParent() != null) return path.getParent();
    return path;
  }

  /** The probed root, if any. */
  public Optional<Path> rootPath() {
    return Optional.ofNullable(root);
  }

  /** One line for chat and logs. */
  public String summary() {
    if (root == null) return "No Quake III installation configured; use /q3 path <dir>";
    if (!mounted) return "Cannot mount " + root + " (" + game + "): " + error;
    StringBuilder text = new StringBuilder();
    text.append(root).append(": ").append(baseArchives).append(" PK3s in baseq3");
    if (!game.equalsIgnoreCase(SearchPath.BASE_GAME))
      text.append(", ").append(gameArchives).append(" in ").append(game);
    if (!skipped.isEmpty()) text.append(", ").append(skipped.size()).append(" skipped");
    if (skippedEntries > 0) text.append(", ").append(skippedEntries).append(" bad entries");
    if (archives() == 0) text.append(" (no PK3s: maps and game code will not load)");
    return text.toString();
  }

  private static InstallationStatus failure(Path root, String game, String error) {
    return new InstallationStatus(root, game, false, 0, 0, 0, List.of(), 0, error);
  }
}

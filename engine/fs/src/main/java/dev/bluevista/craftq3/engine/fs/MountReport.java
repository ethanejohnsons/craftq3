package dev.bluevista.craftq3.engine.fs;

import java.nio.file.Path;
import java.util.List;

/**
 * What a search-path build skipped. Skipped archives (empty, unreadable or malformed PK3s) never
 * fail the mount; each is logged once.
 */
public record MountReport(List<SkippedArchive> skippedArchives, int skippedEntries) {
  /** An archive left out of the search path, and why. */
  public record SkippedArchive(String game, Path file, String reason) {}

  public MountReport {
    skippedArchives = List.copyOf(skippedArchives);
  }
}

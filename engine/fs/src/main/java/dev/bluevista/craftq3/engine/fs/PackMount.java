package dev.bluevista.craftq3.engine.fs;

import java.nio.file.Path;

/**
 * A mounted PK3. {@link #name()} is the pack's base name without {@code .pk3}, as servers announce
 * it in {@code sv_pakNames}; for a download-cache archive it is the server's name, not the cache
 * file name.
 */
public final class PackMount implements Mount {
  private final int index;
  private final String game;
  private final String name;
  private final Pk3Archive archive;
  private final boolean cached;

  PackMount(int index, String game, String name, Pk3Archive archive, boolean cached) {
    this.index = index;
    this.game = game;
    this.name = name;
    this.archive = archive;
    this.cached = cached;
  }

  @Override
  public int index() {
    return index;
  }

  @Override
  public String game() {
    return game;
  }

  @Override
  public Path location() {
    return archive.file();
  }

  /** Base name without extension, original case. */
  public String name() {
    return name;
  }

  public Pk3Archive archive() {
    return archive;
  }

  /** The original engine's pack checksum. */
  public int checksum() {
    return archive.checksum();
  }

  /** True for an archive adopted from the download cache. */
  public boolean cached() {
    return cached;
  }

  @Override
  public String toString() {
    return game + "/" + name + ".pk3";
  }
}

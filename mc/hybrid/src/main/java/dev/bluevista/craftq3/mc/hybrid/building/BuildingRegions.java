package dev.bluevista.craftq3.mc.hybrid.building;

import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Placing BSPs into building regions and loading saved regions. Loading never throws for missing or
 * changed game data: such a region comes back {@link Disabled} with a reason, the save keeps its
 * blocks, and restoring the PK3 enables the region again.
 */
public final class BuildingRegions {
  private BuildingRegions() {}

  /**
   * How regions are laid out in the building dimension: slot {@code n}'s Quake origin is at
   * Minecraft {@code (n * spacing, originY, 0)}.
   *
   * @param spacing blocks between region origins along X
   * @param minY the dimension's lowest block Y
   * @param maxY one past its highest block Y
   * @param unitsPerBlock the scale
   */
  public record Layout(int spacing, int minY, int maxY, int unitsPerBlock) {
    /** 4096-block spacing, a -64..320 dimension, 32 units per block. */
    public static final Layout DEFAULT = new Layout(4096, -64, 320, 32);

    public Layout {
      if (spacing < 64 || spacing > 1 << 20) throw new IllegalArgumentException("Bad spacing");
      if (minY >= maxY) throw new IllegalArgumentException("Bad vertical range");
      if (unitsPerBlock < 1 || unitsPerBlock > 4096)
        throw new IllegalArgumentException("Bad scale");
    }

    /** The slot whose region contains Minecraft X {@code x}, or -1. */
    public int slotAt(double x) {
      double slot = Math.floor((x + spacing / 2.0) / spacing);
      return slot < 0 || slot >= BuildingRegion.MAX_SLOTS ? -1 : (int) slot;
    }
  }

  /** Reads a game's files: the map's bytes, or empty when the file does not exist. */
  @FunctionalInterface
  public interface MapFiles {
    Optional<byte[]> read(String game, String map) throws IOException;
  }

  /** A loaded region's state. */
  public sealed interface Status permits Enabled, Disabled {
    BuildingRegion region();
  }

  /** A region whose BSP was found and matches. */
  public record Enabled(BuildingRegion region, BspMap map, BuildingQueries queries)
      implements Status {}

  /** A region that cannot run: its blocks stay saved, BSP rules are off, and why. */
  public record Disabled(BuildingRegion region, String reason) implements Status {}

  /** The lowercase hex SHA-256 of {@code data}. */
  public static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * The region for a BSP: an existing one with the same game, map and SHA-256 (so returning to a
   * map reuses its blocks), otherwise a new one in the first free slot.
   *
   * @throws IllegalArgumentException when the map is malformed, too wide or too tall for the
   *     layout, or every slot is taken
   */
  public static BuildingRegion place(
      List<BuildingRegion> existing, String game, String map, byte[] bsp, Layout layout) {
    Objects.requireNonNull(layout, "layout");
    String sha = sha256(bsp);
    for (BuildingRegion r : existing)
      if (r.sha256().equals(sha) && r.game().equals(game) && r.map().equals(map)) return r;
    BspMap parsed;
    try {
      parsed = BspReader.read(bsp, game + "/" + map);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Malformed map " + map + ": " + malformed.getMessage());
    }
    if (parsed.models().isEmpty()) throw new IllegalArgumentException("Map has no world model");
    BspMap.Model world = parsed.models().getFirst();
    double s = layout.unitsPerBlock();
    double half = layout.spacing() / 2.0 - 16;
    for (int axis = 0; axis < 2; axis++)
      if (Math.abs(world.mins().get(axis)) / s > half
          || Math.abs(world.maxs().get(axis)) / s > half)
        throw new IllegalArgumentException("Map " + map + " is wider than a region");
    double bottom = Math.floor(world.mins().z() / s);
    double top = Math.ceil(world.maxs().z() / s);
    int available = layout.maxY() - layout.minY();
    if (top - bottom + 4 > available)
      throw new IllegalArgumentException("Map " + map + " is taller than the dimension");
    // Two blocks of margin below the map; the map's bottom lands at minY + 2.
    int originY = (int) (layout.minY() + 2 - bottom);
    BitSet used = new BitSet();
    for (BuildingRegion r : existing) used.set(r.slot());
    int slot = used.nextClearBit(0);
    if (slot >= BuildingRegion.MAX_SLOTS) throw new IllegalArgumentException("No free region");
    return new BuildingRegion(
        slot, game, map, sha, slot * layout.spacing(), originY, 0, layout.unitsPerBlock());
  }

  /** Loads one region, never throwing for missing, unreadable, changed or malformed data. */
  public static Status load(BuildingRegion region, MapFiles files) {
    Objects.requireNonNull(region, "region");
    byte[] bytes;
    try {
      Optional<byte[]> read = files.read(region.game(), region.map());
      if (read.isEmpty())
        return new Disabled(
            region, region.map() + " is not in game " + region.game() + " (missing PK3?)");
      bytes = read.get();
    } catch (IOException | RuntimeException failure) {
      return new Disabled(region, "Cannot read " + region.map() + ": " + failure.getMessage());
    }
    String sha = sha256(bytes);
    if (!sha.equals(region.sha256()))
      return new Disabled(
          region,
          region.map()
              + " in "
              + region.game()
              + " differs from the BSP this region was built on (SHA-256 "
              + sha.substring(0, 12)
              + " instead of "
              + region.sha256().substring(0, 12)
              + ")");
    try {
      BspMap map = BspReader.read(bytes, region.game() + "/" + region.map());
      return new Enabled(region, map, BuildingQueries.build(map, region.transform()));
    } catch (RuntimeException malformed) {
      return new Disabled(region, "Cannot load " + region.map() + ": " + malformed.getMessage());
    }
  }

  /** Loads every region in slot order. */
  public static List<Status> loadAll(List<BuildingRegion> regions, MapFiles files) {
    List<Status> out = new ArrayList<>(regions.size());
    for (BuildingRegion region : regions) out.add(load(region, files));
    return List.copyOf(out);
  }
}

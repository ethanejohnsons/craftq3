package dev.bluevista.craftq3.fabric.building;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** World-local durable map slots. The key is the exact BSP SHA-256; blocks remain normal chunks. */
public final class BuildSpaceIndex {
  private BuildSpaceIndex() {}

  /**
   * Each immutable map is centered in a 4,096-block region, including slot zero's negative half.
   */
  public static int region(double x) {
    if (!Double.isFinite(x)) return -1;
    double slot = Math.floor((x + 2048) / 4096);
    return slot < 0 || slot >= 4096 ? -1 : (int) slot;
  }

  public static synchronized int slot(Path path, String hash) throws IOException {
    if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid BSP identity");
    var saved = entries(path);
    var entries = new Properties();
    saved.forEach((key, value) -> entries.setProperty(key, value.toString()));
    var used = new BitSet();
    saved.values().forEach(used::set);
    if (entries.containsKey(hash)) return Integer.parseInt(entries.getProperty(hash));
    int slot = used.nextClearBit(0);
    if (slot >= 4096) throw new IOException("Build map slot capacity reached");
    entries.setProperty(hash, Integer.toString(slot));
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "build-index-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temp)) {
        entries.store(writer, "CraftQ3 immutable BSP identities; blocks are saved by Minecraft");
      }
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
    return slot;
  }

  public static synchronized Map<String, Integer> entries(Path path) throws IOException {
    var entries = new Properties();
    if (Files.exists(path) && Files.size(path) > 524288)
      throw new IOException("Oversized build index");
    if (Files.exists(path))
      try (var reader = Files.newBufferedReader(path)) {
        try {
          entries.load(reader);
        } catch (IllegalArgumentException e) {
          throw new IOException("Invalid build index properties", e);
        }
      }
    var used = new BitSet();
    var result = new HashMap<String, Integer>();
    for (String key : entries.stringPropertyNames()) {
      if (!key.matches("[0-9a-f]{64}")) throw new IOException("Invalid saved BSP identity");
      int slot;
      try {
        slot = Integer.parseInt(entries.getProperty(key));
      } catch (NumberFormatException e) {
        throw new IOException("Invalid saved build slot", e);
      }
      if (slot < 0 || slot >= 4096 || used.get(slot))
        throw new IOException("Duplicate or out-of-range build slot");
      used.set(slot);
      result.put(key, slot);
    }
    return Map.copyOf(result);
  }
}

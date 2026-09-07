package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** World-local source descriptors; only identities and coordinates, never original asset bytes. */
public final class BuildManifest {
  public record Entry(String hash, int slot, String game, String map, double originY) {
    public Entry {
      if (!hash.matches("[0-9a-f]{64}")
          || slot < 0
          || slot >= 4096
          || !Double.isFinite(originY)
          || Math.abs(originY) > 20_000_000)
        throw new IllegalArgumentException("Invalid saved BSP region");
      game = VirtualPath.gameDirectory(game);
      map = new VirtualPath(map).value();
      if (!map.startsWith("maps/") || !map.endsWith(".bsp"))
        throw new IllegalArgumentException("Invalid saved BSP path");
    }
  }

  private BuildManifest() {}

  public static Map<String, Entry> read(Path path) throws IOException {
    if (!Files.exists(path)) return Map.of();
    if (Files.size(path) > 2_097_152) throw new IOException("Oversized build manifest");
    try {
      var values = new Properties();
      try (var reader = Files.newBufferedReader(path)) {
        values.load(reader);
      }
      if (!"1".equals(values.remove("version")))
        throw new IOException("Unknown build manifest version");
      var hashes = new HashSet<String>();
      for (String key : values.stringPropertyNames()) {
        if (!key.matches("[0-9a-f]{64}\\.(slot|game|map|originY)"))
          throw new IOException("Invalid build manifest key");
        hashes.add(key.substring(0, 64));
      }
      if (hashes.size() > 4096 || values.size() != hashes.size() * 4)
        throw new IOException("Incomplete build manifest");
      var slots = new BitSet();
      var result = new HashMap<String, Entry>();
      for (String hash : hashes) {
        var entry =
            new Entry(
                hash,
                Integer.parseInt(values.getProperty(hash + ".slot")),
                values.getProperty(hash + ".game"),
                values.getProperty(hash + ".map"),
                Double.parseDouble(values.getProperty(hash + ".originY")));
        if (slots.get(entry.slot())) throw new IOException("Aliased build manifest slots");
        slots.set(entry.slot());
        result.put(hash, entry);
      }
      return Map.copyOf(result);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("Invalid build manifest", e);
    }
  }

  public static synchronized void remember(Path path, Entry entry) throws IOException {
    var entries = new HashMap<>(read(path));
    var previous = entries.get(entry.hash());
    if (previous != null) {
      if (previous.slot() != entry.slot() || previous.originY() != entry.originY())
        throw new IOException("Saved BSP region transform cannot change");
      return;
    }
    if (entries.values().stream().anyMatch(e -> e.slot() == entry.slot()))
      throw new IOException("Build region already assigned");
    entries.put(entry.hash(), entry);
    write(path, entries);
  }

  static void write(Path path, Map<String, Entry> entries) throws IOException {
    var values = new Properties();
    values.setProperty("version", "1");
    for (var entry : entries.values()) {
      values.setProperty(entry.hash() + ".slot", Integer.toString(entry.slot()));
      values.setProperty(entry.hash() + ".game", entry.game());
      values.setProperty(entry.hash() + ".map", entry.map());
      values.setProperty(entry.hash() + ".originY", Double.toString(entry.originY()));
    }
    Files.createDirectories(path.toAbsolutePath().getParent());
    var temp = Files.createTempFile(path.toAbsolutePath().getParent(), "build-manifest-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temp)) {
        values.store(writer, "CraftQ3 immutable build regions");
      }
      if (Files.size(temp) > 2_097_152) throw new IOException("Build manifest exceeds size limit");
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}

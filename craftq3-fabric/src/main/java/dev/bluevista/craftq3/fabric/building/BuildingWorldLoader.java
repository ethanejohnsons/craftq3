package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.assets.bsp.*;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.io.*;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Restores exact saved BSP identities before the build level can start ticking. */
public final class BuildingWorldLoader {
  @FunctionalInterface
  public interface FilesFactory {
    Pk3FileSystem open(String game) throws IOException;
  }

  record Located(VirtualPath path, VirtualFileSystem.Origin source, byte[] bytes) {}

  private record Prepared(
      String hash,
      int slot,
      String game,
      String path,
      Double originY,
      BspMap map,
      BspTraceWorld bsp) {}

  private record Pending(Path directory, List<Prepared> regions, boolean migrate) {}

  private static final Map<MinecraftServer, Pending> PENDING = new WeakHashMap<>();

  private BuildingWorldLoader() {}

  public static synchronized void clear(MinecraftServer server) {
    PENDING.remove(server);
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  static Located find(Pk3FileSystem files, VirtualPath path, String hash) throws IOException {
    for (var source : files.searchOrder()) {
      try {
        var bytes = files.readFrom(source, path);
        if (hash(bytes).equals(hash)) return new Located(path, source, bytes);
      } catch (FileNotFoundException ignored) {
      }
    }
    throw new IOException(
        "Saved BSP identity is unavailable: " + path.value() + " SHA-256 " + hash);
  }

  static Map<String, Located> discover(Pk3FileSystem files, Set<String> wanted) throws IOException {
    var result = new HashMap<String, Located>();
    for (var path : files.list("maps")) {
      if (!path.value().endsWith(".bsp")) continue;
      for (var source : files.searchOrder()) {
        try {
          var bytes = files.readFrom(source, path);
          var hash = hash(bytes);
          if (wanted.contains(hash)) result.putIfAbsent(hash, new Located(path, source, bytes));
        } catch (FileNotFoundException ignored) {
        }
      }
      if (result.keySet().containsAll(wanted)) return result;
    }
    var missing = new HashSet<>(wanted);
    missing.removeAll(result.keySet());
    if (!missing.isEmpty())
      throw new IOException(
          "Original BSPs needed for legacy build regions are unavailable: " + missing);
    return result;
  }

  public static double defaultOrigin(ServerLevel level, BspMap map) {
    return level.getMinY() + 16 - Math.floor(map.models().getFirst().bounds().min().z() / 32);
  }

  public static CoordinateTransform transform(
      ServerLevel level, BspMap map, int slot, double originY) throws IOException {
    var bounds = map.models().getFirst().bounds();
    if (Math.max(Math.abs(bounds.min().x()), Math.abs(bounds.max().x())) / 32 > 1800
        || Math.max(Math.abs(bounds.min().y()), Math.abs(bounds.max().y())) / 32 > 1800
        || bounds.min().z() / 32 + originY < level.getMinY()
        || bounds.max().z() / 32 + originY > level.getMinY() + level.getHeight())
      throw new IOException("Saved BSP exceeds the Minecraft build space");
    return new CoordinateTransform(32, new Vec3(slot * 4096.0, originY, 0));
  }

  public static synchronized void prepare(MinecraftServer server, FilesFactory factory)
      throws IOException {
    PENDING.remove(server);
    Path directory = server.getWorldPath(LevelResource.ROOT).resolve("craftq3");
    var index = BuildSpaceIndex.entries(directory.resolve("build-maps.properties"));
    var manifest = new HashMap<>(BuildManifest.read(directory.resolve("build-worlds.properties")));
    for (var entry : manifest.values())
      if (!Objects.equals(index.get(entry.hash()), entry.slot()))
        throw new IOException("Build manifest disagrees with the saved region index");
    if (index.isEmpty()) return;
    var missing = new HashSet<>(index.keySet());
    missing.removeAll(manifest.keySet());
    Map<String, Located> legacy = Map.of();
    if (!missing.isEmpty())
      try (var files = factory.open(null)) {
        legacy = discover(files, missing);
      }
    var prepared = new ArrayList<Prepared>();
    for (var indexed : index.entrySet()) {
      var entry = manifest.get(indexed.getKey());
      Located located;
      if (entry == null) located = legacy.get(indexed.getKey());
      else
        try (var files = factory.open(entry.game())) {
          located = find(files, new VirtualPath(entry.map()), entry.hash());
        }
      var map = BspReader.read(located.bytes());
      prepared.add(
          new Prepared(
              indexed.getKey(),
              indexed.getValue(),
              located.source().game(),
              located.path().value(),
              entry == null ? null : entry.originY(),
              map,
              new BspTraceWorld(map)));
    }
    PENDING.put(server, new Pending(directory, List.copyOf(prepared), !missing.isEmpty()));
  }

  public static synchronized void restore(ServerLevel level) throws IOException {
    if (!level.dimension().equals(BuildingSession.DIMENSION)) return;
    var pending = PENDING.remove(level.getServer());
    if (pending == null) return;
    var manifest = new HashMap<String, BuildManifest.Entry>();
    var restored = new HashMap<Integer, BuildingWorlds.Environment>();
    for (var prepared : pending.regions()) {
      var map = prepared.map();
      var entry =
          new BuildManifest.Entry(
              prepared.hash(),
              prepared.slot(),
              prepared.game(),
              prepared.path(),
              prepared.originY() == null ? defaultOrigin(level, map) : prepared.originY());
      manifest.put(entry.hash(), entry);
      var transform = transform(level, map, entry.slot(), entry.originY());
      var bsp = prepared.bsp();
      restored.put(
          entry.slot(),
          new BuildingWorlds.Environment(
              new BuildingGeometry(bsp, transform), new BuildingSupport(bsp, transform)));
    }
    if (pending.migrate())
      BuildManifest.write(pending.directory().resolve("build-worlds.properties"), manifest);
    restored.forEach((slot, environment) -> BuildingWorlds.register(level, slot, environment));
    org.slf4j.LoggerFactory.getLogger("CraftQ3")
        .info("Restored {} saved BSP building regions before level ticking", restored.size());
  }
}

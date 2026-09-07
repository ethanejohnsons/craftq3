package dev.bluevista.craftq3.fabric.bridge;

import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.server.ExternalPickup;
import dev.bluevista.craftq3.server.ExternalWorld;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Atomic world/game/dimension placement records; original item state lives in the running QVM. */
public final class PickupPlacements {
  public record Entry(int id, String classname, Vec3 position) {
    public Entry {
      new ExternalPickup(classname, new Vec3(0, 0, 0));
      if (id < 1
          || id > ExternalWorld.MAX_PICKUPS
          || Math.abs(position.x()) > 30_000_000
          || Math.abs(position.y()) > 20_000_000
          || Math.abs(position.z()) > 30_000_000)
        throw new IllegalArgumentException("Invalid pickup placement");
    }
  }

  private static final int MAGIC = 0x43513350;

  private PickupPlacements() {}

  private static VirtualPath file(String dimension) {
    if (dimension == null
        || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
        || dimension.length() > 256) throw new IllegalArgumentException("Invalid pickup dimension");
    try {
      return new VirtualPath(
          HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256")
                          .digest(dimension.getBytes(StandardCharsets.UTF_8)))
              + ".dat");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static GameFileStore store(Path world, String game) throws IOException {
    return new GameFileStore(
        world.resolve("craftq3/bridge-pickups").resolve(VirtualPath.gameDirectory(game)),
        new GameFileStore.Limits(65536, 16L * 1024 * 1024, 256, 0, 0));
  }

  public static synchronized List<Entry> read(Path world, String game, String dimension)
      throws IOException {
    try (var files = store(world, game)) {
      return read(files, dimension);
    }
  }

  static List<Entry> read(WritableFiles files, String dimension) throws IOException {
    var data = files.read(file(dimension));
    if (data.isEmpty()) return List.of();
    if (data.get().length > 65536) throw new IOException("Oversized pickup placements");
    try (var in = new DataInputStream(new ByteArrayInputStream(data.get()))) {
      if (in.readInt() != MAGIC || in.readInt() != 1 || !dimension.equals(in.readUTF()))
        throw new IOException("Invalid pickup placement header");
      int count = in.readInt();
      if (count < 0 || count > ExternalWorld.MAX_PICKUPS)
        throw new IOException("Too many pickup placements");
      var entries = new TreeMap<Integer, Entry>();
      for (int i = 0; i < count; i++) {
        var entry =
            new Entry(
                in.readInt(),
                in.readUTF(),
                new Vec3(in.readDouble(), in.readDouble(), in.readDouble()));
        if (entries.putIfAbsent(entry.id(), entry) != null)
          throw new IOException("Duplicate pickup id");
      }
      if (in.available() != 0) throw new IOException("Trailing pickup data");
      return List.copyOf(entries.values());
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid pickup placement", invalid);
    }
  }

  static void write(WritableFiles files, String dimension, List<Entry> entries) throws IOException {
    if (entries.size() > ExternalWorld.MAX_PICKUPS
        || entries.stream().map(Entry::id).distinct().count() != entries.size())
      throw new IllegalArgumentException("Invalid pickup placement list");
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(MAGIC);
      out.writeInt(1);
      out.writeUTF(dimension);
      out.writeInt(entries.size());
      for (var entry : entries) {
        out.writeInt(entry.id());
        out.writeUTF(entry.classname());
        out.writeDouble(entry.position().x());
        out.writeDouble(entry.position().y());
        out.writeDouble(entry.position().z());
      }
    }
    files.write(file(dimension), bytes.toByteArray());
  }

  public static synchronized Entry add(
      Path world, String game, String dimension, String classname, Vec3 position)
      throws IOException {
    try (var files = store(world, game)) {
      var entries = new ArrayList<>(read(files, dimension));
      var used = new BitSet();
      entries.forEach(entry -> used.set(entry.id()));
      var next = new Entry(used.nextClearBit(1), classname, position);
      entries.add(next);
      write(files, dimension, entries);
      return next;
    }
  }

  public static synchronized boolean remove(Path world, String game, String dimension, int id)
      throws IOException {
    try (var files = store(world, game)) {
      var entries = new ArrayList<>(read(files, dimension));
      if (!entries.removeIf(entry -> entry.id() == id)) return false;
      write(files, dimension, entries);
      return true;
    }
  }
}

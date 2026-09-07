package dev.bluevista.craftq3.fabric.bridge;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Journal generations match a marker saved atomically with Minecraft's own player data. */
public final class BridgeReturnState {
  public interface Marker {
    String craftq3$bridgeMarker();

    void craftq3$bridgeMarker(String value);
  }

  record State(GameType mode, Abilities.Packed abilities, boolean gravity, boolean physics) {
    void apply(ServerPlayer player) {
      player.setGameMode(mode);
      player.getAbilities().apply(abilities);
      player.setNoGravity(gravity);
      player.noPhysics = physics;
      player.onUpdateAbilities();
    }
  }

  record Journal(String token, State state) {}

  private BridgeReturnState() {}

  static State capture(ServerPlayer player) {
    return new State(
        player.gameMode.getGameModeForPlayer(),
        player.getAbilities().pack(),
        player.isNoGravity(),
        player.noPhysics);
  }

  static Path directory(ServerPlayer player) {
    return player
        .level()
        .getServer()
        .getWorldPath(LevelResource.ROOT)
        .resolve("craftq3/bridge-return-" + player.getUUID());
  }

  static String save(ServerPlayer player, State state) throws IOException {
    var directory = directory(player);
    Files.createDirectories(directory);
    try (var entries = Files.list(directory)) {
      if (entries.limit(1024).count() >= 1024)
        throw new IOException("Too many unconfirmed bridge recovery records");
    }
    String token = UUID.randomUUID().toString();
    var path = directory.resolve(token + ".properties");
    var temp = Files.createTempFile(directory, "pending-", ".tmp");
    try {
      write(temp, new Journal(token, state));
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
    return token;
  }

  static void write(Path path, Journal journal) throws IOException {
    var values = new Properties();
    var state = journal.state();
    var a = state.abilities();
    values.setProperty("version", "1");
    values.setProperty("token", journal.token());
    values.setProperty("mode", state.mode().getName());
    values.setProperty("invulnerable", Boolean.toString(a.invulnerable()));
    values.setProperty("flying", Boolean.toString(a.flying()));
    values.setProperty("mayfly", Boolean.toString(a.mayFly()));
    values.setProperty("instabuild", Boolean.toString(a.instabuild()));
    values.setProperty("mayBuild", Boolean.toString(a.mayBuild()));
    values.setProperty("flightSpeed", Float.toString(a.flyingSpeed()));
    values.setProperty("walkSpeed", Float.toString(a.walkingSpeed()));
    values.setProperty("noGravity", Boolean.toString(state.gravity()));
    values.setProperty("noPhysics", Boolean.toString(state.physics()));
    try (var writer = Files.newBufferedWriter(path)) {
      values.store(writer, "CraftQ3 bridge player restoration");
    }
  }

  static String token(String value) throws IOException {
    try {
      if (value.length() != 36 || !UUID.fromString(value).toString().equals(value))
        throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("Invalid bridge recovery marker", e);
    }
  }

  static Journal read(Path path) throws IOException {
    if (Files.size(path) > 8192) throw new IOException("Oversized bridge recovery record");
    var p = new Properties();
    try {
      try (var reader = Files.newBufferedReader(path)) {
        p.load(reader);
      }
      if (p.size() != 12 || !"1".equals(p.getProperty("version")))
        throw new IOException("Invalid bridge recovery schema");
      var mode = GameType.byName(p.getProperty("mode"), null);
      if (mode == null) throw new IOException("Invalid bridge return mode");
      var state =
          new State(
              mode,
              new Abilities.Packed(
                  flag(p, "invulnerable"),
                  flag(p, "flying"),
                  flag(p, "mayfly"),
                  flag(p, "instabuild"),
                  flag(p, "mayBuild"),
                  speed(p, "flightSpeed"),
                  speed(p, "walkSpeed")),
              flag(p, "noGravity"),
              flag(p, "noPhysics"));
      return new Journal(token(p.getProperty("token")), state);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("Invalid bridge recovery record", e);
    }
  }

  private static boolean flag(Properties p, String key) throws IOException {
    var value = p.getProperty(key);
    if (!"true".equals(value) && !"false".equals(value))
      throw new IOException("Invalid bridge recovery flag");
    return Boolean.parseBoolean(value);
  }

  private static float speed(Properties p, String key) throws IOException {
    float value = Float.parseFloat(p.getProperty(key));
    if (!Float.isFinite(value) || value < 0) throw new IOException("Invalid bridge recovery speed");
    return value;
  }

  /** Singleplayer level data can carry a saved marker into a different login profile. */
  static Path find(Path directory, String marker) throws IOException {
    String name = token(marker) + ".properties";
    var current = directory.resolve(name);
    if (Files.exists(current)) return current;
    Path found = null;
    int owners = 0;
    try (var entries = Files.newDirectoryStream(directory.getParent(), "bridge-return-*")) {
      for (var owner : entries) {
        if (++owners > 4096) throw new IOException("Too many bridge recovery profiles");
        if (!Files.isDirectory(owner, LinkOption.NOFOLLOW_LINKS)) continue;
        try {
          token(owner.getFileName().toString().substring("bridge-return-".length()));
        } catch (IOException invalid) {
          continue;
        }
        var candidate = owner.resolve(name);
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
        if (found != null) throw new IOException("Ambiguous bridge recovery generation");
        found = candidate;
      }
    }
    if (found == null) throw new NoSuchFileException(current.toString());
    return found;
  }

  public static void recover(ServerPlayer player) throws IOException {
    var marker = (Marker) player;
    var directory = directory(player);
    if (marker.craftq3$bridgeMarker().isEmpty()) {
      // Only a fresh load without the marker confirms that restored player data reached disk.
      if (Files.isDirectory(directory)) {
        try (var entries = Files.newDirectoryStream(directory, "*.properties")) {
          for (var path : entries) Files.delete(path);
        } catch (IOException error) {
          org.slf4j.LoggerFactory.getLogger("CraftQ3")
              .warn("Could not remove confirmed bridge recovery records", error);
        }
      }
      return;
    }
    String token = token(marker.craftq3$bridgeMarker());
    var record = find(directory, token);
    var journal = read(record);
    if (!token.equals(journal.token()))
      throw new IOException("Bridge recovery generation does not match player data");
    journal.state().apply(player);
    marker.craftq3$bridgeMarker("");
    if (!record.getParent().equals(directory))
      org.slf4j.LoggerFactory.getLogger("CraftQ3")
          .info(
              "Recovered bridge return state from saved profile {}",
              record.getParent().getFileName());
    // Keep this generation for another crash before Minecraft saves the cleared marker.
  }
}

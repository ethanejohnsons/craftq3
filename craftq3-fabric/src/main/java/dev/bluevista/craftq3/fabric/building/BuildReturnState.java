package dev.bluevista.craftq3.fabric.building;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** A durable way home if the client stops while its player is saved in the build dimension. */
public final class BuildReturnState {
  private BuildReturnState() {}

  static Path path(ServerPlayer player) {
    return player
        .level()
        .getServer()
        .getWorldPath(LevelResource.ROOT)
        .resolve("craftq3/return-" + player.getUUID() + ".properties");
  }

  public static void save(ServerPlayer player) throws IOException {
    if (player.level().dimension().equals(BuildingSession.DIMENSION))
      throw new IOException("Cannot replace the return point from inside the build dimension");
    var values = capture(player);
    var path = path(player);
    Files.createDirectories(path.getParent());
    var temp = Files.createTempFile(path.getParent(), "return-", ".tmp");
    try {
      try (var writer = Files.newBufferedWriter(temp)) {
        values.store(writer, "CraftQ3 build return point");
      }
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  static Properties capture(ServerPlayer player) {
    var values = new Properties();
    var abilities = player.getAbilities();
    values.setProperty("dimension", player.level().dimension().identifier().toString());
    values.setProperty("x", Double.toString(player.getX()));
    values.setProperty("y", Double.toString(player.getY()));
    values.setProperty("z", Double.toString(player.getZ()));
    values.setProperty("yaw", Float.toString(player.getYRot()));
    values.setProperty("pitch", Float.toString(player.getXRot()));
    values.setProperty("mode", player.gameMode.getGameModeForPlayer().getName());
    values.setProperty("invulnerable", Boolean.toString(abilities.invulnerable));
    values.setProperty("flying", Boolean.toString(abilities.flying));
    values.setProperty("mayfly", Boolean.toString(abilities.mayfly));
    values.setProperty("instabuild", Boolean.toString(abilities.instabuild));
    values.setProperty("mayBuild", Boolean.toString(abilities.mayBuild));
    values.setProperty("flightSpeed", Float.toString(abilities.getFlyingSpeed()));
    values.setProperty("walkSpeed", Float.toString(abilities.getWalkingSpeed()));
    return values;
  }

  public static void recover(ServerPlayer player) throws IOException {
    var path = path(player);
    if (!player.level().dimension().equals(BuildingSession.DIMENSION)) {
      // A fresh load outside the build dimension confirms the return reached Minecraft storage.
      // Leave/recovery itself must retain the journal: an immediate crash can reload older data.
      try {
        Files.deleteIfExists(path);
      } catch (IOException failure) {
        org.slf4j.LoggerFactory.getLogger("CraftQ3")
            .warn("Could not remove an obsolete build return point", failure);
      }
      return;
    }
    if (!Files.isRegularFile(path)) return;
    var point = read(path);
    var level = player.level().getServer().getLevel(point.dimension());
    if (level == null) throw new IOException("Return dimension is unavailable");
    if (!player.teleportTo(
        level, point.x(), point.y(), point.z(), Set.of(), point.yaw(), point.pitch(), true))
      throw new IOException("Return teleport failed");
    player.setGameMode(point.mode());
    player.getAbilities().apply(point.abilities());
    player.onUpdateAbilities();
    // Keep the return journal until a subsequent join confirms a saved outside position.
  }

  record ReturnPoint(
      ResourceKey<net.minecraft.world.level.Level> dimension,
      double x,
      double y,
      double z,
      float yaw,
      float pitch,
      GameType mode,
      net.minecraft.world.entity.player.Abilities.Packed abilities) {}

  /** Parse every field before changing any Minecraft player state. Never consumes the journal. */
  static ReturnPoint read(Path path) throws IOException {
    if (Files.size(path) > 8192) throw new IOException("Oversized build return point");
    try {
      var values = new Properties();
      try (var reader = Files.newBufferedReader(path)) {
        values.load(reader);
      }
      var key =
          ResourceKey.create(
              Registries.DIMENSION, Identifier.parse(values.getProperty("dimension")));
      if (key.equals(BuildingSession.DIMENSION))
        throw new IOException("Return point cannot target the build dimension");
      double x = number(values, "x"), y = number(values, "y"), z = number(values, "z");
      if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 20_000_000)
        throw new IOException("Invalid return coordinates");
      var mode = GameType.byName(values.getProperty("mode"), null);
      if (mode == null) throw new IOException("Invalid return mode");
      float yaw = decimal(values, "yaw"), pitch = decimal(values, "pitch");
      float flight = decimal(values, "flightSpeed"), walk = decimal(values, "walkSpeed");
      if (flight < 0 || walk < 0) throw new IOException("Invalid return ability speed");
      var abilities =
          new net.minecraft.world.entity.player.Abilities.Packed(
              flag(values, "invulnerable"),
              flag(values, "flying"),
              flag(values, "mayfly"),
              flag(values, "instabuild"),
              flag(values, "mayBuild"),
              flight,
              walk);
      return new ReturnPoint(key, x, y, z, yaw, pitch, mode, abilities);
    } catch (IllegalArgumentException
        | NullPointerException
        | net.minecraft.IdentifierException failure) {
      throw new IOException("Invalid build return point", failure);
    }
  }

  private static boolean flag(Properties values, String key) throws IOException {
    var value = values.getProperty(key);
    if (!"true".equals(value) && !"false".equals(value))
      throw new IOException("Invalid return flag");
    return Boolean.parseBoolean(value);
  }

  private static float decimal(Properties values, String key) throws IOException {
    float value = (float) number(values, key);
    if (!Float.isFinite(value)) throw new IOException("Invalid return decimal");
    return value;
  }

  private static double number(Properties values, String key) throws IOException {
    double value = Double.parseDouble(values.getProperty(key));
    if (!Double.isFinite(value)) throw new IOException("Non-finite return value");
    return value;
  }
}

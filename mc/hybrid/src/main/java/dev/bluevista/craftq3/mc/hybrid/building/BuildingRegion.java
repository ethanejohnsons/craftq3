package dev.bluevista.craftq3.mc.hybrid.building;

import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One BSP placed in the building dimension: which map (game directory, path and the exact BSP's
 * SHA-256), and where (the Minecraft block of the Quake origin and the scale). Saved per world by
 * {@link BuildingRegionCodec}; the BSP itself is never copied into the save.
 *
 * @param slot the region's index, which also decides its place along X
 * @param game the game directory the map was loaded from, e.g. {@code baseq3}
 * @param map the map's path in the game's files, e.g. {@code maps/q3dm17.bsp}
 * @param sha256 lowercase hex SHA-256 of the BSP file
 * @param originX the Minecraft block of the Quake origin
 * @param unitsPerBlock the scale, normally 32
 */
public record BuildingRegion(
    int slot,
    String game,
    String map,
    String sha256,
    int originX,
    int originY,
    int originZ,
    int unitsPerBlock) {
  /** At most this many regions per world. */
  public static final int MAX_SLOTS = 4096;

  private static final Pattern GAME = Pattern.compile("[A-Za-z0-9_.\\- ]{1,64}");
  private static final Pattern MAP = Pattern.compile("maps/[A-Za-z0-9_.\\-/ ]{1,120}\\.bsp");
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

  public BuildingRegion {
    Objects.requireNonNull(game, "game");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(sha256, "sha256");
    if (slot < 0 || slot >= MAX_SLOTS) throw new IllegalArgumentException("Region slot " + slot);
    if (!GAME.matcher(game).matches() || game.contains(".."))
      throw new IllegalArgumentException("Invalid game directory: " + game);
    map = map.replace('\\', '/');
    if (!MAP.matcher(map.toLowerCase(Locale.ROOT)).matches() || map.contains(".."))
      throw new IllegalArgumentException("Invalid map path: " + map);
    if (!SHA.matcher(sha256).matches()) throw new IllegalArgumentException("Invalid SHA-256");
    if (unitsPerBlock < 1 || unitsPerBlock > 4096)
      throw new IllegalArgumentException("Invalid scale " + unitsPerBlock);
    if (Math.abs((long) originX) > 30_000_000
        || Math.abs((long) originZ) > 30_000_000
        || Math.abs((long) originY) > 20_000)
      throw new IllegalArgumentException("Region origin out of range");
  }

  /** The map name without directory or extension. */
  public String mapName() {
    String name = map.substring(map.lastIndexOf('/') + 1);
    return name.substring(0, name.length() - 4);
  }

  public CoordinateTransform transform() {
    return new CoordinateTransform(unitsPerBlock, originX, originY, originZ);
  }
}

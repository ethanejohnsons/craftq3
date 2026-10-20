package dev.bluevista.craftq3.engine.game.map;

import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A map as the server runs it: the BSP (collision, visibility, inline models), the entity string
 * the game parses, the checksum published as {@code sv_mapChecksum}, the AAS file for bots when one
 * exists, and extra collision sources composed into world traces.
 *
 * @param name the map name without directory or extension ({@code q3dm17})
 * @param entityText what {@code trap_GetEntityToken} tokenizes (usually the BSP's entity lump)
 * @param checksum {@code CM_LoadMap}'s block checksum of the BSP file
 * @param aas the {@code maps/<name>.aas} bytes, if present
 * @param sources extra world geometry composed into world traces (empty for a plain BSP map)
 */
public record GameMap(
    String name,
    BspMap bsp,
    String entityText,
    int checksum,
    Optional<byte[]> aas,
    List<CollisionSource> sources) {
  public GameMap {
    Objects.requireNonNull(name);
    Objects.requireNonNull(bsp);
    Objects.requireNonNull(entityText);
    Objects.requireNonNull(aas);
    sources = List.copyOf(sources);
  }

  /** A plain BSP map with its own entity lump and no extra collision sources. */
  public static GameMap of(String name, BspMap bsp, Optional<byte[]> aas) {
    return new GameMap(name, bsp, bsp.entityText(), bsp.checksum(), aas, List.of());
  }
}

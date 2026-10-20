package dev.bluevista.craftq3.engine.game.map;

import java.io.IOException;

/**
 * Supplies the maps a server can spawn. {@link BspMapSource} loads {@code maps/<name>.bsp} (and its
 * AAS) from the game's files; other sources may build synthetic maps (the Minecraft bridge supplies
 * terrain this way). Called on the simulation thread.
 */
public interface MapSource {
  /** Whether {@code name} can be loaded ({@code SV_Map_f}'s existence check). */
  boolean exists(String name);

  /**
   * Loads {@code name}.
   *
   * @throws IOException when the map is missing or cannot be read
   * @throws RuntimeException (a format exception) when its data is malformed
   */
  GameMap load(String name) throws IOException;
}

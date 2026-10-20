package dev.bluevista.craftq3.mc.fabric.level;

import dev.bluevista.craftq3.mc.fabric.bridge.BridgeGame;
import dev.bluevista.craftq3.mc.fabric.building.LevelBuilding;

/**
 * Per-level CraftQ3 state, implemented on every {@code Level} by {@code LevelMixin} (the client
 * level holds the building regions too): the level's running bridge game and its building regions.
 * Each level owns its own state, read and written on the server thread, so no global map or lock is
 * involved.
 */
public interface CraftQ3Level {
  /** The level's bridge game, or null. */
  BridgeGame craftq3$bridge();

  void craftq3$setBridge(BridgeGame game);

  /** The level's building regions; {@link LevelBuilding#NONE} outside the building dimension. */
  LevelBuilding craftq3$building();

  void craftq3$setBuilding(LevelBuilding building);

  /** The state of {@code level} (any {@code Level}). */
  static CraftQ3Level of(Object level) {
    return (CraftQ3Level) level;
  }
}

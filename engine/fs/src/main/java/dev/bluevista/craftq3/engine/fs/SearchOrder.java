package dev.bluevista.craftq3.engine.fs;

/** How PK3s and loose files of one game directory are ordered against each other. */
public enum SearchOrder {
  /**
   * Quake III 1.32: the game directory is added to the search path before its PK3s, and each
   * addition goes to the front, so PK3s are searched first (the last name in sort order wins) and
   * loose files only supply what no PK3 has. This is why mods ship {@code zz*.pk3} overrides.
   */
  ORIGINAL_132,
  /**
   * ioquake3 and the legacy CraftQ3 mount: loose files override every PK3 of the same directory.
   */
  LOOSE_FIRST
}

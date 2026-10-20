package dev.bluevista.craftq3.engine.botlib.testing;

import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import java.util.Map;

/** Test support: authored in-memory bot files. */
public final class TestFiles {
  private TestFiles() {}

  /** Files keyed by Quake III path, read as Latin-1 text. */
  public static AssetLookup memory(Map<String, String> files) {
    var assets = new MemoryAssets();
    files.forEach(assets::put);
    return assets;
  }
}

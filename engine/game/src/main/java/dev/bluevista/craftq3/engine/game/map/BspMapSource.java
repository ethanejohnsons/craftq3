package dev.bluevista.craftq3.engine.game.map;

import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Loads {@code maps/<name>.bsp} and, when present, {@code maps/<name>.aas} from game files. */
public final class BspMapSource implements MapSource {
  private final AssetLookup files;

  public BspMapSource(AssetLookup files) {
    this.files = Objects.requireNonNull(files);
  }

  /** The map name as the server reports it: no {@code maps/} prefix or {@code .bsp} suffix. */
  public static String normalize(String name) {
    String result = name.replace('\\', '/');
    if (result.toLowerCase(Locale.ROOT).startsWith("maps/")) result = result.substring(5);
    if (result.toLowerCase(Locale.ROOT).endsWith(".bsp"))
      result = result.substring(0, result.length() - 4);
    return result;
  }

  @Override
  public boolean exists(String name) {
    String map = normalize(name);
    return !map.isEmpty() && files.exists("maps/" + map + ".bsp");
  }

  @Override
  public GameMap load(String name) throws IOException {
    String map = normalize(name);
    if (map.isEmpty() || map.contains("..")) throw new NoSuchFileException(name);
    String path = "maps/" + map + ".bsp";
    byte[] bytes = files.read(path).orElseThrow(() -> new NoSuchFileException(path));
    BspMap bsp = BspReader.read(bytes, path);
    Optional<byte[]> aas = files.read("maps/" + map + ".aas");
    return GameMap.of(map, bsp, aas);
  }
}

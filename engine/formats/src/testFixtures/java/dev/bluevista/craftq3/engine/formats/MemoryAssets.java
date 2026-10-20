package dev.bluevista.craftq3.engine.formats;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link AssetLookup}. Files keep insertion order, which is the listing order; names
 * are matched case-insensitively with either separator.
 */
public final class MemoryAssets implements AssetLookup {
  private final Map<String, byte[]> files = new LinkedHashMap<>();

  public MemoryAssets put(String path, byte[] data) {
    files.put(QPath.normalize(path), data.clone());
    return this;
  }

  public MemoryAssets put(String path, String text) {
    return put(path, text.getBytes(StandardCharsets.ISO_8859_1));
  }

  @Override
  public Optional<byte[]> read(String path) {
    byte[] data = files.get(QPath.normalize(path));
    return data == null ? Optional.empty() : Optional.of(data.clone());
  }

  @Override
  public boolean exists(String path) {
    return files.containsKey(QPath.normalize(path));
  }

  @Override
  public List<String> list(String directory, String extension) {
    String prefix = QPath.normalize(directory) + "/";
    List<String> result = new ArrayList<>();
    for (String path : files.keySet()) {
      String rest = path.startsWith(prefix) ? path.substring(prefix.length()) : null;
      if (rest != null && rest.indexOf('/') < 0 && QPath.extension(rest).equals(extension))
        result.add(path);
    }
    return result;
  }
}

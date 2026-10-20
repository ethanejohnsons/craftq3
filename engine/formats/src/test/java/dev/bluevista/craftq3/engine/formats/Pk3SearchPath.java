package dev.bluevista.craftq3.engine.formats;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A test-only search path over already opened PK3s and loose directories, highest priority first,
 * with the original engine's name matching (case-insensitive, either separator). It stands in for
 * the filesystem module, which is adapted to {@link AssetLookup} separately.
 */
final class Pk3SearchPath implements AssetLookup {
  /** One mounted source: a PK3 or a loose game directory. */
  sealed interface Source permits Archive, Directory {
    /** Normalised names in the source's own order. */
    List<String> names();

    byte[] read(String normalisedName) throws IOException;
  }

  record Archive(ZipFile zip, List<String> names, Map<String, ZipEntry> entries) implements Source {
    static Archive of(ZipFile zip) {
      List<String> names = new ArrayList<>();
      Map<String, ZipEntry> entries = new HashMap<>();
      zip.stream()
          .filter(entry -> !entry.isDirectory())
          .forEach(
              entry -> {
                String name = QPath.normalize(entry.getName());
                if (entries.putIfAbsent(name, entry) == null) names.add(name);
              });
      return new Archive(zip, names, entries);
    }

    @Override
    public byte[] read(String name) throws IOException {
      try (InputStream input = zip.getInputStream(entries.get(name))) {
        return input.readAllBytes();
      }
    }
  }

  record Directory(Path root, List<String> names, Map<String, Path> files) implements Source {
    static Directory of(Path root) throws IOException {
      List<String> names = new ArrayList<>();
      Map<String, Path> files = new HashMap<>();
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile)
            .filter(p -> !QPath.extension(p.getFileName().toString()).equals("pk3"))
            .sorted()
            .forEach(
                p -> {
                  String name = QPath.normalize(root.relativize(p).toString());
                  if (files.putIfAbsent(name, p) == null) names.add(name);
                });
      }
      return new Directory(root, names, files);
    }

    @Override
    public byte[] read(String name) throws IOException {
      return Files.readAllBytes(files.get(name));
    }
  }

  private final List<Source> sources;
  private final Map<String, Source> index = new HashMap<>();

  Pk3SearchPath(List<Source> highestPriorityFirst) {
    this.sources = List.copyOf(highestPriorityFirst);
    for (Source source : sources)
      for (String name : source.names()) index.putIfAbsent(name, source);
  }

  @Override
  public Optional<byte[]> read(String path) {
    String name = QPath.normalize(path);
    Source source = index.get(name);
    if (source == null) return Optional.empty();
    try {
      return Optional.of(source.read(name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public boolean exists(String path) {
    return index.containsKey(QPath.normalize(path));
  }

  @Override
  public List<String> list(String directory, String extension) {
    String prefix = QPath.normalize(directory) + "/";
    Set<String> result = new LinkedHashSet<>();
    for (Source source : sources) {
      for (String name : source.names()) {
        if (name.startsWith(prefix)
            && name.indexOf('/', prefix.length()) < 0
            && QPath.extension(name).equals(extension)) result.add(name);
      }
    }
    return List.copyOf(result);
  }
}

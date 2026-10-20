package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.fs.SearchPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lists game directories for the ui's {@code $modlist} file query, as the original engine does:
 * every directory under the given roots (the home store first, then the installation) except {@code
 * baseq3} and dot-directories, that directly contains at least one {@code .pk3}; duplicates across
 * roots are dropped case-insensitively. The description is the first line of the directory's {@code
 * description.txt} (searched in the same root order), else the directory name. Entries are sorted
 * case-insensitively so the menu order does not depend on the host file system.
 */
public final class ModList {
  /** The original copies descriptions into a {@code MAX_OSPATH} buffer. */
  public static final int MAX_DESCRIPTION = 255;

  /** The file holding a mod's display name. */
  public static final String DESCRIPTION_FILE = "description.txt";

  private final List<Path> roots;

  /**
   * @param roots directories holding game directories, searched in order
   */
  public ModList(List<Path> roots) {
    this.roots = List.copyOf(roots);
  }

  public List<VmFiles.ModInfo> scan() throws IOException {
    List<VmFiles.ModInfo> mods = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) continue;
      List<Path> children = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
        for (Path child : stream) children.add(child);
      }
      for (Path child : children) {
        String name = child.getFileName().toString();
        String key = name.toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || key.equals(SearchPath.BASE_GAME) || !seen.add(key)) continue;
        if (!Files.isDirectory(child) || !hasPack(child)) {
          seen.remove(key);
          continue;
        }
        mods.add(new VmFiles.ModInfo(name, description(name)));
      }
    }
    mods.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.directory(), b.directory()));
    return List.copyOf(mods);
  }

  private static boolean hasPack(Path directory) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path child : stream)
        if (child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3")
            && Files.isRegularFile(child)) return true;
    }
    return false;
  }

  private String description(String directory) throws IOException {
    for (Path root : roots) {
      Path file = root.resolve(directory).resolve(DESCRIPTION_FILE);
      if (!Files.isRegularFile(file)) continue;
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file)) {
        bytes = in.readNBytes(MAX_DESCRIPTION);
      }
      String text = new String(bytes, StandardCharsets.ISO_8859_1);
      int end = 0;
      while (end < text.length()
          && text.charAt(end) != '\n'
          && text.charAt(end) != '\r'
          && text.charAt(end) != 0) end++;
      String line = text.substring(0, end).strip();
      if (!line.isEmpty()) return line;
    }
    return directory;
  }
}

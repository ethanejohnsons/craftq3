package dev.bluevista.craftq3.client.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Safe original-menu names and reversible display aliases for real protocol-68 recordings. */
public final class DemoCatalog {
  public static final int MAX_ENTRIES = 65536;
  private static final String PHYSICAL = ".dm_68";
  private static final String ALIAS = ".dm3";

  private DemoCatalog() {}

  /**
   * Merges flat mounted entries and host-owned protocol-68 filenames. The retail dm3 query exposes
   * only aliases of actual dm_68 entries. Exact duplicates collapse; conflicting case spellings are
   * omitted. Unsupported physical dm3 names block same-name aliases.
   */
  public static List<String> files(
      List<String> mountedNames, List<String> hostNames, String extension, boolean retail) {
    Objects.requireNonNull(mountedNames);
    Objects.requireNonNull(hostNames);
    Objects.requireNonNull(extension);
    if ((long) mountedNames.size() + hostNames.size() > MAX_ENTRIES)
      throw new IllegalArgumentException("Demo catalog entry limit exceeded");
    var available = new ArrayList<String>(mountedNames);
    for (String name : hostNames) if (physical(name)) available.add(name);
    var index = index(available);
    String suffix = extension.toLowerCase(Locale.ROOT);
    if (retail && (suffix.equals("dm3") || suffix.equals(ALIAS))) {
      var aliases = new ArrayList<String>();
      for (var entry : index.entrySet()) {
        String name = entry.getValue();
        if (name == null || !physical(name)) continue;
        String alias = name.substring(0, name.length() - PHYSICAL.length()) + ALIAS;
        if (!index.containsKey(alias.toLowerCase(Locale.ROOT))) aliases.add(alias);
      }
      aliases.sort(String.CASE_INSENSITIVE_ORDER);
      return List.copyOf(aliases);
    }
    return index.entrySet().stream()
        .filter(entry -> entry.getValue() != null && entry.getKey().endsWith(suffix))
        .filter(
            entry ->
                !(suffix.equals(PHYSICAL) || suffix.equals("dm_68")) || physical(entry.getValue()))
        .map(Map.Entry::getValue)
        .toList();
  }

  /**
   * Resolves only a known dm3 display alias, preserving its unique physical filename. Supply all
   * available flat names, including physical dm3 blockers. Unknown, unsafe or ambiguous names
   * return empty; this does not authorize opening an arbitrary renamed or protocol-43 file.
   */
  public static Optional<String> resolveAlias(String name, List<String> availableNames) {
    if (!safe(name) || !name.toLowerCase(Locale.ROOT).endsWith(ALIAS)) return Optional.empty();
    var index = index(availableNames);
    String requested = name.toLowerCase(Locale.ROOT);
    if (index.containsKey(requested)) return Optional.empty();
    String physical = requested.substring(0, requested.length() - ALIAS.length()) + PHYSICAL;
    String match = index.get(physical);
    return match != null && physical(match) ? Optional.of(match) : Optional.empty();
  }

  private static TreeMap<String, String> index(List<String> names) {
    Objects.requireNonNull(names);
    if (names.size() > MAX_ENTRIES)
      throw new IllegalArgumentException("Demo catalog entry limit exceeded");
    var index = new TreeMap<String, String>();
    for (String name : names) {
      if (!safe(name)) continue;
      String key = name.toLowerCase(Locale.ROOT);
      if (!index.containsKey(key)) index.put(key, name);
      else if (!name.equals(index.get(key))) index.put(key, null);
    }
    return index;
  }

  private static boolean physical(String name) {
    return safe(name)
        && name.length() > PHYSICAL.length()
        && name.toLowerCase(Locale.ROOT).endsWith(PHYSICAL);
  }

  private static boolean safe(String name) {
    if (name == null
        || name.isEmpty()
        || name.length() > 255
        || name.equals(".")
        || name.equals("..")) return false;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c <= 32 || c >= 127 || c == '"' || c == ';' || c == '/' || c == '\\' || c == ':')
        return false;
    }
    return true;
  }
}

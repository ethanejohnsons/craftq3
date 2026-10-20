package dev.bluevista.craftq3.engine.fs;

import java.util.Optional;

/**
 * A relative Quake III file path. Backslashes become forward slashes and lookups use an ASCII
 * lower-cased {@link #key()}, as the original engine compares names; the caller's {@link
 * #spelling()} is kept for reference tracking and diagnostics. Spaces and non-ASCII characters are
 * allowed. Absolute paths, empty, {@code .} and {@code ..} components, drive letters and other
 * {@code :} uses, and control characters are rejected, so a host path is never built from an
 * unchecked VM string.
 *
 * <p>Immutable and safe to share between threads.
 */
public final class VirtualPath implements Comparable<VirtualPath> {
  /** Longest accepted spelling, in characters. */
  public static final int MAX_LENGTH = 255;

  private final String key;
  private final String spelling;

  private VirtualPath(String key, String spelling) {
    this.key = key;
    this.spelling = spelling;
  }

  /**
   * Validates and normalises {@code path}.
   *
   * @throws IllegalArgumentException naming the rule the path breaks
   */
  public static VirtualPath of(String path) {
    String problem = problem(path);
    if (problem != null) throw new IllegalArgumentException(problem);
    return new VirtualPath(fold(path.replace('\\', '/')), path);
  }

  /** Like {@link #of} but returns empty for an invalid path. */
  public static Optional<VirtualPath> parse(String path) {
    return problem(path) == null
        ? Optional.of(new VirtualPath(fold(path.replace('\\', '/')), path))
        : Optional.empty();
  }

  /** Why {@code path} is not a valid virtual path, or null when it is. */
  static String problem(String path) {
    if (path == null) return "Virtual path is null";
    if (path.isEmpty() || path.length() > MAX_LENGTH)
      return "Virtual path must contain 1.." + MAX_LENGTH + " characters";
    int start = 0;
    for (int i = 0; i <= path.length(); i++) {
      char c = i < path.length() ? path.charAt(i) : '/';
      if (c < 32 || c == 127) return "Virtual path contains a control character";
      if (c == ':') return "Virtual path contains ':' (drive letter or stream name)";
      if (c == '/' || c == '\\') {
        int length = i - start;
        if (length == 0) return "Virtual path has an empty component (absolute or trailing '/')";
        if (length == 1 && path.charAt(start) == '.') return "Virtual path has a '.' component";
        if (length == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.')
          return "Virtual path has a '..' component";
        start = i + 1;
      }
    }
    return null;
  }

  /**
   * A single game-directory name such as {@code baseq3} or {@code Alternate Fire}, returned in its
   * lookup form.
   */
  public static String gameDirectory(String name) {
    VirtualPath path = of(name);
    if (path.key.indexOf('/') >= 0)
      throw new IllegalArgumentException("Game directory must be one directory name: " + name);
    return path.key;
  }

  /**
   * Normalises a directory prefix for listing: {@code ""} or {@code "/"} is the root; otherwise the
   * result is the lookup key with a trailing slash, such as {@code "maps/"}.
   */
  public static String directoryKey(String directory) {
    if (directory == null) return "";
    String trimmed = directory.replace('\\', '/');
    while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
    return trimmed.isEmpty() ? "" : of(trimmed).key + '/';
  }

  /** ASCII-only lower-casing, as the original engine's name comparison. */
  static String fold(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        char[] chars = text.toCharArray();
        for (int j = i; j < chars.length; j++)
          if (chars[j] >= 'A' && chars[j] <= 'Z') chars[j] += 'a' - 'A';
        return new String(chars);
      }
    }
    return text;
  }

  /** Canonical lookup form: forward slashes, ASCII lower case. */
  public String key() {
    return key;
  }

  /** The validated spelling the caller supplied, with its original case and separators. */
  public String spelling() {
    return spelling;
  }

  /** The last component of {@link #key()}. */
  public String fileName() {
    return key.substring(key.lastIndexOf('/') + 1);
  }

  /** The lower-case extension without its dot, or {@code ""}. */
  public String extension() {
    String name = fileName();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1);
  }

  @Override
  public int compareTo(VirtualPath other) {
    return key.compareTo(other.key);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VirtualPath path && key.equals(path.key);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }

  @Override
  public String toString() {
    return key;
  }
}

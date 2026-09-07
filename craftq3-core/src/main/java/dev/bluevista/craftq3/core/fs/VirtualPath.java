package dev.bluevista.craftq3.core.fs;

import java.util.Locale;

/** Canonical, relative Q3 path. Host paths must never be constructed from unchecked VM strings. */
public final class VirtualPath implements Comparable<VirtualPath> {
  private final String value;
  private final String requested;

  public VirtualPath(String value) {
    if (value == null || value.isEmpty() || value.length() > 255) {
      throw new IllegalArgumentException("Virtual path must contain 1..255 characters");
    }
    String requested = value;
    value = value.replace('\\', '/').toLowerCase(Locale.ROOT);
    if (value.startsWith("/") || value.endsWith("/")) {
      throw new IllegalArgumentException("Virtual path must be relative and name a file: " + value);
    }
    for (String part : value.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) {
        throw new IllegalArgumentException("Invalid virtual path component: " + value);
      }
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 32 || c == 127 || c == ':' || c == 0) {
        throw new IllegalArgumentException("Invalid virtual path character");
      }
    }
    this.value = value;
    this.requested = requested;
  }

  /** Canonical spelling used for path identity and lookup. */
  public String value() {
    return value;
  }

  /** Validated caller spelling, including its original case and path separators. */
  public String requested() {
    return requested;
  }

  public static String gameDirectory(String value) {
    VirtualPath path = new VirtualPath(value);
    if (path.value.contains("/")) {
      throw new IllegalArgumentException("Game directory must be one relative directory name");
    }
    return path.value;
  }

  @Override
  public int compareTo(VirtualPath other) {
    return value.compareTo(other.value);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof VirtualPath path && value.equals(path.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return "VirtualPath[value=" + value + "]";
  }
}

package dev.bluevista.craftq3.botlib.script;

/** A virtual source name and one-based physical position; never a host filesystem path. */
public record SourceLocation(String path, int line, int column) implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  public SourceLocation {
    if (path == null || path.isEmpty() || line < 1 || column < 1)
      throw new IllegalArgumentException("Invalid script source location");
  }

  @Override
  public String toString() {
    return path + ":" + line + ":" + column;
  }
}

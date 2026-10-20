package dev.bluevista.craftq3.botlib.script;

import java.io.IOException;

/** Script failures retain the virtual source position for the PC_* caller's diagnostics. */
public final class ScriptException extends IOException {
  private static final long serialVersionUID = 1L;
  private final SourceLocation location;

  public ScriptException(SourceLocation location, String message) {
    super(location + ": " + message);
    this.location = location;
  }

  public SourceLocation location() {
    return location;
  }
}

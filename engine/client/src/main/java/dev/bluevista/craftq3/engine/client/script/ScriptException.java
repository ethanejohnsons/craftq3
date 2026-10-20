package dev.bluevista.craftq3.engine.client.script;

/** A script the precompiler cannot read further: the guest's read returns end of file. */
public final class ScriptException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ScriptException(String file, int line, String message) {
    super(file + ":" + line + ": " + message);
  }
}

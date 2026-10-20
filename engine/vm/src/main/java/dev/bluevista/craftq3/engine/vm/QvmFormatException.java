package dev.bluevista.craftq3.engine.vm;

/** A QVM file was rejected while parsing or validating; the message names the module. */
public final class QvmFormatException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String module;

  public QvmFormatException(String module, String message) {
    super((module == null || module.isEmpty() ? "QVM" : module) + ": " + message);
    this.module = module == null ? "" : module;
  }

  public String module() {
    return module;
  }
}

package dev.bluevista.craftq3.engine.vmabi;

import java.util.Locale;
import java.util.Optional;

/** The three Quake III virtual machines. Botlib imports belong to {@link #QAGAME}. */
public enum VmModule {
  QAGAME("qagame"),
  CGAME("cgame"),
  UI("ui");

  private final String moduleName;

  VmModule(String moduleName) {
    this.moduleName = moduleName;
  }

  /** The module's file stem ({@code qagame}, {@code cgame}, {@code ui}), used in log keys. */
  public String moduleName() {
    return moduleName;
  }

  /** The search-path location of the module, e.g. {@code vm/cgame.qvm}. */
  public String path() {
    return "vm/" + moduleName + ".qvm";
  }

  /**
   * Recognises a module from a name such as {@code cgame}, {@code vm/cgame.qvm} or {@code
   * baseq3/pak8.pk3!vm/CGAME.QVM}: the last path component without its extension, ignoring case.
   */
  public static Optional<VmModule> fromName(String name) {
    if (name == null) return Optional.empty();
    String stem = name.replace('\\', '/');
    stem = stem.substring(Math.max(stem.lastIndexOf('/'), stem.lastIndexOf('!')) + 1);
    int dot = stem.indexOf('.');
    if (dot >= 0) stem = stem.substring(0, dot);
    stem = stem.toLowerCase(Locale.ROOT);
    for (VmModule module : values()) if (module.moduleName.equals(stem)) return Optional.of(module);
    return Optional.empty();
  }
}

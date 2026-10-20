package dev.bluevista.craftq3.engine.vmabi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Handlers for one module's imports, collected before a {@link VmHost} is built. Handlers are keyed
 * by import entry, not number, so one set serves both ABI profiles. Not thread-safe; build on one
 * thread, then hand to the host.
 */
public final class Bindings {
  private final VmModule module;
  private final Map<VmImport, ImportHandler> handlers = new HashMap<>();

  public Bindings(VmModule module) {
    this.module = Objects.requireNonNull(module);
  }

  public VmModule module() {
    return module;
  }

  /**
   * Binds a handler.
   *
   * @throws IllegalArgumentException for another module's import
   * @throws IllegalStateException when the import is already bound (use {@link #override})
   */
  public Bindings bind(VmImport entry, ImportHandler handler) {
    check(entry);
    Objects.requireNonNull(handler, "handler");
    if (handlers.putIfAbsent(entry, handler) != null)
      throw new IllegalStateException(entry.name() + " is already bound");
    return this;
  }

  /** Binds a handler, replacing any earlier one (e.g. a host refining a shared service). */
  public Bindings override(VmImport entry, ImportHandler handler) {
    check(entry);
    handlers.put(entry, Objects.requireNonNull(handler, "handler"));
    return this;
  }

  public boolean bound(VmImport entry) {
    return handlers.containsKey(entry);
  }

  public ImportHandler handler(VmImport entry) {
    return handlers.get(entry);
  }

  /** A read-only snapshot. */
  public Map<VmImport, ImportHandler> asMap() {
    return Collections.unmodifiableMap(new HashMap<>(handlers));
  }

  private void check(VmImport entry) {
    Objects.requireNonNull(entry, "entry");
    if (entry.module() != module)
      throw new IllegalArgumentException(
          entry.name() + " is a " + entry.module() + " import, not " + module);
  }
}

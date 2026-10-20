package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.Syscalls;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;

/**
 * A loaded game module: the qagame build the server runs for one level. {@link QvmGameProgram}
 * loads the original {@code vm/qagame.qvm}; the server binds the import table of {@link #profile()}
 * and instantiates the module over it.
 */
public interface GameProgram {
  /** The module's name for diagnostics, e.g. {@code baseq3/pak8.pk3!vm/qagame.qvm}. */
  String name();

  /** The ABI the module was compiled against. */
  AbiProfile profile();

  /** A fresh running instance whose traps go to {@code syscalls}. */
  QvmInstance instantiate(Syscalls syscalls);

  /**
   * A fresh running instance in {@code mode} ({@code vm_jit}); programs that are not QVM images
   * ignore the mode.
   */
  default QvmInstance instantiate(Syscalls syscalls, QvmInstance.ExecutionMode mode) {
    return instantiate(syscalls);
  }

  /** Loads the game module for a level; called on the simulation thread at every map spawn. */
  @FunctionalInterface
  interface Loader {
    GameProgram load() throws java.io.IOException;
  }
}

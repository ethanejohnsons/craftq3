package dev.bluevista.craftq3.engine.vm;

import dev.bluevista.craftq3.engine.vm.QvmInstance.ExecutionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base of the VM suites: they run in the interpreter, and again through a {@code Compiled*}
 * subclass that overrides {@link #mode()}, against the same expectations.
 */
abstract class ModeSuite {
  /** The mode every instance made through {@link QvmAssembler} uses in this suite. */
  ExecutionMode mode() {
    return ExecutionMode.INTERPRET;
  }

  boolean compiled() {
    return mode() != ExecutionMode.INTERPRET;
  }

  @BeforeEach
  final void selectMode() {
    QvmAssembler.MODE.set(mode());
  }

  @AfterEach
  final void restoreMode() {
    QvmAssembler.MODE.remove();
  }
}

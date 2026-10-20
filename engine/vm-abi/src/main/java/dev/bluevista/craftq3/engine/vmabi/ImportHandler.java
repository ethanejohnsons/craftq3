package dev.bluevista.craftq3.engine.vmabi;

import dev.bluevista.craftq3.engine.vm.QvmInstance;

/**
 * Implements one import. {@code args} holds the raw argument words (floats as their bits, pointers
 * as guest addresses); it is owned by the VM and valid only during the call. Throwing applies the
 * import's {@link Policy}: a degradable import returns its neutral value, anything else faults.
 */
@FunctionalInterface
public interface ImportHandler {
  int call(QvmInstance vm, int[] args);
}

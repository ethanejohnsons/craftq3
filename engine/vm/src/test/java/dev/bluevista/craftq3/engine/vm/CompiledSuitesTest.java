package dev.bluevista.craftq3.engine.vm;

import dev.bluevista.craftq3.engine.vm.QvmInstance.ExecutionMode;
import org.junit.jupiter.api.Nested;

/** Every VM suite again, with instances in {@link ExecutionMode#COMPILE}. */
final class CompiledSuitesTest {
  @Nested
  final class CompiledOpcodeConformanceTest extends OpcodeConformanceTest {
    @Override
    ExecutionMode mode() {
      return ExecutionMode.COMPILE;
    }
  }

  @Nested
  final class CompiledQvmInstanceTest extends QvmInstanceTest {
    @Override
    ExecutionMode mode() {
      return ExecutionMode.COMPILE;
    }
  }

  @Nested
  final class CompiledQvmSecurityTest extends QvmSecurityTest {
    @Override
    ExecutionMode mode() {
      return ExecutionMode.COMPILE;
    }
  }

  @Nested
  final class CompiledQvmImageTest extends QvmImageTest {
    @Override
    ExecutionMode mode() {
      return ExecutionMode.COMPILE;
    }
  }
}

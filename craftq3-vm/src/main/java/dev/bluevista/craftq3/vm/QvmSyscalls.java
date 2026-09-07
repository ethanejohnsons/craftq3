package dev.bluevista.craftq3.vm;

/** Host capabilities are explicit. The argument snapshot excludes the zero-based syscall number. */
@FunctionalInterface
public interface QvmSyscalls {
  int invoke(QvmMemory memory, int syscall, int[] arguments) throws Exception;
}

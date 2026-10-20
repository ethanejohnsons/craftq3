package dev.bluevista.craftq3.engine.vm;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

/**
 * An image's compiled code, shared by every instance of the image: one method handle per procedure,
 * of type {@code (QvmInstance vm, int ps, int ret, int depth, int base) int}, running either a
 * compiled method or the interpreter. Immutable once published.
 */
final class QvmCode {
  /** The type of every procedure entry point. */
  static final MethodType PROCEDURE =
      MethodType.methodType(
          int.class, QvmInstance.class, int.class, int.class, int.class, int.class);

  private final int[] entries;
  private final MethodHandle[] handles;
  private final long[] compiled;
  private final QvmCompileReport report;

  QvmCode(int[] entries, MethodHandle[] handles, long[] compiled, QvmCompileReport report) {
    this.entries = entries;
    this.handles = handles;
    this.compiled = compiled;
    this.report = report;
  }

  QvmCompileReport report() {
    return report;
  }

  /** Whether the procedure at {@code entry} runs as compiled code. */
  boolean compiled(int entry) {
    return (compiled[entry >>> 6] & 1L << entry) != 0;
  }

  /** Runs the procedure at {@code entry} (an {@code ENTER}) with its frame at {@code ps}. */
  int invoke(int entry, QvmInstance vm, int ps, int ret, int depth, int base) {
    MethodHandle handle = handles[Arrays.binarySearch(entries, entry)];
    try {
      return (int) handle.invokeExact(vm, ps, ret, depth, base);
    } catch (RuntimeException | Error failure) {
      throw failure;
    } catch (Throwable failure) {
      throw new IllegalStateException(failure);
    }
  }

  /**
   * Links calls between compiled classes: the call site of procedure {@code entry} binds to its
   * handle (compiled or interpreted) through the calling class's {@link Linkage}.
   */
  static CallSite link(MethodHandles.Lookup caller, String name, MethodType type, int entry)
      throws IllegalAccessException {
    Linkage linkage = MethodHandles.classData(caller, "_", Linkage.class);
    return new ConstantCallSite(linkage.handle(entry).asType(type));
  }

  /** The class data of compiled classes; filled once every class is defined. */
  static final class Linkage {
    private int[] entries;
    private MethodHandle[] handles;

    void bind(int[] entries, MethodHandle[] handles) {
      this.entries = entries;
      this.handles = handles;
    }

    MethodHandle handle(int entry) {
      if (handles == null) throw new IllegalStateException("Compiled code linked before binding");
      return handles[Arrays.binarySearch(entries, entry)];
    }
  }
}

package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.VmTrapError;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code trap_Print}, {@code trap_Error} and the test prints. Guest text arrives in fragments with
 * embedded newlines ({@code Com_Printf}); complete lines go to the {@link ConsoleOutput} and a
 * trailing partial line waits for its newline (or {@link #flush()}).
 */
public final class PrintServices {
  private final VmModule module;
  private final ConsoleOutput output;
  private final StringBuilder pending = new StringBuilder();

  public PrintServices(VmModule module, ConsoleOutput output) {
    this.module = Objects.requireNonNull(module);
    this.output = Objects.requireNonNull(output);
  }

  public VmModule module() {
    return module;
  }

  /** Prints guest text, emitting each completed line. */
  public void print(String text) {
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != '\n') continue;
      pending.append(text, start, i);
      output.print(pending.toString());
      pending.setLength(0);
      start = i + 1;
    }
    pending.append(text, start, text.length());
  }

  /** Emits a pending partial line, if any. */
  public void flush() {
    if (pending.isEmpty()) return;
    output.print(pending.toString());
    pending.setLength(0);
  }

  /** {@code trap_Print(text)}. */
  public int print(QvmInstance vm, int[] args) {
    print(GuestText.read(vm.memory(), args[0]));
    return 0;
  }

  /** {@code trap_Error(text)}: always throws {@link VmTrapError}. */
  public int error(QvmInstance vm, int[] args) {
    String message = GuestText.read(vm.memory(), args[0]);
    flush();
    throw new VmTrapError(module, message);
  }

  /** {@code trap_TestPrintInt(text, i)}: prints {@code text} then the integer and a newline. */
  public int testPrintInt(QvmInstance vm, int[] args) {
    print(GuestText.read(vm.memory(), args[0]) + args[1] + "\n");
    return 0;
  }

  /** {@code trap_TestPrintFloat(text, f)}: {@code %f} formatting. */
  public int testPrintFloat(QvmInstance vm, int[] args) {
    float value = Float.intBitsToFloat(args[1]);
    print(GuestText.read(vm.memory(), args[0]) + String.format(Locale.ROOT, "%f", value) + "\n");
    return 0;
  }
}

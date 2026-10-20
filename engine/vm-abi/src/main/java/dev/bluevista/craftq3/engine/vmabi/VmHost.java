package dev.bluevista.craftq3.engine.vmabi;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.vm.QvmException;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vm.Syscalls;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The syscall dispatcher shared by the qagame, cgame and ui hosts: maps guest call numbers through
 * an {@link ImportTable} to bound {@link ImportHandler}s and applies the error model.
 *
 * <ul>
 *   <li>Construction fails, listing them, when an {@link Policy#requiresHandler() import that needs
 *       a handler} has none.
 *   <li>A {@link Policy.Kind#STUB} without a handler returns its documented value; a {@link
 *       Policy.Kind#FATAL} import faults the VM.
 *   <li>A handler exception degrades a degradable import: its declared output is cleared, its
 *       neutral value returned, the event counted ({@link #degradations()}) and logged once per
 *       module and number. {@link VmTrapError}, faults of nested {@code vmMain} calls and critical
 *       imports are never degraded.
 *   <li>A number the table does not know is logged once, counted and returns 0, or faults under
 *       {@link UnknownPolicy#FATAL}.
 *   <li>{@link Options#strictSyscalls()} ({@code vm_strictSyscalls 1}, on in tests) turns every
 *       degradation and unknown number into a {@link QvmException}.
 * </ul>
 *
 * Hosts either compose an instance or extend this class (it is open for that). Counters are plain
 * fields: a host belongs to the thread that runs its VM.
 */
public class VmHost implements Syscalls {
  /** What to do with a call number the table does not know. */
  public enum UnknownPolicy {
    /** Log once, count, return 0. */
    DEGRADE,
    /** Fault the VM. */
    FATAL
  }

  /** Dispatcher configuration; set from the host's config, never from system properties. */
  public record Options(boolean strictSyscalls, UnknownPolicy unknownPolicy) {
    /** The cvar a host reads with {@link #fromCvars}. */
    public static final String STRICT_CVAR = "vm_strictSyscalls";

    public Options {
      Objects.requireNonNull(unknownPolicy);
    }

    /** Degrade and continue: the release behaviour. */
    public static Options lenient() {
      return new Options(false, UnknownPolicy.DEGRADE);
    }

    /** Every degradation fails: tests and the compatibility sweep. */
    public static Options strict() {
      return new Options(true, UnknownPolicy.DEGRADE);
    }

    /** Reads {@value #STRICT_CVAR} (absent means 0) from a cvar table. */
    public static Options fromCvars(CvarTable cvars) {
      return new Options(cvars.integer(STRICT_CVAR) != 0, UnknownPolicy.DEGRADE);
    }
  }

  private static final Log LOG = Log.get(VmHost.class);

  private record Slot(VmImport entry, ImportHandler handler, int index) {}

  private final ImportTable table;
  private final Options options;
  private final String module;
  private final Slot[] slots;
  private final VmImport[] indexed;
  private final long[] calls;
  private final long[] degraded;
  private final Map<Integer, Long> unknown = new TreeMap<>();

  /**
   * @throws IllegalStateException naming every import that needs a handler but has none
   */
  public VmHost(ImportTable table, Bindings bindings, Options options) {
    this.table = Objects.requireNonNull(table);
    this.options = Objects.requireNonNull(options);
    Objects.requireNonNull(bindings);
    if (bindings.module() != table.module())
      throw new IllegalArgumentException(
          "Bindings for " + bindings.module() + " cannot serve " + table.module());
    module = table.module().moduleName();
    List<String> missing = new ArrayList<>();
    slots = new Slot[table.span()];
    indexed = table.entries().toArray(VmImport[]::new);
    for (int i = 0; i < indexed.length; i++) {
      VmImport entry = indexed[i];
      ImportHandler handler = bindings.handler(entry);
      if (handler == null && entry.policy().requiresHandler())
        missing.add(entry.name() + " (" + table.numberOf(entry) + ")");
      slots[table.numberOf(entry)] = new Slot(entry, handler, i);
    }
    if (!missing.isEmpty())
      throw new IllegalStateException(
          "Missing "
              + module
              + " import handlers for the "
              + table.profile()
              + " table: "
              + String.join(", ", missing));
    calls = new long[indexed.length];
    degraded = new long[indexed.length];
  }

  public ImportTable table() {
    return table;
  }

  public Options options() {
    return options;
  }

  @Override
  public int call(QvmInstance vm, int number, int[] args) {
    Slot slot = number >= 0 && number < slots.length ? slots[number] : null;
    if (slot == null) return unknown(vm, number);
    calls[slot.index]++;
    Policy policy = slot.entry.policy();
    ImportHandler handler = slot.handler;
    if (handler == null) {
      if (policy.kind() == Policy.Kind.STUB) return policy.value();
      throw new QvmException(
          QvmException.Reason.HOST_FAILURE,
          vm.image().name(),
          -1,
          null,
          number,
          slot.entry.name() + " is fatal: " + policy.reason(),
          null);
    }
    try {
      return handler.call(vm, args);
    } catch (VmTrapError error) {
      throw error;
    } catch (QvmException failure) {
      // A guest pointer rejected by a host memory access degrades; a nested vmMain fault or any
      // other VM failure propagates.
      if (vm.faulted()
          || failure.reason() != QvmException.Reason.MEMORY_BOUNDS
          || !failure.module().isEmpty()) throw failure;
      return degrade(vm, slot, number, args, failure);
    } catch (RuntimeException failure) {
      return degrade(vm, slot, number, args, failure);
    }
  }

  private int degrade(QvmInstance vm, Slot slot, int number, int[] args, RuntimeException failure) {
    Policy policy = slot.entry.policy();
    String description = module + " syscall " + number + " (" + slot.entry.name() + ")";
    if (!policy.degradable() || options.strictSyscalls())
      throw new QvmException(
          QvmException.Reason.HOST_FAILURE,
          vm.image().name(),
          -1,
          null,
          number,
          description
              + (policy.degradable() ? " failed under vm_strictSyscalls: " : " failed: ")
              + failure,
          failure);
    degraded[slot.index]++;
    clearOutput(vm.memory(), policy, args);
    LOG.warnOnce(
        module + ":" + number,
        description
            + " degraded to "
            + policy.value()
            + ": "
            + failure
            + " (further occurrences are counted, not logged)");
    return policy.value();
  }

  private static void clearOutput(QvmMemory memory, Policy policy, int[] args) {
    if (policy.outputArg() < 0) return;
    int address = args[policy.outputArg()];
    if (address == 0) return;
    try {
      if (policy.outputBytes() == Policy.STRING_OUTPUT) memory.writeByte(address, 0);
      else memory.fill(address, policy.outputBytes(), 0);
    } catch (QvmException outside) {
      // The output pointer itself is invalid; there is nothing the guest could read back.
    }
  }

  private int unknown(QvmInstance vm, int number) {
    unknown.merge(number, 1L, Long::sum);
    String description =
        module + " called unknown syscall " + number + " (" + table.profile() + " numbering)";
    if (options.strictSyscalls() || options.unknownPolicy() == UnknownPolicy.FATAL)
      throw new QvmException(
          QvmException.Reason.HOST_FAILURE, vm.image().name(), -1, null, number, description, null);
    LOG.warnOnce(module + ":" + number, description + "; returning 0");
    return 0;
  }

  /**
   * Degradations per import since construction or {@link #resetCounters()}; zero counts omitted.
   */
  public Map<VmImport, Long> degradations() {
    return counts(degraded);
  }

  /** Calls per import (including stubs); zero counts omitted. */
  public Map<VmImport, Long> callCounts() {
    return counts(calls);
  }

  /** Calls per unknown number. */
  public Map<Integer, Long> unknownCalls() {
    return Collections.unmodifiableMap(new TreeMap<>(unknown));
  }

  /** Total degradations plus unknown calls: what the compatibility sweep requires to be zero. */
  public long degradationTotal() {
    long total = 0;
    for (long count : degraded) total += count;
    for (long count : unknown.values()) total += count;
    return total;
  }

  public void resetCounters() {
    java.util.Arrays.fill(calls, 0);
    java.util.Arrays.fill(degraded, 0);
    unknown.clear();
  }

  private Map<VmImport, Long> counts(long[] values) {
    Map<VmImport, Long> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i++) if (values[i] != 0) result.put(indexed[i], values[i]);
    return Collections.unmodifiableMap(result);
  }
}

package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.console.Cvar;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.layout.VmCvarLayout;
import java.util.Objects;
import java.util.Optional;

/**
 * The cvar imports over a {@link CvarTable}. A {@code vmCvar_t.handle} is the table handle.
 *
 * <p>VM writes ({@code trap_Cvar_Set}/{@code SetValue}) are forced like the original's {@code
 * Cvar_Set}, so they bypass LATCH and CHEAT; under {@link WritePolicy#PROTECT_ROM_INIT} (the
 * default) they may not change {@link CvarFlags#ROM} or {@link CvarFlags#INIT} cvars, which the
 * console table has no separate protection flag for. A refused write is logged once and ignored.
 * {@code trap_Cvar_Reset} is a user-level reset, as in the original.
 */
public final class CvarServices {
  /** How VM cvar writes are checked. */
  public enum WritePolicy {
    /** VMs may not change ROM or INIT cvars. */
    PROTECT_ROM_INIT,
    /** Every VM write is forced, as in 1.32. */
    ORIGINAL_FORCED
  }

  private static final Log LOG = Log.get(CvarServices.class);

  private final VmModule module;
  private final CvarTable cvars;
  private final WritePolicy policy;

  public CvarServices(VmModule module, CvarTable cvars, WritePolicy policy) {
    this.module = Objects.requireNonNull(module);
    this.cvars = Objects.requireNonNull(cvars);
    this.policy = Objects.requireNonNull(policy);
  }

  public CvarServices(VmModule module, CvarTable cvars) {
    this(module, cvars, WritePolicy.PROTECT_ROM_INIT);
  }

  public CvarTable cvars() {
    return cvars;
  }

  /** {@code trap_Cvar_Register(vmCvar, name, default, flags)}; a null vmCvar only registers. */
  public int register(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    Cvar cvar = cvars.register(GuestText.read(memory, a[1]), GuestText.read(memory, a[2]), a[3]);
    if (a[0] != 0) store(memory, a[0], cvar);
    return 0;
  }

  /** {@code trap_Cvar_Update(vmCvar)}: refreshes the struct when the cvar changed. */
  public int update(QvmInstance vm, int[] a) {
    if (a[0] == 0) return 0;
    QvmMemory memory = vm.memory();
    int handle = VmCvarLayout.handle(memory, a[0]);
    Cvar cvar = cvars.get(handle);
    if (cvar.modificationCount() != VmCvarLayout.modificationCount(memory, a[0]))
      store(memory, a[0], cvar);
    return 0;
  }

  private void store(QvmMemory memory, int address, Cvar cvar) {
    if (VmCvarLayout.write(memory, address, cvar))
      LOG.warnOnce(
          module.moduleName() + ":cvarlength:" + cvar.name(),
          module.moduleName()
              + " cvar "
              + cvar.name()
              + " exceeds "
              + (VmCvarLayout.STRING_BYTES - 1)
              + " characters; the guest sees it truncated");
  }

  /** {@code trap_Cvar_Set(name, value)}. */
  public int set(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    String name = GuestText.read(memory, a[0]);
    if (writable(name)) cvars.set(name, GuestText.read(memory, a[1]));
    return 0;
  }

  /** ui {@code trap_Cvar_SetValue(name, float)}. */
  public int setValue(QvmInstance vm, int[] a) {
    String name = GuestText.read(vm.memory(), a[0]);
    if (writable(name)) cvars.setValue(name, Float.intBitsToFloat(a[1]));
    return 0;
  }

  /** Whether a VM may write {@code name} under the policy; logs a refusal once. */
  public boolean writable(String name) {
    if (policy == WritePolicy.ORIGINAL_FORCED) return true;
    Optional<Cvar> existing = cvars.find(name);
    if (existing.isEmpty() || !existing.get().hasAny(CvarFlags.ROM | CvarFlags.INIT)) return true;
    LOG.warnOnce(
        module.moduleName() + ":cvarset:" + name.toLowerCase(java.util.Locale.ROOT),
        module.moduleName() + " may not change read-only cvar " + name + "; ignored");
    return false;
  }

  /** ui {@code trap_Cvar_Reset(name)}. */
  public int reset(QvmInstance vm, int[] a) {
    cvars.reset(GuestText.read(vm.memory(), a[0]));
    return 0;
  }

  /** ui {@code trap_Cvar_Create(name, value, flags)}. */
  public int create(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    cvars.register(GuestText.read(memory, a[0]), GuestText.read(memory, a[1]), a[2]);
    return 0;
  }

  /** {@code trap_Cvar_VariableStringBuffer(name, buffer, size)}. */
  public int variableStringBuffer(QvmInstance vm, int[] a) {
    QvmMemory memory = vm.memory();
    GuestText.write(memory, a[1], cvars.string(GuestText.read(memory, a[0])), a[2]);
    return 0;
  }

  /** qagame {@code trap_Cvar_VariableIntegerValue(name)}. */
  public int variableIntegerValue(QvmInstance vm, int[] a) {
    return cvars.integer(GuestText.read(vm.memory(), a[0]));
  }

  /** ui {@code trap_Cvar_VariableValue(name)}: float bits. */
  public int variableValue(QvmInstance vm, int[] a) {
    return Float.floatToRawIntBits(cvars.value(GuestText.read(vm.memory(), a[0])));
  }

  /** ui {@code trap_Cvar_InfoStringBuffer(bit, buffer, size)}. */
  public int infoStringBuffer(QvmInstance vm, int[] a) {
    int capacity =
        a[0] == CvarFlags.SYSTEMINFO ? CvarTable.BIG_INFO_CAPACITY : CvarTable.INFO_CAPACITY;
    GuestText.write(vm.memory(), a[1], cvars.info(a[0], capacity), a[2]);
    return 0;
  }

  /** qagame {@code trap_GetServerinfo(buffer, size)}: the serverinfo cvars. */
  public int serverInfo(QvmInstance vm, int[] a) {
    GuestText.write(vm.memory(), a[0], cvars.info(CvarFlags.SERVERINFO), a[1]);
    return 0;
  }
}

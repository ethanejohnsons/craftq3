package dev.bluevista.craftq3.engine.vmabi.service;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandBuffer;
import dev.bluevista.craftq3.engine.console.CommandRegistry;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Command imports over a {@link CommandRegistry} and {@link CommandBuffer}.
 *
 * <ul>
 *   <li>{@code argc}/{@code argv}/{@code args} read the command being dispatched ({@link
 *       CommandRegistry#current()}), or arguments a host pushes around a {@code vmMain} call with
 *       {@link #withArgs} (for example a client command the server tokenized).
 *   <li>{@code trap_AddCommand} registers a forwarding command: running it calls the VM's console
 *       command entry point, and when the VM returns 0 the command goes to the {@code unhandled}
 *       consumer (the cgame host forwards it to the server, as {@code CL_ForwardCommandToServer}).
 *       Commands a VM added are removed by {@code trap_RemoveCommand} or {@link #removeAll()}.
 *   <li>{@code trap_SendConsoleCommand}/{@code trap_Cmd_ExecuteText} use the buffer's {@code
 *       exec_when} semantics.
 * </ul>
 */
public final class CommandServices {
  private static final Log LOG = Log.get(CommandServices.class);

  /** An argument scope from {@link #withArgs}; closing restores the previous arguments. */
  public interface ArgsScope extends AutoCloseable {
    @Override
    void close();
  }

  private final VmModule module;
  private final CommandRegistry registry;
  private final CommandBuffer buffer;
  private final int consoleCommandEntry;
  private final Consumer<CommandArgs> unhandled;
  private final Deque<CommandArgs> pushed = new ArrayDeque<>();
  private final Map<String, String> added = new LinkedHashMap<>();

  /**
   * @param consoleCommandEntry the {@code vmMain} command run for a VM-added command ({@code
   *     CG_CONSOLE_COMMAND} for cgame)
   * @param unhandled receives a VM-added command the VM declined
   */
  public CommandServices(
      VmModule module,
      CommandBuffer buffer,
      int consoleCommandEntry,
      Consumer<CommandArgs> unhandled) {
    this.module = Objects.requireNonNull(module);
    this.buffer = Objects.requireNonNull(buffer);
    this.registry = buffer.registry();
    this.consoleCommandEntry = consoleCommandEntry;
    this.unhandled = Objects.requireNonNull(unhandled);
  }

  public CommandRegistry registry() {
    return registry;
  }

  /** The arguments the argument imports see. */
  public CommandArgs current() {
    CommandArgs top = pushed.peek();
    return top != null ? top : registry.current();
  }

  /** Makes {@code args} current until the returned scope is closed. */
  public ArgsScope withArgs(CommandArgs args) {
    pushed.push(Objects.requireNonNull(args));
    return () -> {
      if (pushed.peek() != args)
        throw new IllegalStateException("Argument scopes closed out of order");
      pushed.pop();
    };
  }

  /** {@code trap_Argc()}. */
  public int argc(QvmInstance vm, int[] a) {
    return current().argc();
  }

  /** {@code trap_Argv(n, buffer, size)}. */
  public int argv(QvmInstance vm, int[] a) {
    GuestText.write(vm.memory(), a[1], current().argv(a[0]), a[2]);
    return 0;
  }

  /** cgame {@code trap_Args(buffer, size)}: arguments 1 onwards. */
  public int args(QvmInstance vm, int[] a) {
    GuestText.write(vm.memory(), a[0], current().args(), a[1]);
    return 0;
  }

  /** qagame {@code trap_SendConsoleCommand(exec_when, text)}, ui {@code trap_Cmd_ExecuteText}. */
  public int executeText(QvmInstance vm, int[] a) {
    if (a[0] < 0 || a[0] >= CommandBuffer.When.values().length)
      throw new IllegalArgumentException("Invalid exec_when " + a[0]);
    buffer.execute(CommandBuffer.When.values()[a[0]], GuestText.read(vm.memory(), a[1]));
    return 0;
  }

  /** cgame {@code trap_SendConsoleCommand(text)}: appends to the buffer. */
  public int addText(QvmInstance vm, int[] a) {
    buffer.add(GuestText.read(vm.memory(), a[0]));
    return 0;
  }

  /** cgame {@code trap_AddCommand(name)}. */
  public int addCommand(QvmInstance vm, int[] a) {
    String name = GuestText.read(vm.memory(), a[0]);
    String key = name.toLowerCase(Locale.ROOT);
    if (added.containsKey(key)) return 0;
    if (registry.register(name, args -> forward(vm, args))) added.put(key, name);
    return 0;
  }

  private void forward(QvmInstance vm, CommandArgs args) {
    if (vm.faulted()) {
      LOG.warnOnce(
          module.moduleName() + ":faulted-command",
          module.moduleName() + " has faulted; its command " + args.name() + " is ignored");
      return;
    }
    if (vm.vmMain(consoleCommandEntry) == 0) unhandled.accept(args);
  }

  /** cgame {@code trap_RemoveCommand(name)}: only commands this VM added. */
  public int removeCommand(QvmInstance vm, int[] a) {
    String name = GuestText.read(vm.memory(), a[0]);
    if (added.remove(name.toLowerCase(Locale.ROOT)) != null) registry.unregister(name);
    return 0;
  }

  /** Unregisters every command the VM added (call at VM shutdown). */
  public void removeAll() {
    for (String name : added.values()) registry.unregister(name);
    added.clear();
  }

  /** Names of the commands the VM added, in registration order. */
  public List<String> addedCommands() {
    return new ArrayList<>(added.values());
  }

  /** The cvar table behind the registry. */
  public CvarTable cvars() {
    return registry.cvars();
  }
}

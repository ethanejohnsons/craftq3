package dev.bluevista.craftq3.engine.vmabi.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.console.CommandBuffer;
import dev.bluevista.craftq3.engine.console.CommandParser;
import dev.bluevista.craftq3.engine.console.CommandRegistry;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.vm.Opcode;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.TestVm;
import dev.bluevista.craftq3.engine.vmabi.VmExports;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class CommandServicesTest {
  private static final int TEXT = 0x400, BUFFER = 0x600;

  private final List<String> output = new ArrayList<>();
  private final CvarTable cvars = new CvarTable(new ThreadOwner("test console"));
  private final CommandRegistry registry = new CommandRegistry(cvars, output::add);
  private final CommandBuffer buffer = new CommandBuffer(registry);
  private final List<CommandArgs> forwarded = new ArrayList<>();
  private final CommandServices services =
      new CommandServices(
          VmModule.CGAME, buffer, VmExports.Cgame.CG_CONSOLE_COMMAND.command(), forwarded::add);

  private static int[] args(int... values) {
    int[] padded = new int[16];
    System.arraycopy(values, 0, padded, 0, values.length);
    return padded;
  }

  /** A VM whose vmMain returns {@code result}. */
  private static QvmInstance returning(int result) {
    return new TestVm()
        .op(Opcode.ENTER, 8)
        .op(Opcode.CONST, result)
        .op(Opcode.LEAVE, 8)
        .vm((vm, number, a) -> 0);
  }

  @Test
  void argumentsComeFromTheDispatchedCommandOrAPushedScope() {
    QvmInstance vm = TestVm.instance();
    QvmMemory memory = vm.memory();
    List<String> seen = new ArrayList<>();
    registry.register(
        "probe",
        a -> {
          seen.add(Integer.toString(services.argc(vm, args())));
          services.argv(vm, args(1, BUFFER, 64));
          seen.add(memory.readString(BUFFER, 64));
          services.args(vm, args(BUFFER, 64));
          seen.add(memory.readString(BUFFER, 64));
        });
    registry.execute("probe \"two words\" three");
    assertEquals(List.of("3", "two words", "two words three"), seen);
    assertEquals(0, services.argc(vm, args()));

    CommandServices.ArgsScope scope = services.withArgs(CommandParser.tokenize("say hello"));
    assertEquals(2, services.argc(vm, args()));
    services.argv(vm, args(0, BUFFER, 3));
    assertEquals("sa", memory.readString(BUFFER, 64));
    services.argv(vm, args(7, BUFFER, 64));
    assertEquals("", memory.readString(BUFFER, 64));
    scope.close();
    assertEquals(0, services.argc(vm, args()));
  }

  @Test
  void addedCommandsForwardToTheVmAndThenToTheServer() {
    QvmInstance handles = returning(1);
    handles.memory().writeString(TEXT, "+scores", 64);
    services.addCommand(handles, args(TEXT));
    assertTrue(registry.exists("+scores"));
    registry.execute("+scores");
    assertTrue(forwarded.isEmpty());

    QvmInstance declines = returning(0);
    declines.memory().writeString(TEXT, "tell_target", 64);
    services.addCommand(declines, args(TEXT));
    registry.execute("tell_target hi");
    assertEquals(1, forwarded.size());
    assertEquals("hi", forwarded.getFirst().argv(1));
    assertEquals(List.of("+scores", "tell_target"), services.addedCommands());

    declines.memory().writeString(TEXT, "TELL_TARGET", 64);
    services.removeCommand(declines, args(TEXT));
    assertFalse(registry.exists("tell_target"));
    services.removeAll();
    assertFalse(registry.exists("+scores"));
    assertTrue(services.addedCommands().isEmpty());
  }

  @Test
  void removeCommandLeavesOtherOwnersAlone() {
    registry.register("quit", a -> {});
    QvmInstance vm = TestVm.instance();
    vm.memory().writeString(TEXT, "quit", 64);
    services.addCommand(vm, args(TEXT));
    services.removeCommand(vm, args(TEXT));
    assertTrue(registry.exists("quit"));
  }

  @Test
  void consoleCommandsHonourExecWhen() {
    AtomicInteger runs = new AtomicInteger();
    registry.register("count", a -> runs.incrementAndGet());
    QvmInstance vm = TestVm.instance();
    vm.memory().writeString(TEXT, "count", 64);
    services.executeText(vm, args(CommandBuffer.When.NOW.ordinal(), TEXT));
    assertEquals(1, runs.get());
    vm.memory().writeString(TEXT, "count\n", 64);
    services.executeText(vm, args(CommandBuffer.When.APPEND.ordinal(), TEXT));
    services.executeText(vm, args(CommandBuffer.When.INSERT.ordinal(), TEXT));
    services.addText(vm, args(TEXT));
    assertEquals(1, runs.get());
    buffer.execute();
    assertEquals(4, runs.get());
    assertThrows(IllegalArgumentException.class, () -> services.executeText(vm, args(3, TEXT)));
  }
}

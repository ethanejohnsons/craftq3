package dev.bluevista.craftq3.engine.vmabi.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.console.CommandBuffer;
import dev.bluevista.craftq3.engine.console.CommandRegistry;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.vm.QvmException;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.Bindings;
import dev.bluevista.craftq3.engine.vmabi.CgameImport;
import dev.bluevista.craftq3.engine.vmabi.ImportTable;
import dev.bluevista.craftq3.engine.vmabi.Policy;
import dev.bluevista.craftq3.engine.vmabi.QagameImport;
import dev.bluevista.craftq3.engine.vmabi.TestVm;
import dev.bluevista.craftq3.engine.vmabi.UiImport;
import dev.bluevista.craftq3.engine.vmabi.VmHost;
import dev.bluevista.craftq3.engine.vmabi.VmImport;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.VmTrapError;
import dev.bluevista.craftq3.engine.vmabi.layout.QTimeLayout;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class SharedServicesTest {
  private final List<String> output = new ArrayList<>();
  private final CvarTable cvars = new CvarTable(new ThreadOwner("test"));
  private final CommandBuffer buffer = new CommandBuffer(new CommandRegistry(cvars, output::add));
  private final ManualClock clock = new ManualClock(5_000);
  private final java.time.Clock wall =
      java.time.Clock.fixed(Instant.parse("2000-06-15T12:30:45Z"), ZoneId.of("Europe/Berlin"));

  private static final VmFiles NO_FILES =
      new VmFiles() {
        public int length(String path) {
          return -1;
        }

        public Opened open(String path, Mode mode) throws IOException {
          throw new IOException("no files");
        }

        public byte[] read(int handle, int length) throws IOException {
          throw new IOException("bad handle");
        }

        public int write(int handle, byte[] data) throws IOException {
          throw new IOException("bad handle");
        }

        public boolean seek(int handle, int offset, Origin origin) throws IOException {
          throw new IOException("bad handle");
        }

        public void close(int handle) throws IOException {
          throw new IOException("bad handle");
        }

        public List<String> list(String directory, String extension) {
          return List.of();
        }
      };

  private SharedServices services(VmModule module) {
    return SharedServices.create(
        module,
        output::add,
        new TimeServices(clock, wall),
        buffer,
        CvarServices.WritePolicy.PROTECT_ROM_INIT,
        NO_FILES,
        args -> output.add("forward " + args.text()));
  }

  @ParameterizedTest
  @EnumSource(VmModule.class)
  void installBindsEverySharedImport(VmModule module) {
    Bindings bindings = services(module).install(new Bindings(module));
    for (AbiProfile profile : AbiProfile.values())
      for (VmImport entry : ImportTable.of(module, profile).entries())
        assertEquals(
            entry.policy().kind() == Policy.Kind.SHARED,
            bindings.bound(entry),
            module + " " + entry.name());
  }

  private VmHost host(VmModule module) {
    Bindings bindings = services(module).install(new Bindings(module));
    for (VmImport entry : ImportTable.of(module, AbiProfile.Q3_132).entries())
      if (entry.policy().requiresHandler() && !bindings.bound(entry))
        bindings.bind(entry, (vm, a) -> 0);
    return new VmHost(ImportTable.of(module, AbiProfile.Q3_132), bindings, VmHost.Options.strict());
  }

  @Test
  void printEmitsWholeLinesAndErrorStopsTheVm() {
    VmHost host = host(VmModule.CGAME);
    QvmInstance vm = TestVm.caller(host, CgameImport.CG_PRINT.number(), 0x100);
    vm.memory().writeString(0x100, "one\ntwo ", 64);
    vm.vmMain(0);
    assertEquals(List.of("one"), output);
    vm.memory().writeString(0x100, "three\n", 64);
    vm.vmMain(0);
    assertEquals(List.of("one", "two three"), output);

    QvmInstance failing = TestVm.caller(host, CgameImport.CG_ERROR.number(), 0x100);
    failing.memory().writeString(0x100, "CG_Init: bad", 64);
    QvmException fault = assertThrows(QvmException.class, () -> failing.vmMain(0));
    VmTrapError error = assertInstanceOf(VmTrapError.class, fault.getCause());
    assertEquals(VmModule.CGAME, error.module());
    assertEquals("CG_Init: bad", error.guestMessage());
  }

  @Test
  void testPrintsFormatLikeTheOriginal() {
    VmHost host = host(VmModule.QAGAME);
    QvmInstance vm =
        TestVm.caller(
            host, QagameImport.TRAP_TESTPRINTFLOAT.number(), 0x100, Float.floatToRawIntBits(1.5f));
    vm.memory().writeString(0x100, "value: ", 64);
    vm.vmMain(0);
    TestVm.caller(host, QagameImport.TRAP_TESTPRINTINT.number(), 0x100, -7).vmMain(0);
    // The second caller is a fresh VM whose string buffer is still empty.
    assertEquals(List.of("value: 1.500000", "-7"), output);
  }

  @Test
  void millisecondsCountFromServiceCreationAndRealTimeFillsQTime() {
    VmHost host = host(VmModule.UI);
    clock.advance(250);
    assertEquals(250, TestVm.caller(host, UiImport.UI_MILLISECONDS.number()).vmMain(0));
    QvmInstance vm = TestVm.caller(host, UiImport.UI_REAL_TIME.number(), 0x200);
    int seconds = vm.vmMain(0);
    assertEquals(Instant.parse("2000-06-15T12:30:45Z").getEpochSecond(), seconds);
    var memory = vm.memory();
    assertEquals(14, memory.readInt(0x200 + QTimeLayout.TM_HOUR), "Berlin summer time");
    assertEquals(5, memory.readInt(0x200 + QTimeLayout.TM_MON));
    assertEquals(100, memory.readInt(0x200 + QTimeLayout.TM_YEAR));
    assertEquals(4, memory.readInt(0x200 + QTimeLayout.TM_WDAY));
    assertEquals(1, memory.readInt(0x200 + QTimeLayout.TM_ISDST));
    assertEquals(seconds, TestVm.caller(host, UiImport.UI_REAL_TIME.number(), 0).vmMain(0));
  }

  @Test
  void missingFilesAreNotDegradationsButBadHandlesAre() {
    VmHost host = host(VmModule.UI);
    QvmInstance open = TestVm.caller(host, UiImport.UI_FS_FOPENFILE.number(), 0x100, 0x200, 0);
    open.memory().writeString(0x100, "menu/main.menu", 64);
    assertEquals(-1, open.vmMain(0));
    assertTrue(host.degradations().isEmpty());
    // Handle 0 (left by a failed open) is benign even in strict mode, as in the original.
    assertEquals(0, TestVm.caller(host, UiImport.UI_FS_READ.number(), 0x100, 4, 0).vmMain(0));
    assertEquals(0, TestVm.caller(host, UiImport.UI_FS_WRITE.number(), 0x100, 4, 0).vmMain(0));
    assertEquals(0, TestVm.caller(host, UiImport.UI_FS_FCLOSEFILE.number(), 0).vmMain(0));
    assertTrue(host.degradations().isEmpty());
    // Strict mode: a bad handle is a guest error and faults.
    assertThrows(
        QvmException.class,
        () -> TestVm.caller(host, UiImport.UI_FS_FCLOSEFILE.number(), 5).vmMain(0));
  }

  @Test
  void cgameCommandsRunThroughTheSharedServices() {
    SharedServices shared = services(VmModule.CGAME);
    Bindings bindings = shared.install(new Bindings(VmModule.CGAME));
    for (VmImport entry : ImportTable.of(VmModule.CGAME, AbiProfile.Q3_132).entries())
      if (entry.policy().requiresHandler() && !bindings.bound(entry))
        bindings.bind(entry, (vm, a) -> 0);
    VmHost host =
        new VmHost(
            ImportTable.of(VmModule.CGAME, AbiProfile.Q3_132), bindings, VmHost.Options.strict());
    QvmInstance vm = TestVm.caller(host, CgameImport.CG_ADDCOMMAND.number(), 0x100);
    vm.memory().writeString(0x100, "tcmd", 64);
    vm.vmMain(0);
    assertEquals(List.of("tcmd"), shared.commands().addedCommands());
    // The caller VM's vmMain returns the trap's result (0), so the command is forwarded.
    buffer.registry().execute("tcmd arg");
    assertEquals(List.of("forward tcmd arg"), output);
  }
}

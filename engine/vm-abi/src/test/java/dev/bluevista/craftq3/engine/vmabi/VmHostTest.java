package dev.bluevista.craftq3.engine.vmabi;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.vm.QvmException;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class VmHostTest {
  private final List<String> warnings = new ArrayList<>();
  private Log.Sink previous;

  @BeforeEach
  void captureLog() {
    Log.resetOnce();
    previous =
        Log.install(
            (name, level, message, error) -> {
              if (level == Log.Level.WARN) warnings.add(message);
            });
  }

  @AfterEach
  void restoreLog() {
    Log.install(previous);
    Log.resetOnce();
  }

  /** Binds every UI import that needs a handler to one returning {@code value}. */
  private static Bindings allUi(int value) {
    Bindings bindings = new Bindings(VmModule.UI);
    for (VmImport entry : ImportTable.of(VmModule.UI, AbiProfile.Q3_132).entries())
      if (entry.policy().requiresHandler()) bindings.bind(entry, (vm, args) -> value);
    return bindings;
  }

  private static VmHost uiHost(Bindings bindings, VmHost.Options options) {
    return new VmHost(ImportTable.of(VmModule.UI, AbiProfile.Q3_132), bindings, options);
  }

  private static int call(VmHost host, VmImport entry, int... args) {
    int number = host.table().numberOf(entry);
    return TestVm.caller(host, number, args).vmMain(0);
  }

  @Test
  void missingHandlersFailFastAndAreListed() {
    Bindings bindings = allUi(0);
    Bindings partial = new Bindings(VmModule.UI);
    bindings.asMap().forEach(partial::bind);
    var fresh = new Bindings(VmModule.UI);
    for (var entry : bindings.asMap().entrySet())
      if (entry.getKey() != UiImport.UI_GETGLCONFIG && entry.getKey() != UiImport.UI_PRINT)
        fresh.bind(entry.getKey(), entry.getValue());
    var failure =
        assertThrows(IllegalStateException.class, () -> uiHost(fresh, VmHost.Options.strict()));
    assertTrue(failure.getMessage().contains("UI_GETGLCONFIG (43)"), failure.getMessage());
    assertTrue(failure.getMessage().contains("UI_PRINT (1)"), failure.getMessage());
    assertNotNull(uiHost(partial, VmHost.Options.strict()));
  }

  @Test
  void retailTablesRequireTheirOwnImportsOnly() {
    Bindings bindings = allUi(0);
    var failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                new VmHost(
                    ImportTable.of(VmModule.UI, AbiProfile.RETAIL_1999),
                    bindings,
                    VmHost.Options.lenient()));
    assertTrue(failure.getMessage().contains("RETAIL_UI_LAN_GETLOCALSERVERCOUNT"));
    bindings.bind(RetailImport.RETAIL_UI_LAN_GETLOCALSERVERCOUNT, (vm, a) -> 7);
    bindings.bind(RetailImport.RETAIL_UI_LAN_GETLOCALSERVERADDRESSSTRING, (vm, a) -> 0);
    bindings.bind(RetailImport.RETAIL_UI_LAN_GETGLOBALSERVERCOUNT, (vm, a) -> 0);
    bindings.bind(RetailImport.RETAIL_UI_LAN_GETGLOBALSERVERADDRESSSTRING, (vm, a) -> 0);
    var host =
        new VmHost(
            ImportTable.of(VmModule.UI, AbiProfile.RETAIL_1999), bindings, VmHost.Options.strict());
    assertEquals(7, TestVm.caller(host, 46).vmMain(0));
  }

  @Test
  void bindingsRejectOtherModulesAndDuplicates() {
    Bindings bindings = new Bindings(VmModule.CGAME);
    assertThrows(
        IllegalArgumentException.class, () -> bindings.bind(UiImport.UI_PRINT, (vm, a) -> 0));
    bindings.bind(CgameImport.CG_PRINT, (vm, a) -> 0);
    assertThrows(
        IllegalStateException.class, () -> bindings.bind(CgameImport.CG_PRINT, (vm, a) -> 1));
    bindings.override(CgameImport.CG_PRINT, (vm, a) -> 2);
    assertEquals(2, bindings.handler(CgameImport.CG_PRINT).call(null, new int[16]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VmHost(
                ImportTable.of(VmModule.UI, AbiProfile.Q3_132),
                bindings,
                VmHost.Options.lenient()));
  }

  @Test
  void handlersReceiveArgumentsAndReturnValues() {
    Bindings bindings = allUi(0);
    bindings.override(UiImport.UI_LAN_COMPARESERVERS, (vm, a) -> a[0] + a[1] * a[2]);
    VmHost host = uiHost(bindings, VmHost.Options.strict());
    assertEquals(3 + 4 * 5, call(host, UiImport.UI_LAN_COMPARESERVERS, 3, 4, 5));
    assertEquals(Map.of(UiImport.UI_LAN_COMPARESERVERS, 1L), host.callCounts());
  }

  @Test
  void stubsAnswerWithoutHandlersAndMayBeOverridden() {
    VmHost host = uiHost(allUi(0), VmHost.Options.strict());
    assertEquals(ImportConstants.CD_KEY_VALID, call(host, UiImport.UI_VERIFY_CDKEY));
    assertEquals(ImportConstants.MEMORY_REMAINING, call(host, UiImport.UI_MEMORY_REMAINING));
    Bindings bindings = allUi(0);
    bindings.bind(UiImport.UI_VERIFY_CDKEY, (vm, a) -> 0);
    assertEquals(0, call(uiHost(bindings, VmHost.Options.strict()), UiImport.UI_VERIFY_CDKEY));
    assertTrue(host.degradations().isEmpty());
    assertEquals(0, host.degradationTotal());
  }

  @Test
  void failingHandlersDegradeOnceLoggedAndCounted() {
    Bindings bindings = allUi(0);
    bindings.override(
        UiImport.UI_GETCONFIGSTRING,
        (vm, a) -> {
          throw new IllegalStateException("no such configstring");
        });
    VmHost host = uiHost(bindings, VmHost.Options.lenient());
    int buffer = 0x200;
    QvmInstance vm = TestVm.caller(host, 45, 3, buffer, 64);
    vm.memory().writeString(buffer, "stale", 64);
    assertEquals(0, vm.vmMain(0));
    assertEquals(0, vm.memory().readUnsignedByte(buffer), "output string cleared");
    assertEquals(0, vm.vmMain(0));
    assertEquals(Map.of(UiImport.UI_GETCONFIGSTRING, 2L), host.degradations());
    assertEquals(2, host.degradationTotal());
    assertEquals(1, warnings.size(), warnings.toString());
    String warning = warnings.getFirst();
    assertTrue(warning.contains("ui syscall 45 (UI_GETCONFIGSTRING)"), warning);
    assertTrue(warning.contains("no such configstring"), warning);
    host.resetCounters();
    assertTrue(host.degradations().isEmpty());
  }

  @Test
  void neutralValuesAndStructOutputsApplyOnDegradation() {
    Bindings bindings = new Bindings(VmModule.QAGAME);
    for (VmImport entry : ImportTable.of(VmModule.QAGAME, AbiProfile.Q3_132).entries())
      if (entry.policy().requiresHandler())
        bindings.bind(
            entry,
            (vm, a) -> {
              throw new UnsupportedOperationException("not here");
            });
    VmHost host =
        new VmHost(
            ImportTable.of(VmModule.QAGAME, AbiProfile.Q3_132), bindings, VmHost.Options.lenient());
    int trace = 0x300;
    QvmInstance vm = TestVm.caller(host, QagameImport.G_TRACE.number(), trace);
    vm.memory().fill(trace, 64, 0x7f);
    assertEquals(0, vm.vmMain(0));
    for (int i = 0; i < 56; i++) assertEquals(0, vm.memory().readUnsignedByte(trace + i));
    assertEquals(0x7f, vm.memory().readUnsignedByte(trace + 56));
    assertEquals(-1, TestVm.caller(host, QagameImport.G_FS_FOPEN_FILE.number()).vmMain(0));
    assertEquals(
        -1, TestVm.caller(host, BotlibImport.BOTLIB_AI_GET_LEVEL_ITEM_GOAL.number()).vmMain(0));
  }

  @Test
  void strictModeTurnsDegradationIntoAVmFault() {
    Bindings bindings = allUi(0);
    bindings.override(
        UiImport.UI_LAN_GETSERVERCOUNT,
        (vm, a) -> {
          throw new IllegalArgumentException("bad source");
        });
    VmHost host = uiHost(bindings, VmHost.Options.strict());
    QvmInstance vm = TestVm.caller(host, 65, 9);
    QvmException fault = assertThrows(QvmException.class, () -> vm.vmMain(0));
    assertEquals(QvmException.Reason.HOST_FAILURE, fault.reason());
    assertEquals(65, fault.syscall());
    assertTrue(fault.getMessage().contains("vm_strictSyscalls"), fault.getMessage());
    assertInstanceOf(IllegalArgumentException.class, fault.getCause());
    assertTrue(vm.faulted());
  }

  @Test
  void criticalImportsNeverDegrade() {
    Bindings bindings = allUi(0);
    bindings.override(
        UiImport.UI_GETGLCONFIG,
        (vm, a) -> {
          throw new IllegalStateException("no renderer");
        });
    VmHost host = uiHost(bindings, VmHost.Options.lenient());
    QvmException fault =
        assertThrows(QvmException.class, () -> call(host, UiImport.UI_GETGLCONFIG, 0x100));
    assertTrue(fault.getMessage().contains("UI_GETGLCONFIG"));
    assertTrue(host.degradations().isEmpty());
  }

  @Test
  void trapErrorPassesThroughToTheHost() {
    Bindings bindings = allUi(0);
    bindings.override(
        UiImport.UI_ERROR,
        (vm, a) -> {
          throw new VmTrapError(VmModule.UI, "menu file missing");
        });
    VmHost host = uiHost(bindings, VmHost.Options.lenient());
    QvmException fault = assertThrows(QvmException.class, () -> call(host, UiImport.UI_ERROR));
    VmTrapError error = assertInstanceOf(VmTrapError.class, fault.getCause());
    assertEquals("menu file missing", error.guestMessage());
    assertTrue(host.degradations().isEmpty());
  }

  @Test
  void guestPointersOutsideMemoryDegrade() {
    Bindings bindings = allUi(0);
    bindings.override(
        UiImport.UI_GETCLIENTSTATE,
        (vm, a) -> {
          vm.memory().fill(a[0], 3084, 0);
          return 1;
        });
    VmHost host = uiHost(bindings, VmHost.Options.lenient());
    assertEquals(0, call(host, UiImport.UI_GETCLIENTSTATE, TestVm.MEMORY - 16));
    assertEquals(1, host.degradations().get(UiImport.UI_GETCLIENTSTATE));
    assertEquals(1, call(host, UiImport.UI_GETCLIENTSTATE, 0x100));
  }

  @Test
  void unknownNumbersReturnZeroOrFault() {
    VmHost lenient = uiHost(allUi(0), VmHost.Options.lenient());
    QvmInstance vm = TestVm.caller(lenient, 99);
    assertEquals(0, vm.vmMain(0));
    assertEquals(0, vm.vmMain(0));
    assertEquals(Map.of(99, 2L), lenient.unknownCalls());
    assertEquals(2, lenient.degradationTotal());
    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().contains("unknown syscall 99"), warnings.getFirst());

    VmHost strict = uiHost(allUi(0), VmHost.Options.strict());
    assertThrows(QvmException.class, () -> TestVm.caller(strict, 99).vmMain(0));
    VmHost fatal = uiHost(allUi(0), new VmHost.Options(false, VmHost.UnknownPolicy.FATAL));
    assertThrows(QvmException.class, () -> TestVm.caller(fatal, 5000).vmMain(0));
  }

  @Test
  void fatalPolicyFaults() {
    Policy fatal = Policy.fatal("cannot be honoured");
    assertEquals(Policy.Kind.FATAL, fatal.kind());
    assertFalse(fatal.requiresHandler());
    assertTrue(fatal.describe().contains("cannot be honoured"));
  }

  @Test
  void optionsReadTheStrictCvar() {
    var cvars =
        new dev.bluevista.craftq3.engine.console.CvarTable(
            new dev.bluevista.craftq3.engine.common.thread.ThreadOwner("test"));
    assertFalse(VmHost.Options.fromCvars(cvars).strictSyscalls());
    cvars.set(VmHost.Options.STRICT_CVAR, "1");
    assertTrue(VmHost.Options.fromCvars(cvars).strictSyscalls());
  }
}

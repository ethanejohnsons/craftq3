package dev.bluevista.craftq3.engine.vmabi.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.CvarFlags;
import dev.bluevista.craftq3.engine.console.CvarTable;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.TestVm;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.layout.VmCvarLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class CvarServicesTest {
  private static final int VMCVAR = 0x100, NAME = 0x400, VALUE = 0x500, BUFFER = 0x600;

  private final CvarTable cvars = new CvarTable(new ThreadOwner("test cvars"));
  private final CvarServices services = new CvarServices(VmModule.QAGAME, cvars);
  private final QvmInstance vm = TestVm.instance();
  private final QvmMemory memory = vm.memory();
  private Log.Sink previous;

  @BeforeEach
  void quietLog() {
    Log.resetOnce();
    previous = Log.install((name, level, message, error) -> {});
  }

  @AfterEach
  void restoreLog() {
    Log.install(previous);
    Log.resetOnce();
  }

  private static int[] args(int... values) {
    int[] padded = new int[16];
    System.arraycopy(values, 0, padded, 0, values.length);
    return padded;
  }

  private void text(int address, String value) {
    memory.writeString(address, value, 256);
  }

  @Test
  void registerFillsTheVmCvarAndUpdateTracksChanges() {
    text(NAME, "g_speed");
    text(VALUE, "320");
    services.register(vm, args(VMCVAR, NAME, VALUE, CvarFlags.SERVERINFO));
    int handle = cvars.find("g_speed").orElseThrow().handle();
    assertEquals(handle, VmCvarLayout.handle(memory, VMCVAR));
    assertEquals(320, memory.readInt(VMCVAR + VmCvarLayout.INTEGER));
    assertEquals("320", VmCvarLayout.string(memory, VMCVAR));
    assertTrue(cvars.find("g_speed").orElseThrow().hasAny(CvarFlags.SERVERINFO));

    int count = VmCvarLayout.modificationCount(memory, VMCVAR);
    services.update(vm, args(VMCVAR));
    assertEquals(count, VmCvarLayout.modificationCount(memory, VMCVAR));
    cvars.set("g_speed", "400");
    services.update(vm, args(VMCVAR));
    assertEquals("400", VmCvarLayout.string(memory, VMCVAR));
    assertEquals(400f, memory.readFloat(VMCVAR + VmCvarLayout.VALUE));
    assertEquals(count + 1, VmCvarLayout.modificationCount(memory, VMCVAR));

    services.register(vm, args(0, NAME, VALUE, 0));
    memory.writeInt(VMCVAR, 9999);
    assertThrows(IllegalArgumentException.class, () -> services.update(vm, args(VMCVAR)));
  }

  @Test
  void vmSetsAreForcedButCannotTouchReadOnlyCvars() {
    cvars.register("g_gametype", "0", CvarFlags.LATCH | CvarFlags.SERVERINFO);
    cvars.register("sv_cheatprotected", "0", CvarFlags.CHEAT);
    cvars.register("version", "Q3 1.32", CvarFlags.ROM);
    cvars.register("fs_game", "", CvarFlags.INIT);
    cvars.set(CvarTable.CHEATS, "0");
    for (String name : new String[] {"g_gametype", "sv_cheatprotected", "version", "fs_game"}) {
      text(NAME, name);
      text(VALUE, "4");
      services.set(vm, args(NAME, VALUE));
    }
    assertEquals("4", cvars.string("g_gametype"), "VM writes bypass LATCH");
    assertEquals("4", cvars.string("sv_cheatprotected"), "VM writes bypass CHEAT");
    assertEquals("Q3 1.32", cvars.string("version"));
    assertEquals("", cvars.string("fs_game"));
    assertFalse(services.writable("version"));
    assertTrue(services.writable("brand_new"));

    var original = new CvarServices(VmModule.UI, cvars, CvarServices.WritePolicy.ORIGINAL_FORCED);
    text(NAME, "version");
    original.set(vm, args(NAME, VALUE));
    assertEquals("4", cvars.string("version"));
  }

  @Test
  void uiValueResetCreateAndInfoServices() {
    text(NAME, "ui_volume");
    services.setValue(vm, args(NAME, Float.floatToRawIntBits(0.5f)));
    assertEquals("0.500000", cvars.string("ui_volume"));
    assertEquals(0.5f, Float.intBitsToFloat(services.variableValue(vm, args(NAME))));
    services.setValue(vm, args(NAME, Float.floatToRawIntBits(3f)));
    assertEquals("3", cvars.string("ui_volume"));

    text(NAME, "cg_fov");
    text(VALUE, "90");
    services.create(vm, args(NAME, VALUE, CvarFlags.ARCHIVE | CvarFlags.USERINFO));
    cvars.set("cg_fov", "110");
    services.reset(vm, args(NAME));
    assertEquals("90", cvars.string("cg_fov"));
    assertEquals(90, services.variableIntegerValue(vm, args(NAME)));

    services.variableStringBuffer(vm, args(NAME, BUFFER, 2));
    assertEquals("9", memory.readString(BUFFER, 16));
    text(NAME, "missing");
    services.variableStringBuffer(vm, args(NAME, BUFFER, 16));
    assertEquals("", memory.readString(BUFFER, 16));

    services.infoStringBuffer(vm, args(CvarFlags.USERINFO, BUFFER, 256));
    assertEquals("\\cg_fov\\90", memory.readString(BUFFER, 256));
    cvars.register("sv_hostname", "arena", CvarFlags.SERVERINFO);
    services.serverInfo(vm, args(BUFFER, 256));
    assertEquals("\\sv_hostname\\arena", memory.readString(BUFFER, 256));
  }
}

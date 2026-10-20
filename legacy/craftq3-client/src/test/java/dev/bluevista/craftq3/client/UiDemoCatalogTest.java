package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UiDemoCatalogTest {
  @Test
  void bothGuestProfilesMergeHostDemosAndWriteOnlyCompleteBoundedNames() throws Exception {
    for (var profile : UiAbi.values()) {
      boolean retail = profile == UiAbi.RETAIL_1999;
      String suffix = retail ? ".dm3" : ".dm_68";
      for (int capacity : new int[] {0, 1, 2 + suffix.length(), 64}) {
        var p = program(profile, "demos", retail ? "dm3" : ".dm_68", capacity);
        var fs =
            new ClientTestData.Files(
                Map.of(
                    "vm/ui.qvm",
                    p.bytes(),
                    "demos/a.dm_68",
                    new byte[0],
                    "demos/z.dm_68",
                    new byte[0],
                    "demos/unsupported.dm3",
                    new byte[0],
                    "demos/nested/hidden.dm_68",
                    new byte[0]));
        var cvars = new CvarSystem();
        try (var ui =
            new Q3Ui(
                fs,
                cvars,
                new CommandSystem(cvars, fs, ignored -> {}),
                new KeyBindings(),
                new ClientTestData.Audio(),
                ignored -> {},
                ignored -> {},
                host(),
                profile)) {
          var field = Q3Ui.class.getDeclaredField("vm");
          field.setAccessible(true);
          var memory = ((QvmInterpreter) field.get(ui)).memory();
          memory.fill(1000, 100, 0x55);
          ui.initialize(640, 480);
          int count = capacity == 64 ? 3 : capacity >= 2 + suffix.length() ? 1 : 0;
          assertEquals(count, memory.readInt(400));
          int cursor = 1000;
          for (String name : List.of("a", "b", "z").subList(0, count)) {
            String expected = name + suffix;
            assertEquals(expected, memory.readCString(cursor, 100));
            cursor += expected.length() + 1;
          }
          if (count == 0 && capacity > 0) {
            assertEquals(0, memory.readUnsignedByte(1000));
            cursor++;
          }
          assertEquals(0x55, memory.readUnsignedByte(cursor));
        }
      }
    }
  }

  @Test
  void unrelatedListingsAndDisconnectedDefaultStayUnchanged() throws Exception {
    assertEquals(List.of(), UiHost.disconnected().demoFiles());
    var profile = UiAbi.Q3_132;
    var p = program(profile, "models", ".md3", 64);
    var fs =
        new ClientTestData.Files(Map.of("vm/ui.qvm", p.bytes(), "models/one.md3", new byte[0]));
    var cvars = new CvarSystem();
    try (var ui =
        new Q3Ui(
            fs,
            cvars,
            new CommandSystem(cvars, fs, ignored -> {}),
            new KeyBindings(),
            new ClientTestData.Audio(),
            ignored -> {},
            ignored -> {},
            host(),
            profile)) {
      ui.initialize(640, 480);
      var field = Q3Ui.class.getDeclaredField("vm");
      field.setAccessible(true);
      var memory = ((QvmInterpreter) field.get(ui)).memory();
      assertEquals(1, memory.readInt(400));
      assertEquals("one.md3", memory.readCString(1000, 64));
    }
  }

  private static UiHost host() {
    return new UiHost() {
      public ClientState clientState() {
        return new ClientState(1, 0, -1, "", "", "");
      }

      public String configString(int index) {
        return "";
      }

      public int keyCatcher() {
        return 0;
      }

      public void keyCatcher(int value) {}

      public void clearKeys() {}

      public List<String> demoFiles() {
        return List.of("a.dm_68", "b.dm_68");
      }
    };
  }

  private static ClientTestData.Program program(
      UiAbi profile, String directory, String extension, int capacity) {
    var p =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .op(Opcode.LOCAL, 72)
            .op(Opcode.LOAD4)
            .op(Opcode.CONST, 0);
    int api = p.size();
    p.op(Opcode.EQ, 0).op(Opcode.CONST, 400);
    int[] args = {200, 224, 1000, capacity};
    for (int i = 0; i < args.length; i++) p.op(Opcode.CONST, args[i]).op(Opcode.ARG, 8 + i * 4);
    p.op(Opcode.CONST, -18)
        .op(Opcode.CALL)
        .op(Opcode.STORE4)
        .op(Opcode.CONST, 0)
        .op(Opcode.LEAVE, 64);
    p.patch(api, p.size());
    p.op(Opcode.CONST, profile == UiAbi.RETAIL_1999 ? 3 : 4).op(Opcode.LEAVE, 64);
    for (var entry : Map.of(200, directory, 224, extension).entrySet()) {
      byte[] bytes = entry.getValue().getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(bytes, 0, p.data, entry.getKey(), bytes.length);
    }
    return p;
  }
}

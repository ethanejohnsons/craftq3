package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.vm.Opcode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class UiBrowserTest {
  @Test
  void bothGuestProfilesUseNativeListAndPingLayoutsWithCopiedOutput() throws Exception {
    for (var profile : UiAbi.values()) {
      boolean retail = profile == UiAbi.RETAIL_1999;
      var observed = new ArrayList<String>();
      var browser =
          new UiBrowser() {
            public int capacity(int source) {
              return source == 0 || source == 2 ? 128 : 0;
            }

            public boolean pingOccupied(int index) {
              return index == 2;
            }

            public int count(int source) {
              return source == 0 ? 2 : 3;
            }

            public String address(int source, int index) {
              return "server-" + source + "-" + index;
            }

            public int pingCount() {
              return 4;
            }

            public void clearPing(int index) {
              observed.add("clear " + index);
            }

            public Ping ping(int index) {
              observed.add("ping " + index);
              return new Ping("127.0.0.1:27960", 17);
            }

            public String pingInfo(int index) {
              return "ping-info-" + index;
            }
          };
      var p =
          program(
              profile,
              code -> {
                store(code, 400, retail ? 46 : 65, 0);
                store(code, 404, retail ? 48 : 65, 2);
                if (retail) code.call(47, 1, 900, 64).call(49, 2, 1000, 64);
                else code.call(66, 0, 1, 900, 64).call(66, 2, 2, 1000, 64);
                code.call(1, 900).call(1, 1000);
                store(code, 408, retail ? 50 : 46);
                code.call(retail ? 51 : 47, 3)
                    .call(retail ? 52 : 48, 2, 1100, 64, 412)
                    .call(retail ? 53 : 49, 2, 1200, 64)
                    .call(1, 1100)
                    .call(1, 1200);
                report(code, 400, 200);
                report(code, 404, 224);
                report(code, 408, 248);
                report(code, 412, 272);
              });
      put(p, 200, "local_count");
      put(p, 224, "global_count");
      put(p, 248, "ping_count");
      put(p, 272, "ping_time");
      var output = new ArrayList<String>();
      var cvars = new CvarSystem();
      try (var ui = ui(profile, p, browser, cvars, output)) {
        ui.initialize(640, 480);
        assertEquals(2, cvars.integer("local_count"));
        assertEquals(3, cvars.integer("global_count"));
        assertEquals(4, cvars.integer("ping_count"));
        assertEquals(17, cvars.integer("ping_time"));
        assertEquals(List.of("clear 3", "ping 2"), observed);
        assertTrue(
            output.containsAll(
                List.of("server-0-1", "server-2-2", "127.0.0.1:27960", "ping-info-2")));
      }
    }
  }

  @Test
  void modernGuestRoutesBrowserMutationsAndStatusPendingPreservesItsBuffer() throws Exception {
    var calls = new ArrayList<String>();
    var browser =
        new UiBrowser() {
          int requests;

          public void markVisible(int source, int index, int value) {
            calls.add("visible " + source + " " + index + " " + value);
          }

          public void resetPings(int source) {
            calls.add("reset " + source);
          }

          public void loadCache() {
            calls.add("load");
          }

          public void saveCache() {
            calls.add("save");
          }

          public int add(int source, String name, String address) {
            calls.add("add " + source + " " + name + " " + address);
            return 1;
          }

          public void remove(int source, String address) {
            calls.add("remove " + source + " " + address);
          }

          public boolean updatePings(int source) {
            calls.add("update " + source);
            return true;
          }

          public int visible(int source, int index) {
            return 2;
          }

          public int serverPing(int source, int index) {
            return 23;
          }

          public int compare(int source, int key, int direction, int first, int second) {
            calls.add(
                "compare " + source + " " + key + " " + direction + " " + first + " " + second);
            return -1;
          }

          public Optional<String> status(String address, int capacity) {
            calls.add("status " + address + " " + capacity);
            return requests++ == 0 ? Optional.empty() : Optional.of("ready");
          }

          public void resetStatus(String address) {
            calls.add("status-reset " + address);
          }
        };
    var p =
        program(
            UiAbi.Q3_132,
            code -> {
              code.call(101, 900, 450, 10)
                  .call(68, 3, -1, 2)
                  .call(70, 3)
                  .call(71)
                  .call(72)
                  .call(73, 3, 300, 340)
                  .call(74, 3, 340)
                  .call(69, 3)
                  .call(85, 3, 4, 1, 2, 0);
              store(code, 400, 84, 3, 0);
              store(code, 404, 83, 3, 0);
              code.call(82, 340, 900, 64)
                  .call(1, 900)
                  .call(82, 340, 900, 64)
                  .call(1, 900)
                  .call(82, 340, 0, 0)
                  .call(82, 0, 0, 0);
              report(code, 400, 200);
              report(code, 404, 224);
            });
    put(p, 200, "visible_value");
    put(p, 224, "server_ping");
    put(p, 300, "Favorite");
    put(p, 340, "127.0.0.1:27960");
    put(p, 450, "untouched");
    var output = new ArrayList<String>();
    var cvars = new CvarSystem();
    try (var ui = ui(UiAbi.Q3_132, p, browser, cvars, output)) {
      ui.initialize(640, 480);
      assertEquals(2, cvars.integer("visible_value"));
      assertEquals(23, cvars.integer("server_ping"));
      assertTrue(output.containsAll(List.of("untouched", "ready")));
      assertEquals(
          List.of(
              "visible 3 -1 2",
              "reset 3",
              "load",
              "save",
              "add 3 Favorite 127.0.0.1:27960",
              "remove 3 127.0.0.1:27960",
              "update 3",
              "compare 3 4 1 2 0",
              "status 127.0.0.1:27960 64",
              "status 127.0.0.1:27960 64",
              "status-reset 127.0.0.1:27960",
              "status-reset null"),
          calls);
    }
  }

  @Test
  void malformedOutputPointersCannotConsumePingOrStartStatusRequests() throws Exception {
    for (int call : new int[] {48, 82}) {
      int[] calls = {0};
      var browser =
          new UiBrowser() {
            public Ping ping(int index) {
              calls[0]++;
              return new Ping("", 0);
            }

            public Optional<String> status(String address, int capacity) {
              calls[0]++;
              return Optional.empty();
            }
          };
      var p =
          program(
              UiAbi.Q3_132,
              code -> {
                if (call == 48) code.call(48, 0, 900, 64, 262143);
                else code.call(82, 340, 262143, 64);
              });
      put(p, 340, "127.0.0.1:27960");
      try (var ui = ui(UiAbi.Q3_132, p, browser, new CvarSystem(), new ArrayList<>())) {
        assertThrows(dev.bluevista.craftq3.vm.QvmException.class, () -> ui.initialize(640, 480));
        assertEquals(0, calls[0]);
      }
    }
  }

  @Test
  void guestCopiesMatchNativePaddingForValidEmptyInvalidAndTruncatedSlots() throws Exception {
    for (var profile : UiAbi.values()) {
      boolean retail = profile == UiAbi.RETAIL_1999;
      for (int operation : new int[] {0, 1, 2, 3}) {
        if (retail && operation == 3) continue;
        for (boolean present : new boolean[] {false, true}) {
          for (int capacity : new int[] {1, 4, 16}) {
            var browser =
                new UiBrowser() {
                  public int capacity(int source) {
                    return present ? 128 : 0;
                  }

                  public String address(int source, int index) {
                    return "abcd";
                  }

                  public String info(int source, int index) {
                    return "abcd";
                  }

                  public boolean pingOccupied(int index) {
                    return present;
                  }

                  public Ping ping(int index) {
                    return new Ping("abcd", present ? 17 : 0);
                  }

                  public String pingInfo(int index) {
                    return "";
                  }
                };
            var p =
                program(
                    profile,
                    code -> {
                      code.call(100, 900, 0x5a, 32);
                      switch (operation) {
                        case 0 -> {
                          if (retail) code.call(47, 0, 900, capacity);
                          else code.call(66, 0, 0, 900, capacity);
                        }
                        case 1 -> code.call(retail ? 52 : 48, 0, 900, capacity, 800);
                        case 2 -> code.call(retail ? 53 : 49, 0, 900, capacity);
                        case 3 -> code.call(67, 0, 0, 900, capacity);
                        default -> throw new AssertionError();
                      }
                    });
            try (var ui = ui(profile, p, browser, new CvarSystem(), new ArrayList<>())) {
              ui.initialize(640, 480);
              var field = Q3Ui.class.getDeclaredField("vm");
              field.setAccessible(true);
              var memory = ((dev.bluevista.craftq3.vm.QvmInterpreter) field.get(ui)).memory();
              byte[] expected = new byte[32];
              java.util.Arrays.fill(expected, (byte) 0x5a);
              if (present) {
                java.util.Arrays.fill(expected, 0, capacity, (byte) 0);
                if (operation != 2)
                  System.arraycopy(
                      "abcd".getBytes(StandardCharsets.ISO_8859_1),
                      0,
                      expected,
                      0,
                      Math.min(4, capacity - 1));
              } else expected[0] = 0;
              assertArrayEquals(
                  expected,
                  memory.readBytes(900, 32),
                  profile + " operation=" + operation + " present=" + present + " cap=" + capacity);
              if (operation == 1) assertEquals(present ? 17 : 0, memory.readInt(800));
            }
          }
        }
      }
    }
  }

  private static ClientTestData.Program program(
      UiAbi profile, Consumer<ClientTestData.Program> body) {
    var p =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .op(Opcode.LOCAL, 72)
            .op(Opcode.LOAD4)
            .op(Opcode.CONST, 0);
    int api = p.size();
    p.op(Opcode.EQ, 0);
    body.accept(p);
    p.op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
    p.patch(api, p.size());
    p.op(Opcode.CONST, profile == UiAbi.RETAIL_1999 ? 3 : 4).op(Opcode.LEAVE, 64);
    return p;
  }

  private static void store(ClientTestData.Program p, int address, int call, int... args) {
    p.op(Opcode.CONST, address);
    for (int i = 0; i < args.length; i++) p.op(Opcode.CONST, args[i]).op(Opcode.ARG, 8 + i * 4);
    p.op(Opcode.CONST, -1 - call).op(Opcode.CALL).op(Opcode.STORE4);
  }

  private static void report(ClientTestData.Program p, int address, int name) {
    p.op(Opcode.CONST, name)
        .op(Opcode.ARG, 8)
        .op(Opcode.CONST, address)
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 12)
        .op(Opcode.CONST, -7)
        .op(Opcode.CALL)
        .op(Opcode.POP);
  }

  private static void put(ClientTestData.Program p, int address, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    System.arraycopy(bytes, 0, p.data, address, bytes.length);
  }

  private static Q3Ui ui(
      UiAbi profile,
      ClientTestData.Program program,
      UiBrowser browser,
      CvarSystem cvars,
      List<String> output)
      throws Exception {
    var fs = new ClientTestData.Files(Map.of("vm/ui.qvm", program.bytes()));
    UiHost host =
        new UiHost() {
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

          public UiBrowser browser() {
            return browser;
          }
        };
    return new Q3Ui(
        fs,
        cvars,
        new CommandSystem(cvars, fs, output::add),
        new KeyBindings(),
        new ClientTestData.Audio(),
        ignored -> {},
        output::add,
        host,
        profile);
  }
}

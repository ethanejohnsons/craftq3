package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.vm.Opcode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class UiHostTest {
  @Test
  void consoleCommandsCarryCurrentEngineTimeAndTheirOwnArgumentsWhileUiIsHidden() throws Exception {
    for (var profile : UiAbi.values()) {
      var program =
          new ClientTestData.Program()
              .op(Opcode.ENTER, 64)
              .op(Opcode.LOCAL, 72)
              .op(Opcode.LOAD4)
              .op(Opcode.CONST, 0);
      int apiBranch = program.size();
      program.op(Opcode.EQ, 0);
      program
          .op(Opcode.CONST, 200)
          .op(Opcode.ARG, 8)
          .op(Opcode.LOCAL, 76)
          .op(Opcode.LOAD4)
          .op(Opcode.CVIF)
          .op(Opcode.ARG, 12)
          .op(Opcode.CONST, -7)
          .op(Opcode.CALL)
          .op(Opcode.POP)
          .call(11, 1, 300, 128)
          .call(3, 224, 300)
          .op(Opcode.CONST, 1)
          .op(Opcode.LEAVE, 64);
      program.patch(apiBranch, program.size());
      program.op(Opcode.CONST, profile == UiAbi.RETAIL_1999 ? 3 : 4).op(Opcode.LEAVE, 64);
      for (var entry : Map.of(200, "observed_time", 224, "observed_argument").entrySet()) {
        byte[] bytes = entry.getValue().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, program.data, entry.getKey(), bytes.length);
      }
      var fs = new ClientTestData.Files(Map.of("vm/ui.qvm", program.bytes()));
      var cvars = new CvarSystem();
      var commands = new CommandSystem(cvars, fs, ignored -> {});
      try (var ui =
          new Q3Ui(
              fs,
              cvars,
              commands,
              new KeyBindings(),
              new ClientTestData.Audio(),
              ignored -> {},
              ignored -> {},
              UiHost.disconnected(),
              profile)) {
        ui.initialize(640, 480);
        ui.frame(1000, 640, 480);
        assertTrue(ui.consoleCommand(CommandParser.tokenize("postgame \"quoted value\""), 125000));
        assertEquals(125000, cvars.integer("observed_time"));
        assertEquals("quoted value", cvars.string("observed_argument"));
        assertTrue(ui.consoleCommand(CommandParser.tokenize("another retained")));
        assertEquals(125000, cvars.integer("observed_time"));
        ui.frame(125050, 640, 480);
        assertEquals("", cvars.string("observed_argument"));
      }
    }
  }

  @Test
  void bothGuestsShareCvarsBindingsAndHostStateWithBoundedDirectoryListings() throws Exception {
    for (var profile : UiAbi.values()) {
      var fs =
          new ClientTestData.Files(
              Map.of(
                  "vm/ui.qvm",
                  program(profile),
                  "models/players/sarge/default.skin",
                  new byte[0],
                  "models/players/sarge/lower.md3",
                  new byte[0],
                  "models/players/visor/default.skin",
                  new byte[0],
                  "models/players/readme.txt",
                  new byte[0]));
      var cvars = new CvarSystem();
      cvars.register("sv_cheats", "0", CvarSystem.ROM);
      var output = new ArrayList<String>();
      var commands = new CommandSystem(cvars, fs, output::add);
      var bindings = new KeyBindings();
      var audio = new ClientTestData.Audio();
      var host =
          new UiHost() {
            private int catcher;

            public ClientState clientState() {
              return new ClientState(8, 0, 3, "local", "", "ready");
            }

            public String configString(int index) {
              return index == 4 ? "fixture configuration" : "";
            }

            public int keyCatcher() {
              return catcher;
            }

            public void keyCatcher(int value) {
              catcher = value;
            }

            public void clearKeys() {}
          };
      try (var ui =
          new Q3Ui(fs, cvars, commands, bindings, audio, frame -> {}, output::add, host, profile)) {
        ui.initialize(640, 480);
        ui.setMenu(Q3Ui.Menu.INGAME);
        assertEquals("+attack", bindings.binding('w'));
        assertEquals("seen", cvars.string("fixture_shared"));
        assertEquals("17", cvars.string("fixture_registered"));
        assertTrue(output.contains("UPARROW"));
        assertTrue(output.contains("fixture configuration"));
        var first = (CgameFrame.Quad) ui.frame(1000, 640, 480).commands().getFirst();
        assertEquals(2, first.x(), "Directory lists deduplicate immediate child directories");
        assertEquals(3, first.y(), "Client state carries host client number");
        assertEquals(640, first.width());
        long before = ui.syscallCounts().get(43);
        ui.key(13, false, 1010);
        assertEquals(before + (profile == UiAbi.RETAIL_1999 ? 0 : 1), ui.syscallCounts().get(43));
        var resized = (CgameFrame.Quad) ui.frame(1020, 1024, 768).commands().getFirst();
        assertEquals(1024, resized.width());
        assertEquals(640, first.width());
        assertEquals(0, audio.clears, "UI overlays never reset game-owned audio loops");
      }
      assertFalse(fs.closed);
      assertFalse(audio.closed);
    }
  }

  @Test
  void malformedGuestPointerFailsWithServiceContextAndRequiresNewSession() throws Exception {
    var program =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .op(Opcode.LOCAL, 72)
            .op(Opcode.LOAD4)
            .op(Opcode.CONST, 0);
    int branch = program.size();
    program.op(Opcode.EQ, 0).call(43, 262140).op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
    program.patch(branch, program.size());
    program.op(Opcode.CONST, 4).op(Opcode.LEAVE, 64);
    var fs = new ClientTestData.Files(Map.of("vm/ui.qvm", program.bytes()));
    var cvars = new CvarSystem();
    var commands = new CommandSystem(cvars, fs, s -> {});
    try (var ui =
        new Q3Ui(
            fs,
            cvars,
            commands,
            new KeyBindings(),
            new ClientTestData.Audio(),
            frame -> {},
            s -> {},
            UiHost.disconnected())) {
      var failure =
          assertThrows(dev.bluevista.craftq3.vm.QvmException.class, () -> ui.initialize(640, 480));
      assertEquals(dev.bluevista.craftq3.vm.QvmException.Reason.MEMORY_BOUNDS, failure.reason());
      assertEquals(Q3Ui.State.FAILED, ui.state());
      assertThrows(IllegalStateException.class, () -> ui.frame(1000, 640, 480));
    }
  }

  @Test
  void unknownLegacyExtensionsAndUnconfiguredCdVerificationNeverPretendToSucceed()
      throws Exception {
    assertFalse(UiHost.disconnected().verifyCdKey("", ""));
    assertThrows(
        UnsupportedOperationException.class,
        () -> UiHost.disconnected().verifyCdKey("provided-key", ""));
    assertEquals(UiAbi.Q3_132, UiAbi.detect("retail API3".getBytes(StandardCharsets.US_ASCII)));
    var cvars = new CvarSystem();
    cvars.register("other", "0", 0);
    var fs = new ClientTestData.Files(Map.of("vm/ui.qvm", program(UiAbi.RETAIL_1999)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Q3Ui(
                fs,
                cvars,
                new CommandSystem(cvars, fs, s -> {}),
                new KeyBindings(),
                new ClientTestData.Audio(),
                frame -> {},
                s -> {},
                UiHost.disconnected()));
  }

  private static byte[] program(UiAbi profile) {
    var program =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .op(Opcode.LOCAL, 72)
            .op(Opcode.LOAD4)
            .op(Opcode.CONST, 0);
    int branch = program.size();
    program.op(Opcode.EQ, 0);
    program
        .call(profile == UiAbi.RETAIL_1999 ? 54 : 50, 500, 300, 340, 1)
        .call(43, 1000)
        .call(44, 34000)
        .call(45, 4, 35000, 1024)
        .call(1, 35000)
        .call(35, 'w', 240)
        .call(33, 132, 32000, 32)
        .call(1, 32000)
        .call(3, 260, 280);
    program.op(Opcode.CONST, 800);
    int[] list = {200, 224, 30000, 1024};
    for (int i = 0; i < list.length; i++)
      program.op(Opcode.CONST, list[i]).op(Opcode.ARG, 8 + i * 4);
    program.op(Opcode.CONST, -18).op(Opcode.CALL).op(Opcode.STORE4);
    program
        .op(Opcode.CONST, 800)
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 8)
        .op(Opcode.CONST, 34008)
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 12)
        .op(Opcode.CONST, 1000 + profile.renderer().glconfigWidth())
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 16);
    int[] remaining = {
      Float.floatToRawIntBits(20), 0, 0, Float.floatToRawIntBits(1), Float.floatToRawIntBits(1), 0
    };
    for (int i = 0; i < remaining.length; i++)
      program.op(Opcode.CONST, remaining[i]).op(Opcode.ARG, 20 + i * 4);
    program
        .op(Opcode.CONST, -28)
        .op(Opcode.CALL)
        .op(Opcode.POP)
        .op(Opcode.CONST, 0)
        .op(Opcode.LEAVE, 64);
    program.patch(branch, program.size());
    program.op(Opcode.CONST, profile == UiAbi.RETAIL_1999 ? 3 : 4).op(Opcode.LEAVE, 64);
    for (var entry :
        Map.of(
                200,
                "models/players",
                224,
                "/",
                240,
                "+attack",
                260,
                "fixture_shared",
                280,
                "seen",
                300,
                "fixture_registered",
                340,
                "17")
            .entrySet()) {
      byte[] text = entry.getValue().getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(text, 0, program.data, entry.getKey(), text.length);
    }
    return program.bytes();
  }
}

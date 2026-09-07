package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.command.KeyBindings;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.vm.Opcode;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class UiEmptyShaderTest {
  @Test
  void bothGuestProfilesCanRegisterEmptyBitmapNamesAndDrawWithHandleZero() throws Exception {
    for (var profile : UiAbi.values()) {
      var p =
          new ClientTestData.Program()
              .op(Opcode.ENTER, 64)
              .op(Opcode.LOCAL, 72)
              .op(Opcode.LOAD4)
              .op(Opcode.CONST, 0);
      int api = p.size();
      p.op(Opcode.EQ, 0);
      p.op(Opcode.CONST, 300)
          .op(Opcode.CONST, 200)
          .op(Opcode.ARG, 8)
          .op(Opcode.CONST, -21)
          .op(Opcode.CALL)
          .op(Opcode.STORE4);
      p.call(20, 0);
      p.op(Opcode.CONST, 300).op(Opcode.LOAD4).op(Opcode.CVIF).op(Opcode.ARG, 8);
      int[] rest = {
        0,
        Float.floatToRawIntBits(16),
        Float.floatToRawIntBits(16),
        0,
        0,
        Float.floatToRawIntBits(1),
        Float.floatToRawIntBits(1),
        0
      };
      for (int i = 0; i < rest.length; i++) p.op(Opcode.CONST, rest[i]).op(Opcode.ARG, 12 + i * 4);
      p.op(Opcode.CONST, -28)
          .op(Opcode.CALL)
          .op(Opcode.POP)
          .op(Opcode.CONST, 0)
          .op(Opcode.LEAVE, 64);
      p.patch(api, p.size());
      p.op(Opcode.CONST, profile == UiAbi.RETAIL_1999 ? 3 : 4).op(Opcode.LEAVE, 64);
      var fs = new ClientTestData.Files(Map.of("vm/ui.qvm", p.bytes()));
      var cvars = new CvarSystem();
      cvars.register("sv_cheats", "0", CvarSystem.ROM);
      try (var ui =
          new Q3Ui(
              fs,
              cvars,
              new CommandSystem(cvars, fs, ignored -> {}),
              new KeyBindings(),
              new ClientTestData.Audio(),
              frame -> {},
              ignored -> {},
              UiHost.disconnected(),
              profile)) {
        ui.initialize(640, 480);
        var quad = (CgameFrame.Quad) ui.frame(1000, 640, 480).commands().getFirst();
        assertEquals(0, quad.x());
        assertEquals("white", quad.shader());
        assertEquals(1, ui.registeredShaders());
      }
    }
  }
}

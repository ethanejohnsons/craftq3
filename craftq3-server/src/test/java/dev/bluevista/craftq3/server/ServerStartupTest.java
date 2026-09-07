package dev.bluevista.craftq3.server;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ServerStartupTest {
  @Test
  void freshMapRunsTheObservedSettlingExportsBeforeBecomingAvailable() throws Exception {
    for (boolean bots : new boolean[] {false, true}) {
      var output = new ArrayList<String>();
      try (var server = server(output)) {
        assertEquals(1, server.cvars().integer("bot_enable"));
        server.cvars().set("bot_enable", bots ? "1" : "0", CvarSystem.Source.ENGINE);
        server.commands().submit("set startup_pending consumed", CommandSystem.Execution.APPEND);
        server.initialize(0, 42);
        assertEquals(Q3Server.State.RUNNING, server.state());
        assertEquals(400, server.time());
        assertEquals(4, server.frameNumber());
        assertTrue(server.cvars().find("startup_pending").isEmpty());
        var expected = new ArrayList<String>();
        for (int time : new int[] {0, 100, 200, 300}) {
          assertEquals("frame", server.configstrings().get(32 + time));
          assertEquals(bots ? "bot" : "", server.configstrings().get(33 + time));
          expected.add("frame");
          if (bots) expected.add("bot");
        }
        assertEquals(
            expected, output.stream().filter(s -> s.equals("frame") || s.equals("bot")).toList());
      }
    }
  }

  @Test
  void fastRestartSettlesAtTheOldClockBeforeAdvancingWithoutBotFrames() throws Exception {
    for (boolean bots : new boolean[] {false, true}) {
      var output = new ArrayList<String>();
      try (var server = server(output)) {
        server.cvars().set("bot_enable", bots ? "1" : "0", CvarSystem.Source.ENGINE);
        server.initialize(0, 42);
        output.clear();
        server.restart(42);
        assertEquals(Q3Server.State.RUNNING, server.state());
        assertEquals(800, server.time());
        assertEquals(8, server.frameNumber());
        for (int time : new int[] {400, 500, 600, 700}) {
          assertEquals("frame", server.configstrings().get(32 + time));
          assertEquals("", server.configstrings().get(33 + time));
        }
        assertEquals(
            List.of("frame", "frame", "frame", "frame"),
            output.stream().filter(s -> s.equals("frame") || s.equals("bot")).toList());
      }
    }
  }

  @Test
  void overflowingStartupTimeIsRejectedBeforeGuestInitialization() throws Exception {
    try (var server = server(new ArrayList<>())) {
      assertThrows(ArithmeticException.class, () -> server.initialize(Integer.MAX_VALUE - 399, 42));
      assertEquals(Q3Server.State.LOADED, server.state());
      assertEquals(0, server.frameNumber());
      assertTrue(server.syscallCounts().isEmpty());
    }
  }

  private static Q3Server server(List<String> output) throws Exception {
    byte[] module = observerModule();
    var fs =
        new VirtualFileSystem() {
          public byte[] read(VirtualPath path) {
            if (!path.value().equals("vm/qagame.qvm"))
              throw new IllegalArgumentException(path.value());
            return module.clone();
          }

          public Optional<Origin> which(VirtualPath path) {
            return Optional.of(new Origin("fixture", "memory", false));
          }

          public List<VirtualPath> list(String directory) {
            return List.of();
          }

          public List<Origin> searchOrder() {
            return List.of();
          }

          public void close() {}
        };
    return new Q3Server(
        fs, "fixture", BspReader.read(BspFixture.map(false)), null, output::add, Clock.systemUTC());
  }

  /**
   * Authored guest records the public GAME_RUN_FRAME / BOTAI_START_FRAME imports and timestamps.
   */
  private static byte[] observerModule() {
    var instructions = new ArrayList<int[]>();
    emit(instructions, Opcode.ENTER, 16);
    emit(instructions, Opcode.LOCAL, 24);
    emit(instructions, Opcode.LOAD4);
    emit(instructions, Opcode.CONST, 8);
    int gameBranch = instructions.size();
    emit(instructions, Opcode.EQ, 0);
    emit(instructions, Opcode.LOCAL, 24);
    emit(instructions, Opcode.LOAD4);
    emit(instructions, Opcode.CONST, 10);
    int botBranch = instructions.size();
    emit(instructions, Opcode.EQ, 0);
    emit(instructions, Opcode.CONST, 0);
    emit(instructions, Opcode.LEAVE, 16);
    instructions.get(gameBranch)[1] = instructions.size();
    record(instructions, 32, 16);
    instructions.get(botBranch)[1] = instructions.size();
    record(instructions, 33, 32);
    var code = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
    for (int[] instruction : instructions) {
      var op = Opcode.values()[instruction[0]];
      code.put((byte) op.ordinal());
      if (instruction.length == 2) {
        if (op == Opcode.ARG) code.put((byte) instruction[1]);
        else code.putInt(instruction[1]);
      }
    }
    int codeBytes = code.position(), dataOffset = (32 + codeBytes + 3) & ~3;
    var file = ByteBuffer.allocate(dataOffset + 64).order(ByteOrder.LITTLE_ENDIAN);
    file.putInt(QvmReader.MAGIC)
        .putInt(instructions.size())
        .putInt(32)
        .putInt(codeBytes)
        .putInt(dataOffset)
        .putInt(64)
        .putInt(0)
        .putInt(65536);
    file.put(code.array(), 0, codeBytes);
    file.position(dataOffset + 16);
    file.put("frame\0".getBytes(StandardCharsets.US_ASCII));
    file.position(dataOffset + 32);
    file.put("bot\0".getBytes(StandardCharsets.US_ASCII));
    return file.array();
  }

  private static void record(List<int[]> code, int keyOffset, int text) {
    emit(code, Opcode.LOCAL, 28);
    emit(code, Opcode.LOAD4);
    emit(code, Opcode.CONST, keyOffset);
    emit(code, Opcode.ADD);
    emit(code, Opcode.ARG, 8);
    emit(code, Opcode.CONST, text);
    emit(code, Opcode.ARG, 12);
    emit(code, Opcode.CONST, -19); // G_SET_CONFIGSTRING (18).
    emit(code, Opcode.CALL);
    emit(code, Opcode.POP);
    emit(code, Opcode.CONST, text);
    emit(code, Opcode.ARG, 8);
    emit(code, Opcode.CONST, -1);
    emit(code, Opcode.CALL);
    emit(code, Opcode.POP);
    emit(code, Opcode.CONST, 0);
    emit(code, Opcode.LEAVE, 16);
  }

  private static void emit(List<int[]> code, Opcode op, int... operand) {
    code.add(operand.length == 0 ? new int[] {op.ordinal()} : new int[] {op.ordinal(), operand[0]});
  }
}

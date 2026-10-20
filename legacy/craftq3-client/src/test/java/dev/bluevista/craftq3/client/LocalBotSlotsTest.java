package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.vm.Opcode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LocalBotSlotsTest {
  @Test
  void botSlotsRespectCapacityHumanOwnershipReuseAndFrameScheduling() throws Exception {
    var files = new ClientTestData.Files(Map.of("vm/qagame.qvm", program()));
    try (var server =
        new Q3Server(
            files,
            "fixture",
            BspReader.read(BspFixture.map(false)),
            null,
            text -> {},
            Clock.systemUTC())) {
      server.cvars().set("sv_maxclients", "2", CvarSystem.Source.ENGINE);
      server.cvars().set("bot_enable", "0", CvarSystem.Source.ENGINE);
      server.initialize(1000, 42);
      server.connect(0, Map.of("name", "Human"));
      assertFalse(server.isBot(0));
      server.consoleCommand(CommandParser.tokenize("allocate"));
      assertEquals(1, result(server));
      assertTrue(server.isBot(1));
      server.consoleCommand(CommandParser.tokenize("allocate"));
      assertEquals(-1, result(server));
      server.updateUserInfo(1, Map.of("name", "SyntheticBot"));
      server.consoleCommand(CommandParser.tokenize("free"));
      assertFalse(server.isBot(1));
      assertThrows(IllegalStateException.class, () -> server.playerState(1));
      server.consoleCommand(CommandParser.tokenize("free"));
      server.consoleCommand(CommandParser.tokenize("allocate"));
      assertEquals(1, result(server));
      assertTrue(server.isBot(1));
      server.runFrame(server.time() + 50);
      assertEquals("", server.configstrings().get(101));
      server.cvars().set("bot_enable", "1", CvarSystem.Source.ENGINE);
      server.runFrame(server.time() + 50);
      assertEquals("bot frame", server.configstrings().get(101));
      // The host must reject a bot-free syscall aimed at the connected human slot.
      assertThrows(
          RuntimeException.class, () -> server.consoleCommand(CommandParser.tokenize("human")));
      assertFalse(server.isBot(0));
    }
  }

  private static int result(Q3Server server) {
    return ByteBuffer.wrap(server.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(184);
  }

  private static byte[] program() {
    var p =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .call(15, 4096, 2, 516, 16384, 468)
            .op(Opcode.LOCAL, 72)
            .op(Opcode.LOAD4)
            .op(Opcode.CONST, 10);
    int botFrame = p.size();
    p.op(Opcode.EQ, 0);
    p.op(Opcode.LOCAL, 72).op(Opcode.LOAD4).op(Opcode.CONST, 9);
    int other = p.size();
    p.op(Opcode.NE, 0);
    p.call(9, 0, 300, 16).op(Opcode.CONST, 300).op(Opcode.LOAD1).op(Opcode.CONST, 'a');
    int allocate = p.size();
    p.op(Opcode.EQ, 0);
    p.op(Opcode.CONST, 300).op(Opcode.LOAD1).op(Opcode.CONST, 'h');
    int human = p.size();
    p.op(Opcode.EQ, 0);
    p.call(35, 1).op(Opcode.CONST, 1).op(Opcode.LEAVE, 64);
    p.patch(human, p.size());
    p.call(35, 0).op(Opcode.CONST, 1).op(Opcode.LEAVE, 64);
    p.patch(allocate, p.size());
    p.op(Opcode.CONST, 16384 + 184)
        .op(Opcode.CONST, -35)
        .op(Opcode.CALL)
        .op(Opcode.STORE4)
        .op(Opcode.CONST, 1)
        .op(Opcode.LEAVE, 64);
    p.patch(botFrame, p.size());
    p.call(18, 101, 350);
    p.patch(other, p.size());
    p.op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
    System.arraycopy(
        "bot frame".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, p.data, 350, 9);
    return p.bytes();
  }
}

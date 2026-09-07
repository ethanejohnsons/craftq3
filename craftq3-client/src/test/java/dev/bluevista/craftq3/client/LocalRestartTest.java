package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.vm.Opcode;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LocalRestartTest {
  @Test
  void fastRestartPreservesConnectionsAndSignalsTheRetainedClient() throws Exception {
    // Record each callback's low argument bits in engine configstrings, which outlive VM reset.
    var guest =
        new ClientTestData.Program().op(Opcode.ENTER, 64).call(15, 4096, 1, 516, 16384, 468);
    for (int argument = 0; argument < 3; argument++) {
      guest
          .op(Opcode.CONST, 300)
          .op(Opcode.LOCAL, 76 + argument * 4)
          .op(Opcode.LOAD4)
          .op(Opcode.CONST, 1)
          .op(Opcode.BAND)
          .op(Opcode.CONST, '0')
          .op(Opcode.ADD)
          .op(Opcode.STORE1);
      guest
          .op(Opcode.LOCAL, 72)
          .op(Opcode.LOAD4)
          .op(Opcode.CONST, 3)
          .op(Opcode.MULI)
          .op(Opcode.CONST, 100 + argument)
          .op(Opcode.ADD)
          .op(Opcode.ARG, 8)
          .op(Opcode.CONST, 300)
          .op(Opcode.ARG, 12)
          .op(Opcode.CONST, -19)
          .op(Opcode.CALL)
          .op(Opcode.POP);
    }
    guest.op(Opcode.CONST, 0).op(Opcode.LEAVE, 64);
    var files =
        new ClientTestData.Files(
            Map.of(
                "vm/qagame.qvm",
                guest.bytes(),
                "vm/cgame.qvm",
                ClientTestData.client(ClientAbi.Q3_132)));
    try (var server =
        new Q3Server(
            files,
            "fixture",
            BspReader.read(BspFixture.map(false)),
            null,
            s -> {},
            Clock.systemUTC())) {
      server.initialize(1000, 42);
      server.connect(0, Map.of("name", "KeptPlayer"));
      assertEquals("0", server.configstrings().get(102), "First GAME_INIT is not a restart");
      assertEquals("1", server.configstrings().get(107), "First connection has firstTime=true");
      var snapshots = new LocalSnapshots(server, 0);
      snapshots.capture();
      var engine = new CommandSystem(server.cvars(), files, s -> {});
      engine.register("host_only", command -> {});
      try (var client =
          new Q3Client(
              files,
              server,
              frame -> {},
              new ClientTestData.Audio(),
              s -> {},
              ClientAbi.Q3_132,
              engine)) {
        client.initialize(0, 640, 480);
        server.commands().submit("set restart_remainder preserved", CommandSystem.Execution.APPEND);
        int beforeTime = server.time(), beforeFrame = server.frameNumber();
        server.restart(42);
        assertSame(engine, client.commands());
        assertEquals(Q3Client.State.RUNNING, client.state());
        assertEquals(beforeTime + 400, server.time());
        assertEquals(beforeFrame + 4, server.frameNumber());
        assertEquals(1, server.restartCount());
        assertEquals("1", server.configstrings().get(103), "GAME_SHUTDOWN receives restart=true");
        assertEquals("1", server.configstrings().get(102), "GAME_INIT receives restart=true");
        assertEquals("0", server.configstrings().get(107), "Reconnect has firstTime=false");
        assertEquals(1, server.commands().pending(), "Restart must not drain the engine buffer");
        snapshots.capture();
        assertEquals("map_restart\n", snapshots.command(snapshots.commandSequence()));
        for (var abi : ClientAbi.values()) {
          var memory = ClientTestData.memory();
          assertEquals(1, snapshots.read(memory, abi, snapshots.number(), 20000));
          assertEquals(4, memory.readInt(20000));
          assertEquals(beforeTime + 400, memory.readInt(20008));
        }
        assertFalse(client.frame(server.time(), 640, 480).commands().isEmpty());
        server.restart(42);
        assertEquals(0, server.snapshotFlags(), "Server-count bit toggles on each restart");
      }
      assertFalse(engine.complete("probe").contains("probe"), "Closed VM leaked command handlers");
      assertTrue(engine.complete("host_only").contains("host_only"));
      try (var replacement =
          new Q3Client(
              files,
              server,
              frame -> {},
              new ClientTestData.Audio(),
              s -> {},
              ClientAbi.Q3_132,
              engine)) {
        replacement.initialize(0, 640, 480);
        assertTrue(engine.complete("probe").contains("probe"));
      }
      server.cvars().set("g_gametype", "4", CvarSystem.Source.CONSOLE);
      assertTrue(server.requiresMapReload());
      assertThrows(IllegalStateException.class, () -> server.restart(42));
      assertEquals(
          Q3Server.State.RUNNING, server.state(), "Rejected fast restart preserves the game");
    }
  }
}

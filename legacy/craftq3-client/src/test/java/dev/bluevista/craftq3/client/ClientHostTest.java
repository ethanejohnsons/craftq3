package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import dev.bluevista.craftq3.vm.Opcode;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClientHostTest {
  @Test
  void originalSyntheticGuestsExerciseSnapshotsResizeAndBorrowedOwnership() throws Exception {
    for (var profile : ClientAbi.values()) {
      var files =
          new ClientTestData.Files(
              Map.of(
                  "vm/qagame.qvm",
                  ClientTestData.server(),
                  "vm/cgame.qvm",
                  ClientTestData.client(profile)));
      var audio = new ClientTestData.Audio();
      try (var server =
          new Q3Server(
              files,
              "fixture",
              BspReader.read(BspFixture.map(false)),
              null,
              s -> {},
              Clock.systemUTC())) {
        server.initialize(1000, 1);
        int startTime = server.time();
        server.connect(0, Map.of("name", "Synthetic"));
        try (var client = new Q3Client(files, server, frame -> {}, audio, s -> {}, profile)) {
          client.initialize(0, 640, 480);
          assertEquals(0, client.cvars().byHandle(0).handle());
          assertEquals("sv_cheats", client.cvars().byHandle(0).name());
          assertEquals(3, client.selectedWeapon());
          assertEquals(.5f, client.sensitivityScale());
          var first = (CgameFrame.Quad) client.frame(startTime, 640, 480).commands().getFirst();
          assertEquals(1, first.x());
          assertEquals(640, first.width());
          assertEquals(
              1,
              ((CgameFrame.Quad) client.frame(startTime + 10, 640, 480).commands().getFirst()).x());
          client.userCommand(UserCommand.idle(startTime + 50));
          server.runFrame(startTime + 50);
          assertEquals(
              2,
              ((CgameFrame.Quad) client.frame(startTime + 50, 640, 480).commands().getFirst()).x());
          assertEquals(
              0,
              audio.clears,
              "Retail trap30 has no kill-all argument; stale guest words must not kill voices");
          client.commands().register("host_only", command -> {});
          client.cvars().set("custom", "retained", CvarSystem.Source.CONSOLE);
          var resized =
              (CgameFrame.Quad) client.frame(startTime + 60, 1024, 768).commands().getFirst();
          assertEquals(1024, resized.width());
          assertEquals(2, resized.x());
          assertEquals("retained", client.cvars().string("custom"));
          assertTrue(client.commands().complete("host_only").contains("host_only"));
          assertTrue(client.commands().complete("probe").contains("probe"));
          assertEquals(1, audio.clears);
          assertEquals(640, first.width(), "Submitted frames retain their original owned values");
        }
        assertEquals(Q3Server.State.RUNNING, server.state());
        assertFalse(audio.closed);
        assertFalse(files.closed);
      }
    }
  }

  @Test
  void unknownGuestServicesFailWithVmContextAndCloseDoesNotReenterFaultedGuest() throws Exception {
    var bad =
        new ClientTestData.Program()
            .op(Opcode.ENTER, 64)
            .call(999)
            .op(Opcode.CONST, 0)
            .op(Opcode.LEAVE, 64)
            .bytes();
    var files =
        new ClientTestData.Files(
            Map.of("vm/qagame.qvm", ClientTestData.server(), "vm/cgame.qvm", bad));
    try (var server =
        new Q3Server(
            files,
            "fixture",
            BspReader.read(BspFixture.map(false)),
            null,
            s -> {},
            Clock.systemUTC())) {
      server.initialize(1000, 1);
      server.connect(0, Map.of("name", "Synthetic"));
      try (var client =
          new Q3Client(files, server, frame -> {}, new ClientTestData.Audio(), s -> {})) {
        var failure =
            assertThrows(
                dev.bluevista.craftq3.vm.QvmException.class, () -> client.initialize(0, 640, 480));
        assertTrue(failure.getMessage().contains("999"));
        assertEquals(Q3Client.State.FAILED, client.state());
        assertThrows(IllegalStateException.class, () -> client.frame(1000, 640, 480));
      }
    }
  }
}

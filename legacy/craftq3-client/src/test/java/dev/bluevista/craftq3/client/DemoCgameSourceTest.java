package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.client.demo.DemoPlayback;
import dev.bluevista.craftq3.core.demo.DemoRecord;
import dev.bluevista.craftq3.core.demo.DemoWriter;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DemoCgameSourceTest {
  @Test
  void presentationConsumesRecordedCommandsAndRetainsSnapshotOperationMetadata() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DemoWriter(bytes)) {
      write(out, 10, game(5, 91));
      write(
          out,
          11,
          new Command(6, "cs 100 before"),
          new Frame(snapshot(11, 1500), null),
          new Command(7, "cs 100 after"));
    }
    try (var playback = playback(bytes)) {
      playback.read();
      var source = new DemoCgameSource(playback);
      assertEquals(new CgameSource.Initialization(3, 10, 5, 0, 0), source.initialize(3));
      assertTrue(source.demoPlayback());
      playback.read();
      assertEquals(new CgameSource.SnapshotNumber(11, 1500), source.currentSnapshot());
      var snapshot = source.snapshot(11).orElseThrow();
      assertEquals(6, snapshot.serverCommandSequence());
      assertEquals(-1, snapshot.serverCommandCount());
      assertEquals(0, snapshot.ping());
      assertEquals("initial", source.configStrings().get(100));
      assertEquals(Optional.of("cs 100 before"), source.serverCommand(6));
      assertEquals("before", source.configStrings().get(100));
      assertEquals(Optional.of("cs 100 after"), source.serverCommand(7));
      assertEquals("after", source.configStrings().get(100));
      source.userCommand(UserCommand.idle(1700));
      source.clientCommand("score");
      assertEquals(7, playback.commandSequence());
      assertThrows(IllegalArgumentException.class, () -> source.snapshot(12));
      playback.read();
      assertTrue(playback.ended());
    }
  }

  @Test
  void expiredCommandsPreserveCursorMissingSlotsAdvanceAndDemoSystemInfoHasNoHostCallbacks()
      throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DemoWriter(bytes)) {
      write(out, 10, game(5, 91));
      write(
          out,
          11,
          new Command(
              100, "cs 1 \"\\sv_serverid\\42\\sv_pure\\1\\fs_game\\different\\sv_paks\\999\""));
    }
    try (var playback = playback(bytes)) {
      playback.read();
      var source = new DemoCgameSource(playback);
      source.initialize(3);
      playback.read();
      assertEquals(Optional.empty(), source.serverCommand(36));
      assertEquals(5, source.lastExecutedCommand());
      assertEquals(Optional.of(""), source.serverCommand(37));
      assertEquals(37, source.lastExecutedCommand());
      assertThrows(IllegalArgumentException.class, () -> source.serverCommand(101));
      source.serverCommand(100);
      assertEquals(42, source.serverId());
      assertTrue(source.configStrings().get(1).contains("different"));
      assertEquals(100, source.restart().serverCommandSequence());
    }
  }

  @Test
  void rendererRestartSkipsExpiredHistoryAndGamestateReplacesSource() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DemoWriter(bytes)) {
      write(out, 10, game(0, 91));
      write(out, 11, new Command(100, "cs 100 latest"), new Frame(snapshot(11, 1000), null));
      write(out, 12, game(100, 92));
    }
    try (var playback = playback(bytes)) {
      playback.read();
      var old = new DemoCgameSource(playback);
      old.initialize(3);
      playback.read();
      assertEquals(100, old.restart().serverCommandSequence());
      assertEquals("latest", old.configStrings().get(100));
      playback.read();
      assertThrows(IllegalStateException.class, old::refresh);
      var next = new DemoCgameSource(playback);
      assertEquals(12, next.initialize(3).serverMessageSequence());
      assertEquals(92, next.serverId());
      assertEquals(0, next.currentSnapshot().number());
    }
  }

  @Test
  void missingDeltaConsumesSurroundingCommandsWithoutReplacingLatestValidSnapshot()
      throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new DemoWriter(bytes)) {
      write(out, 89, game(4, 91));
      write(out, 90, new Frame(snapshot(90, 1000), null));
      write(
          out,
          100,
          new Command(5, "print before"),
          new Frame(snapshot(100, 1100), snapshot(99, 1050)),
          new Command(6, "print after"));
    }
    var events = new ArrayList<String>();
    try (var playback =
        new DemoPlayback(
            new ByteArrayInputStream(bytes.toByteArray()),
            new DemoPlayback.Listener() {
              public void gamestate() {
                events.add("game");
              }

              public void snapshot(int time, int flags) {
                events.add("snapshot " + time);
              }
            })) {
      playback.read();
      playback.read();
      var source = new DemoCgameSource(playback);
      source.initialize(3);
      playback.read();
      assertEquals(List.of("game", "snapshot 1000"), events);
      assertEquals(90, source.currentSnapshot().number());
      assertEquals(6, playback.commandSequence());
      assertEquals(Optional.of("print before"), source.serverCommand(5));
      assertEquals(Optional.of("print after"), source.serverCommand(6));
    }
  }

  private static DemoPlayback playback(ByteArrayOutputStream bytes) {
    return new DemoPlayback(
        new ByteArrayInputStream(bytes.toByteArray()), new DemoPlayback.Listener() {});
  }

  private static GameState game(int sequence, int id) {
    return new GameState(
        sequence,
        Map.of(0, "\\mapname\\q3dm1", 1, "\\sv_serverid\\" + id, 100, "initial"),
        Baselines.EMPTY,
        3,
        0);
  }

  private static SnapshotDeltaCodec.Snapshot snapshot(int sequence, int time) {
    return new SnapshotDeltaCodec.Snapshot(
        sequence, time, 0, new byte[0], new byte[468], List.of());
  }

  private static void write(DemoWriter out, int sequence, Operation... operations)
      throws Exception {
    var payload = new MessageWriter();
    ServerMessageCodec.write(payload, new Message(0, List.of(operations)), Baselines.EMPTY);
    out.append(new DemoRecord(sequence, payload.bytes()));
  }
}

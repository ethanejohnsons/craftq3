package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.client.demo.DemoPlayback;
import dev.bluevista.craftq3.core.demo.DemoRecord;
import dev.bluevista.craftq3.core.demo.DemoWriter;
import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Command;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Frame;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Message;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.Operation;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.core.net.delta.EntityDeltaCodec;
import dev.bluevista.craftq3.core.net.delta.PlayerDeltaCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DemoMissingDeltaBodyTest {
  @Test
  void consumesUnusablePlayerArraysAndEntityDeltasBeforeReadingTheFollowingCommand()
      throws Exception {
    var initial = snapshot(20, 1000, 1, 10.5f, List.of(entity(2, 3, 11.25f)));
    var missing = snapshot(29, 1250, 2, 37.75f, List.of(entity(2, 4, 100), entity(5, 5, -55)));
    var discarded =
        snapshot(30, 1300, 5, -219.125f, List.of(entity(2, 7, 223.25f), entity(7, 8, -123.5f)));
    // This body changes all four player arrays plus scalar fields, changes one entity,
    // removes another and adds a third. The absent sequence 29 is never written to the demo.
    var badRecord =
        encoded(
            30,
            new Command(5, "cs 100 before"),
            new Frame(discarded, missing),
            new Command(6, "cs 100 after"));
    var control =
        ServerMessageCodec.read(
            new MessageReader(badRecord.payload()),
            30,
            Baselines.EMPTY,
            n -> n == 29 ? missing : null);
    assertSnapshot(discarded, ((Frame) control.operations().get(1)).current());

    var recovery = snapshot(31, 1350, 4, 71.25f, List.of(entity(7, 9, 45.5f)));
    var subsequent = snapshot(32, 1400, 4, 72.75f, List.of(entity(7, 10, 49.25f)));
    var bytes = new ByteArrayOutputStream();
    try (var out = new DemoWriter(bytes)) {
      out.append(
          encoded(
              19,
              new GameState(
                  4,
                  Map.of(0, "\\mapname\\fixture", 1, "\\sv_serverid\\91", 100, "initial"),
                  Baselines.EMPTY,
                  3,
                  0)));
      out.append(encoded(20, new Frame(initial, null)));
      out.append(badRecord);
      out.append(encoded(31, new Frame(recovery, null)));
      out.append(encoded(32, new Frame(subsequent, recovery)));
    }
    var events = new ArrayList<String>();
    try (var playback =
        new DemoPlayback(
            new ByteArrayInputStream(bytes.toByteArray()),
            new DemoPlayback.Listener() {
              @Override
              public void gamestate() {
                events.add("game");
              }

              @Override
              public void snapshot(int time, int flags) {
                events.add(time + "/" + flags);
              }
            })) {
      playback.read();
      var source = new DemoCgameSource(playback);
      source.initialize(3);
      playback.read();
      var retained = playback.snapshot().orElseThrow();
      assertSnapshot(initial, retained);
      assertEquals(4, source.snapshot(20).orElseThrow().serverCommandSequence());
      playback.read();
      assertEquals(List.of("game", "1000/1"), events);
      assertSame(retained, playback.snapshot().orElseThrow());
      assertEquals(new CgameSource.SnapshotNumber(20, 1000), source.currentSnapshot());
      assertTrue(playback.snapshot(30).isEmpty());
      assertEquals(6, playback.commandSequence());
      assertEquals(4, source.snapshot(20).orElseThrow().serverCommandSequence());
      assertEquals("initial", source.configStrings().get(100));
      assertEquals(Optional.of("cs 100 before"), source.serverCommand(5));
      assertEquals("before", source.configStrings().get(100));
      assertEquals(Optional.of("cs 100 after"), source.serverCommand(6));
      assertEquals("after", source.configStrings().get(100));

      playback.read();
      assertSnapshot(recovery, playback.snapshot().orElseThrow());
      assertEquals(6, source.snapshot(31).orElseThrow().serverCommandSequence());
      playback.read();
      assertSnapshot(subsequent, playback.snapshot().orElseThrow());
      assertEquals(List.of("game", "1000/1", "1350/4", "1400/4"), events);
      assertEquals(5, playback.recordsRead());
      playback.read();
      assertTrue(playback.ended());
    }
  }

  private static DemoRecord encoded(int sequence, Operation... operations) {
    var out = new MessageWriter();
    ServerMessageCodec.write(out, new Message(0, List.of(operations)), Baselines.EMPTY);
    return new DemoRecord(sequence, out.bytes());
  }

  private static Snapshot snapshot(
      int sequence, int time, int flags, float position, List<byte[]> entities) {
    byte[] player = new byte[PlayerDeltaCodec.STATE_BYTES];
    var words = ByteBuffer.wrap(player).order(ByteOrder.LITTLE_ENDIAN);
    words.putInt(0, time - 25).putFloat(20, position).putFloat(32, position * .5f).putInt(144, 5);
    words.putInt(PlayerDeltaCodec.STATS_OFFSET, time / 10);
    words.putInt(PlayerDeltaCodec.PERSISTANT_OFFSET + 12, -time / 20);
    words.putInt(PlayerDeltaCodec.POWERUPS_OFFSET + 36, 100000 + time);
    words.putInt(PlayerDeltaCodec.AMMO_OFFSET + 20, time / 50);
    return new Snapshot(
        sequence, time, flags, new byte[] {3, (byte) sequence, (byte) 0xaa}, player, entities);
  }

  private static byte[] entity(int number, int type, float position) {
    byte[] entity = new byte[EntityDeltaCodec.STATE_BYTES];
    ByteBuffer.wrap(entity)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0, number)
        .putInt(4, type)
        .putInt(16, type * 100)
        .putFloat(24, position)
        .putFloat(44, position * .25f)
        .putInt(180, type + 16);
    return entity;
  }

  private static void assertSnapshot(Snapshot expected, Snapshot actual) {
    assertEquals(expected.sequence(), actual.sequence());
    assertEquals(expected.time(), actual.time());
    assertEquals(expected.flags(), actual.flags());
    assertArrayEquals(expected.areaMask(), actual.areaMask());
    assertArrayEquals(expected.player(), actual.player());
    assertEquals(expected.entities().size(), actual.entities().size());
    for (int i = 0; i < expected.entities().size(); i++)
      assertArrayEquals(expected.entities().get(i), actual.entities().get(i));
  }
}

package dev.bluevista.craftq3.client.demo;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.client.DemoServerClock;
import dev.bluevista.craftq3.core.demo.DemoReader;
import dev.bluevista.craftq3.core.demo.DemoRecord;
import dev.bluevista.craftq3.core.demo.DemoWriter;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.*;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoPlayerTest {
  private static final GameState GAME = game("first");

  @Test
  void primeStopsAtFirstGamestateAndFirstClockFrameDoesNotReadAhead() throws Exception {
    try (var player = player(demo(NoOp.INSTANCE, GAME, frame(3, 1000), frame(4, 10000)))) {
      player.prime();
      assertEquals(2, player.playback().recordsRead());
      assertEquals(1, player.playback().generation());
      assertEquals("first", player.playback().initialConfigStrings().get(0));
      assertTrue(player.playback().snapshot().isEmpty());
      assertTrue(player.frame(16, 0, 1, false, false, 100).isEmpty());
      assertEquals(2, player.playback().recordsRead());
      assertEquals(1000, player.frame(16, 0, 1, false, false, 116).orElseThrow());
      assertEquals(4, player.playback().recordsRead());
      assertEquals(10000, player.playback().snapshot().orElseThrow().time());
    }
  }

  @Test
  void elapsedScalingRetainsFractionsAndClampsHostStallsWithoutApplyingScaleTwice()
      throws Exception {
    try (var player = player(demo(GAME, frame(2, 1000), frame(3, 10000)))) {
      player.prime();
      assertTrue(player.frame(1, 0, .5f, false, false, 0).isEmpty());
      assertEquals(1000, player.frame(1, 0, .5f, false, false, 0).orElseThrow());
      assertEquals(1000, player.frame(1, 0, .5f, false, false, 0).orElseThrow());
      assertEquals(1001, player.frame(1, 0, .5f, false, false, 0).orElseThrow());
      assertEquals(1001, player.frame(-100, 0, 1, false, false, 0).orElseThrow());
      assertEquals(1201, player.frame(1000, 0, 1, false, false, 0).orElseThrow());
      assertEquals(1201, player.frame(100, 0, 0, false, false, 0).orElseThrow());
      var before = player.clock().state();
      for (float scale : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 1001}) {
        assertThrows(
            IllegalArgumentException.class, () -> player.frame(100, 0, scale, false, false, 0));
        assertEquals(before, player.clock().state());
      }
      assertEquals(1202, player.frame(1, 0, 1, false, false, 0).orElseThrow());
    }
  }

  @Test
  void freezeAndTimedemoUseSeparatePresentationAndWallClocks() throws Exception {
    try (var player = player(demo(GAME, frame(2, 1000), frame(3, 10000)))) {
      player.prime();
      player.frame(0, 0, 1, true, false, 0);
      assertEquals(0, player.frame(16, 0, 1, true, false, 0).orElseThrow());
      assertEquals(2, player.playback().recordsRead());
      assertEquals(1050, player.frame(16, 0, 1, true, true, 1000).orElseThrow());
      assertEquals(3, player.playback().recordsRead());
      assertEquals(1100, player.frame(0, 0, 0, true, true, 1007).orElseThrow());
      assertEquals(7, player.clock().timedemo().minimumDuration());
      assertEquals(1000, player.clock().state().oldTime());
      assertEquals(1016, player.frame(0, 0, 1, false, false, 1008).orElseThrow());
    }
  }

  @Test
  void markerAndPhysicalEofStopPlaybackWithoutAnotherSnapshot() throws Exception {
    byte[] complete = demo(GAME, frame(2, 1000), frame(3, 1100));
    for (boolean marker : new boolean[] {false, true}) {
      byte[] bytes = marker ? complete : Arrays.copyOf(complete, complete.length - 8);
      try (var player = player(bytes)) {
        player.prime();
        player.frame(0, 0, 1, false, false, 0);
        assertEquals(1000, player.frame(16, 0, 1, false, false, 0).orElseThrow());
        assertTrue(player.frame(100, 0, 1, false, false, 0).isEmpty());
        assertTrue(player.playback().ended());
        assertTrue(player.clock().state().ended());
        assertEquals(
            marker ? DemoReader.End.MARKER : DemoReader.End.PHYSICAL_EOF,
            player.playback().endReason());
        assertTrue(player.frame(16, 0, 1, false, false, 0).isEmpty());
        assertEquals(3, player.playback().recordsRead());
      }
    }
  }

  @Test
  void readAheadYieldsAtNextGamestateBeforeActivatingItsSnapshots() throws Exception {
    try (var player =
        player(demo(GAME, frame(2, 1000), game("next"), frame(4, 2000), frame(5, 2100)))) {
      player.prime();
      player.frame(0, 0, 1, false, false, 0);
      assertTrue(player.frame(16, 0, 1, false, false, 0).isEmpty());
      assertEquals(2, player.playback().generation());
      assertEquals(3, player.playback().recordsRead());
      assertEquals("next", player.playback().initialConfigStrings().get(0));
      assertTrue(player.playback().snapshot().isEmpty());
      assertEquals(2000, player.frame(16, 0, 1, false, false, 0).orElseThrow());
      assertEquals(5, player.playback().recordsRead());
    }
  }

  @Test
  void startupRejectsMissingMalformedAndSnapshotFirstInputAndBoundsNoopWork() throws Exception {
    byte[] invalidLength =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(-2).array();
    var invalidGame = new GameState(0, Map.of(), Baselines.EMPTY, 64, 0);
    for (byte[] bytes :
        List.of(
            new byte[0],
            demo(),
            new byte[] {1, 2, 3},
            invalidLength,
            demo(frame(1, 1000)),
            demo(invalidGame))) {
      try (var player = player(bytes)) {
        assertThrows(IOException.class, player::prime);
        assertTrue(player.playback().gameState().isEmpty());
      }
    }
    Operation[] noops = new Operation[DemoServerClock.MAX_READS_PER_FRAME + 1];
    Arrays.fill(noops, NoOp.INSTANCE);
    try (var player = player(demo(noops))) {
      assertThrows(IOException.class, player::prime);
      assertEquals(DemoServerClock.MAX_READS_PER_FRAME, player.playback().recordsRead());
    }
  }

  @Test
  void closingBufferedPlaybackOwnsStreamAndRejectsFramesThatWouldNotNeedIo() throws Exception {
    class Tracked extends ByteArrayInputStream {
      int closes;

      Tracked(byte[] bytes) {
        super(bytes);
      }

      @Override
      public void close() {
        closes++;
      }
    }
    var input = new Tracked(demo(GAME, frame(2, 1000), frame(3, 10000)));
    var player = new DemoPlayer(input);
    player.prime();
    player.frame(0, 0, 1, false, false, 0);
    assertEquals(1000, player.frame(16, 0, 1, false, false, 0).orElseThrow());
    player.close();
    player.close();
    assertEquals(1, input.closes);
    assertThrows(IOException.class, player::prime);
    assertThrows(IOException.class, () -> player.frame(16, 0, 1, false, false, 0));
  }

  private static DemoPlayer player(byte[] bytes) {
    return new DemoPlayer(new ByteArrayInputStream(bytes));
  }

  private static GameState game(String name) {
    return new GameState(0, Map.of(0, name), Baselines.EMPTY, 0, 42);
  }

  private static Frame frame(int sequence, int time) {
    return new Frame(new Snapshot(sequence, time, 0, new byte[0], new byte[468], List.of()), null);
  }

  private static byte[] demo(Operation... operations) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var writer = new DemoWriter(output)) {
      int sequence = 0;
      for (var operation : operations) {
        var message = new MessageWriter();
        ServerMessageCodec.write(message, new Message(0, List.of(operation)), Baselines.EMPTY);
        writer.append(new DemoRecord(++sequence, message.bytes()));
      }
    }
    return output.toByteArray();
  }
}

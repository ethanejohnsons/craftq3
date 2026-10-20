package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ServerMessageCodec.Download;
import dev.bluevista.craftq3.core.net.ServerMessageCodec.GameState;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

final class DownloadTransferTest {
  @Test
  void downloadBodiesKeepOwnedBytesAndWrappedBlocksHaveNoSizeHeader() {
    byte[] data = {1, 2, 3};
    var first = new Download(0, 3, data, null);
    data[0] = 99;
    for (var packet :
        List.of(
            first,
            new Download(65535, null, new byte[] {4}, null),
            new Download(0, null, new byte[] {5}, null),
            new Download(0, -1, new byte[0], "denied"))) {
      int expected = packet.fileSize() != null ? 0 : packet.block() == 0 ? 65536 : 65535;
      var writer = new MessageWriter();
      DownloadMessageCodec.write(writer, packet);
      var reader = new MessageReader(writer.bytes(), writer.bitPosition());
      var decoded = DownloadMessageCodec.read(reader, expected);
      assertEquals(packet.block(), decoded.block());
      assertEquals(packet.fileSize(), decoded.fileSize());
      assertEquals(packet.error(), decoded.error());
      assertArrayEquals(packet.data(), decoded.data());
      assertEquals(writer.bitPosition(), reader.bitPosition());
      var shortReader = new MessageReader(writer.bytes(), writer.bitPosition() - 1);
      assertThrows(
          IllegalArgumentException.class, () -> DownloadMessageCodec.read(shortReader, expected));
      assertEquals(0, shortReader.bitPosition());
    }
    assertArrayEquals(new byte[] {1, 2, 3}, first.data());
    first.data()[0] = 77;
    assertEquals(1, first.data()[0]);
  }

  @Test
  void windowRetransmitsAfterTimeoutAndRequiresOrderedTransmittedAcknowledgements()
      throws Exception {
    try (var sender =
        new DownloadSender(new ByteArrayInputStream(new byte[100 * 1024]), 100 * 1024)) {
      for (int i = 0; i < 48; i++) assertEquals(i, sender.next(1000).orElseThrow().block());
      assertTrue(sender.next(2000).isEmpty());
      assertEquals(0, sender.next(2001).orElseThrow().block());
      assertThrows(IOException.class, () -> sender.acknowledge(1, 2001));
      sender.acknowledge(0, 2001);
      assertThrows(IOException.class, () -> sender.acknowledge(0, 2001));
    }
    try (var sender = new DownloadSender(new ByteArrayInputStream(new byte[2]), 2)) {
      assertThrows(IOException.class, () -> sender.acknowledge(0, 1));
      assertEquals(0, sender.next(1).orElseThrow().block());
      sender.acknowledge(0, 1);
      assertEquals(1, sender.next(1).orElseThrow().block());
      sender.acknowledge(1, 1);
      assertTrue(sender.complete());
    }
  }

  @Test
  void receiverIgnoresOutOfOrderBlocksAndEnforcesAdvertisedLength() throws Exception {
    var sink = new ByteArrayOutputStream();
    try (var receiver = new DownloadReceiver(sink)) {
      assertFalse(receiver.accept(new Download(1, null, new byte[] {9}, null)).accepted());
      assertEquals(0, receiver.accept(new Download(0, 3, new byte[] {1, 2}, null)).acknowledge());
      assertFalse(receiver.accept(new Download(0, null, new byte[] {9}, null)).accepted());
      assertThrows(
          IOException.class, () -> receiver.accept(new Download(1, null, new byte[0], null)));
      assertThrows(
          IOException.class, () -> receiver.accept(new Download(1, null, new byte[] {3, 4}, null)));
      assertEquals(1, receiver.accept(new Download(1, null, new byte[] {3}, null)).acknowledge());
      assertTrue(receiver.accept(new Download(2, null, new byte[0], null)).complete());
      assertArrayEquals(new byte[] {1, 2, 3}, sink.toByteArray());
    }
  }

  @Test
  void negativeErrorsAndOversizedFilesNeverWriteToTheSink() throws Exception {
    var sink = new ByteArrayOutputStream();
    try (var receiver = new DownloadReceiver(sink)) {
      assertThrows(
          IOException.class, () -> receiver.accept(new Download(0, -1, new byte[0], "disabled")));
      assertThrows(
          IOException.class,
          () -> receiver.accept(new Download(0, Integer.MAX_VALUE, new byte[] {1}, null)));
      assertEquals(0, sink.size());
    }
  }

  @Test
  void streamsCrossTheSixtyFourMiBBlockNumberBoundaryWithoutBufferingTheFile() throws Exception {
    int size = 65536 * 1024 + 3;
    InputStream source =
        new InputStream() {
          int remaining = size;

          public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 42;
          }

          public int read(byte[] bytes, int offset, int count) {
            if (remaining == 0) return -1;
            int n = Math.min(remaining, count);
            Arrays.fill(bytes, offset, offset + n, (byte) 42);
            remaining -= n;
            return n;
          }
        };
    try (var sender = new DownloadSender(source, size);
        var receiver = new DownloadReceiver(OutputStream.nullOutputStream())) {
      int expected = 0;
      while (!sender.complete()) {
        var packet = sender.next(expected).orElseThrow();
        if (expected == 0 || expected >= 65535) {
          var writer = new MessageWriter();
          DownloadMessageCodec.write(writer, packet);
          packet = DownloadMessageCodec.read(new MessageReader(writer.bytes()), expected);
        }
        var progress = receiver.accept(packet);
        assertEquals(expected, progress.acknowledge());
        sender.acknowledge(expected, expected);
        expected++;
        if (progress.complete()) assertEquals(size, progress.received());
      }
      assertEquals(65538, expected);
    }
  }

  @Test
  void changedSourcesFailBeforeSuccessfulCompletion() throws Exception {
    try (var shortFile = new DownloadSender(new ByteArrayInputStream(new byte[1]), 2);
        var longFile = new DownloadSender(new ByteArrayInputStream(new byte[2]), 1)) {
      assertThrows(IOException.class, () -> shortFile.next(0));
      assertThrows(IOException.class, () -> longFile.next(0));
      assertFalse(shortFile.complete());
      assertFalse(longFile.complete());
    }
    try (var sender = new DownloadSender(InputStream.nullInputStream(), 0);
        var receiver = new DownloadReceiver(OutputStream.nullOutputStream())) {
      assertTrue(receiver.accept(sender.next(0).orElseThrow()).complete());
      sender.acknowledge(0, 0);
      assertTrue(sender.complete());
    }
  }

  @Test
  void establishedSessionsCarryDownloadOperationsAndResetForTheNextRequestedFile() {
    var server = new Protocol68ServerSession(42, 1234);
    var client = new Protocol68ClientSession(42, 1234);
    server.queueGameState(
        new GameState(0, Map.of(1, "\\sv_serverid\\100"), Baselines.EMPTY, 0, 0), 100);
    deliver(server, client);
    for (int file = 0; file < 2; file++) {
      client.beginDownload();
      for (var packet :
          List.of(
              new Download(0, 1, new byte[] {7}, null), new Download(1, null, new byte[0], null))) {
        server.queueDownload(packet);
        var received = deliver(server, client);
        assertEquals(Protocol68ClientSession.Status.ACCEPTED, received.status());
        var decoded = (Download) received.message().operations().getLast();
        assertEquals(packet.fileSize(), decoded.fileSize());
        assertArrayEquals(packet.data(), decoded.data());
      }
    }
  }

  private static Protocol68ClientSession.Received deliver(
      Protocol68ServerSession server, Protocol68ClientSession client) {
    Protocol68ClientSession.Received result = null;
    while (server.hasPendingPacket()) result = client.receive(server.pollPacket().orElseThrow());
    return result;
  }
}

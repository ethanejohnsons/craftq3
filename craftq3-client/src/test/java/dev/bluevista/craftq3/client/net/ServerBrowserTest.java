package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.fs.WritableFiles;
import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import dev.bluevista.craftq3.platform.net.DiscoveryTransport;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ServerBrowserTest {
  private static final InetSocketAddress SERVER = peer("127.0.0.1", 27960);
  private static final InetSocketAddress MASTER = peer("127.0.0.1", 27950);
  private static final String INFO =
      "infoResponse\n"
          + "\\protocol\\68\\gamename\\Quake3Arena\\hostname\\Private"
          + " QA\\mapname\\q3dm1\\clients\\2\\sv_maxclients\\8";

  private record Sent(InetSocketAddress address, byte[] packet) {
    String text() {
      return new String(ConnectionlessPacket.payload(packet), StandardCharsets.ISO_8859_1);
    }
  }

  private static final class Wire implements ServerBrowser.Wire {
    final List<Sent> sent = new ArrayList<>();
    final ArrayDeque<DiscoveryTransport.Poll> incoming = new ArrayDeque<>();
    boolean closed, writable = true;

    public boolean send(InetSocketAddress peer, byte[] packet) {
      if (!writable) return false;
      sent.add(new Sent(peer, packet.clone()));
      return true;
    }

    public DiscoveryTransport.Poll poll() {
      return incoming.isEmpty()
          ? new DiscoveryTransport.Poll(DiscoveryTransport.Status.EMPTY, null, new byte[0])
          : incoming.removeFirst();
    }

    public void close() {
      closed = true;
    }

    void receive(InetSocketAddress source, String text) {
      receive(source, ConnectionlessPacket.text(text));
    }

    void receive(InetSocketAddress source, byte[] packet) {
      incoming.add(new DiscoveryTransport.Poll(DiscoveryTransport.Status.PACKET, source, packet));
    }
  }

  private static final class Saved implements WritableFiles {
    final Map<VirtualPath, byte[]> values = new HashMap<>();

    public Optional<byte[]> read(VirtualPath path) {
      return Optional.ofNullable(values.get(path)).map(byte[]::clone);
    }

    public void write(VirtualPath path, byte[] value) {
      values.put(path, value.clone());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final int[] now = {1000}, opens = {0};
    final Wire wire = new Wire();
    final CvarSystem cvars = new CvarSystem();
    final List<String> output = new ArrayList<>();
    final ServerBrowser browser;

    Fixture() {
      this(null, new BrowserResolver());
    }

    Fixture(Saved saved, BrowserResolver resolver) {
      browser =
          new ServerBrowser(
              cvars,
              saved,
              output::add,
              () -> now[0],
              resolver,
              ipv6 -> {
                opens[0]++;
                return wire;
              });
    }

    public void close() throws java.io.IOException {
      browser.close();
    }
  }

  @Test
  void originalUiFavoritesQueueOnlyThenPublishPingAndInfoFromMatchingPeer() throws Exception {
    try (var f = new Fixture()) {
      var b = f.browser;
      assertEquals(0, f.opens[0]);
      assertEquals(1, b.add(3, "Favorite", "127.0.0.1:27960"));
      assertEquals(0, b.add(3, "Same", "127.0.0.1:27960"));
      b.resetPings(3);
      b.markVisible(3, -1, 2);
      assertTrue(b.updatePings(3));
      assertEquals(0, f.opens[0]);
      b.pump();
      assertEquals("getinfo xxx", f.wire.sent.getFirst().text());
      assertEquals(SERVER, f.wire.sent.getFirst().address());
      f.wire.receive(peer("127.0.0.2", 27960), INFO);
      f.now[0] += 17;
      b.pump();
      assertEquals(0, b.serverPing(3, 0));
      assertFalse(b.info(3, 0).contains("Private QA"));
      f.wire.receive(SERVER, INFO);
      b.pump();
      assertEquals(17, b.serverPing(3, 0));
      assertEquals(2, b.visible(3, 0));
      assertTrue(b.info(3, 0).contains("\\hostname\\Private QA"));
      assertEquals(17, b.ping(0).milliseconds());
      b.clearPing(0);
      assertFalse(b.pingOccupied(0));
      b.remove(3, "127.0.0.1:27960");
      assertEquals(0, b.count(3));
    }
  }

  @Test
  void masterRepliesRequireRequestedPeerAndKeepRetainedVisibilityAndDeduplicate() throws Exception {
    try (var f = new Fixture()) {
      var b = f.browser;
      b.markVisible(2, -1, 2);
      b.discoverGlobal(List.of("127.0.0.1:27950"), 68, List.of("empty", "full"));
      assertEquals(-1, b.count(2));
      b.pump();
      assertEquals("getservers 68 empty full", f.wire.sent.getFirst().text());
      byte[] response =
          masterResponse(SERVER, SERVER, peer("127.0.0.1", 27961), peer("0.0.0.0", 0));
      f.wire.receive(peer("127.0.0.2", 27950), response);
      b.pump();
      assertEquals(-1, b.count(2));
      f.wire.receive(MASTER, response);
      b.pump();
      assertEquals(2, b.count(2));
      assertEquals(2, b.count(1));
      assertEquals("127.0.0.1:27960", b.address(2, 0));
      assertEquals(2, b.visible(2, 0));
      assertEquals(-1, b.serverPing(2, 0));
      b.discoverGlobal(List.of("127.0.0.1:27950"), 68, List.of());
      f.now[0] += 10000;
      b.pump();
      assertEquals(0, b.count(2));
      assertThrows(
          IllegalArgumentException.class,
          () -> b.discoverGlobal(List.of("127.0.0.1"), 68, List.of("x\nquit")));
    }
  }

  @Test
  void localDiscoveryIsExplicitBoundedAndExpiresUnsolicitedResponses() throws Exception {
    try (var f = new Fixture()) {
      var b = f.browser;
      b.discoverLocal(List.of(SERVER));
      b.pump();
      assertEquals(2, f.wire.sent.size());
      assertTrue(
          f.wire.sent.stream()
              .allMatch(s -> s.address().equals(SERVER) && s.text().equals("getinfo xxx")));
      f.wire.receive(SERVER, INFO);
      b.pump();
      assertEquals(1, b.count(0));
      f.now[0] += 10000;
      f.wire.receive(peer("127.0.0.1", 27961), INFO);
      b.pump();
      assertEquals(1, b.count(0));
      for (int i = 0; i < 100; i++) f.wire.receive(SERVER, new byte[] {1});
      b.pump();
      assertEquals(36, f.wire.incoming.size());
      b.pump();
      assertEquals(0, f.wire.incoming.size());
    }
  }

  @Test
  void statusCachesBoundedReplyAndDoesNotAcceptOtherPeers() throws Exception {
    try (var f = new Fixture()) {
      var b = f.browser;
      assertEquals(750, f.cvars.integer("cl_serverStatusResendTime"));
      assertTrue(b.status("127.0.0.1:27960", 8192).isEmpty());
      b.pump();
      assertEquals("getstatus", f.wire.sent.getFirst().text());
      f.wire.receive(peer("127.0.0.1", 27961), "statusResponse\n\\sv_hostname\\Wrong\n");
      b.pump();
      assertTrue(b.status("127.0.0.1:27960", 8192).isEmpty());
      f.wire.receive(SERVER, "statusResponse\n\\sv_hostname\\QA\n7 17 \"Player\"\n");
      b.pump();
      assertEquals(
          "\\sv_hostname\\QA\\\\7 17 \"Player\"\\",
          b.status("127.0.0.1:27960", 8192).orElseThrow());
      assertEquals("\\sv", b.status("127.0.0.1:27960", 4).orElseThrow());
      b.resetStatus(null);
      assertTrue(b.status("127.0.0.1:27960", 8192).isEmpty());
    }
  }

  @Test
  void favoriteDnsSurvivesUiMetadataChangesAndCacheRoundTrips() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var saved = new Saved();
    try (var resolver =
            new BrowserResolver(
                host -> {
                  entered.countDown();
                  try {
                    if (!release.await(3, TimeUnit.SECONDS))
                      throw new AssertionError("Lookup not released");
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                  }
                  return new InetAddress[] {SERVER.getAddress()};
                },
                System::currentTimeMillis);
        var f = new Fixture(saved, resolver)) {
      var b = f.browser;
      assertEquals(1, b.add(3, "DNS Favorite", "qa.invalid:27960"));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals(1, b.count(3));
      assertEquals("", b.address(3, 0));
      b.resetPings(3);
      b.markVisible(3, 0, 2);
      release.countDown();
      long deadline = System.nanoTime() + 3_000_000_000L;
      while (b.address(3, 0).isEmpty() && System.nanoTime() < deadline) {
        b.pump();
        Thread.sleep(1);
      }
      assertEquals("127.0.0.1:27960", b.address(3, 0));
      assertEquals(-1, b.serverPing(3, 0));
      assertEquals(2, b.visible(3, 0));
      b.saveCache();
    } finally {
      release.countDown();
    }
    try (var f = new Fixture(saved, new BrowserResolver())) {
      assertEquals(1, f.browser.count(3));
      assertEquals("127.0.0.1:27960", f.browser.address(3, 0));
      assertEquals(2, f.browser.visible(3, 0));
      assertEquals(0, f.opens[0]);
    }
  }

  @Test
  void wouldBlockQueueExpiresAndCloseStopsFurtherIo() throws Exception {
    try (var f = new Fixture()) {
      var b = f.browser;
      f.wire.writable = false;
      b.discoverLocal(List.of(SERVER));
      b.pump();
      assertTrue(f.wire.sent.isEmpty());
      f.now[0] += 1000;
      f.wire.writable = true;
      b.pump();
      assertTrue(f.wire.sent.isEmpty());
      b.close();
      assertTrue(f.wire.closed);
      b.pump();
      assertThrows(IllegalStateException.class, () -> b.discoverLocal(List.of(SERVER)));
    }
  }

  private static InetSocketAddress peer(String host, int port) {
    return new InetSocketAddress(InetAddress.ofLiteral(host), port);
  }

  private static byte[] masterResponse(InetSocketAddress... peers) {
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes("getserversResponse".getBytes(StandardCharsets.US_ASCII));
    for (var peer : peers) {
      bytes.write('\\');
      bytes.writeBytes(peer.getAddress().getAddress());
      bytes.write(peer.getPort() >>> 8);
      bytes.write(peer.getPort());
    }
    bytes.writeBytes("\\EOT".getBytes(StandardCharsets.US_ASCII));
    return ConnectionlessPacket.encode(bytes.toByteArray());
  }
}

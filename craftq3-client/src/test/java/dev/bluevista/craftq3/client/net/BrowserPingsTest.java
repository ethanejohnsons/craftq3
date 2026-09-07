package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserPingsTest {
  private final LanServerList servers = new LanServerList();
  private final List<BrowserPings.Request> sent = new ArrayList<>();
  private int now = 1000, maximum = 800;
  private final BrowserPings pings =
      new BrowserPings(servers, () -> now, () -> maximum, "Quake3Arena", 71, 68, sent::add);

  private static InetSocketAddress peer(int port) throws Exception {
    return new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port);
  }

  private static byte[] info(String tail) {
    return ("\\protocol\\71" + tail).getBytes(StandardCharsets.ISO_8859_1);
  }

  private void row(int source, int index, InetSocketAddress address, int ping, int visible) {
    servers.updateEntry(
        source,
        index,
        new LanServerList.Entry(
            address, "old", "oldmap", "oldgame", 9, 8, 7, 6, 5, 4, ping, visible, 3, 2, 1));
    servers.setCount(source, index + 1);
  }

  @Test
  void directRequestsKeepDuplicateAddressesAndBoundTheQueue() throws Exception {
    var address = peer(27960);
    assertEquals(0, pings.request(address));
    assertEquals(1, pings.request(address));
    assertEquals(new BrowserPings.Request(address, "getinfo xxx"), sent.getFirst());
    for (int index = 2; index < 32; index++)
      assertEquals(index, pings.request(peer(27000 + index)));
    assertEquals(32, pings.count());
    assertEquals(0, pings.request(peer(28000)));
    assertEquals(32, pings.count());
    assertEquals(peer(28000), pings.get(0).orElseThrow().address());
    pings.clear(-1);
    pings.clear(32);
    assertEquals(32, pings.count());
    assertTrue(pings.get(-1).isEmpty());
    assertEquals("", pings.info(32));
    assertTrue(pings.occupied(0));
    assertFalse(pings.occupied(-1));
    pings.clear(0);
    assertFalse(pings.occupied(0));
  }

  @Test
  void pollingExposesTimeoutWithoutConsumingSlotAndStillAcceptsLateReply() throws Exception {
    var address = peer(27960);
    pings.request(address);
    maximum = 1;
    now = 1099;
    assertEquals(0, pings.get(0).orElseThrow().milliseconds());
    now = 1100;
    assertEquals(100, pings.get(0).orElseThrow().milliseconds());
    now = 1250;
    assertEquals(250, pings.get(0).orElseThrow().milliseconds());
    assertEquals(1, pings.count());
    assertTrue(pings.receiveInfo(address, info("\\hostname\\late")));
    now = 1300;
    assertEquals(250, pings.get(0).orElseThrow().milliseconds());
    assertTrue(pings.updateVisible(LanServerList.FAVORITES));
    assertEquals(0, pings.count());
    assertFalse(pings.updateVisible(LanServerList.FAVORITES));
  }

  @Test
  void expectedReplySourcesRemainPendingUntilConsumedWithoutPolling() throws Exception {
    int[] reads = {0};
    var isolated =
        new BrowserPings(
            servers,
            () -> {
              reads[0]++;
              return now;
            },
            () -> maximum,
            "Quake3Arena",
            71,
            68,
            sent::add);
    var address = peer(27960);
    isolated.request(address);
    now += 2000;
    int before = reads[0];
    assertTrue(isolated.expects(address));
    assertFalse(isolated.expects(peer(27961)));
    assertFalse(isolated.expects(null));
    assertFalse(isolated.expects(InetSocketAddress.createUnresolved("example.invalid", 27960)));
    assertEquals(before, reads[0]);
    assertTrue(isolated.receiveInfo(address, info("")));
    assertFalse(isolated.expects(address));
    isolated.request(address);
    assertTrue(isolated.expects(address));
    isolated.clear(0);
    assertFalse(isolated.expects(address));
  }

  @Test
  void manualReuseDistinguishesPendingAgeFromCompletedLatency() throws Exception {
    var address = peer(27960);
    pings.request(address);
    now = 1499;
    pings.receiveInfo(address, info("\\hostname\\fast"));
    now = 2500;
    assertEquals(1, pings.request(peer(27961)));
    now = 2999;
    assertEquals(2, pings.request(peer(27962)));
    now = 3000;
    assertEquals(1, pings.request(peer(27963)));
    pings.clear(0);
    assertEquals("", pings.info(0));
    assertEquals(0, pings.request(peer(27964)));
    assertTrue(pings.info(0).contains("\\hostname\\fast"));
  }

  @Test
  void filtersProtocolGameAndEndpointButNotChallengeOrIpv6Scope() throws Exception {
    pings.discoverySource(2);
    var address = peer(27960);
    pings.request(address);
    now += 42;
    assertFalse(pings.receiveInfo(peer(27961), info("\\challenge\\xxx")));
    assertFalse(pings.receiveInfo(address, "\\protocol\\69".getBytes(StandardCharsets.ISO_8859_1)));
    assertFalse(pings.receiveInfo(address, info("\\gamename\\quake3arena")));
    assertTrue(
        pings.receiveInfo(
            address, "\\protocol\\68\\challenge\\wrong".getBytes(StandardCharsets.ISO_8859_1)));
    assertEquals(42, pings.get(0).orElseThrow().milliseconds());
    assertFalse(pings.receiveInfo(address, info("\\hostname\\duplicate")));

    byte[] bytes = new byte[16];
    bytes[0] = (byte) 0xfe;
    bytes[1] = (byte) 0x80;
    bytes[15] = 1;
    var first = new InetSocketAddress(Inet6Address.getByAddress(null, bytes, 1), 27960);
    var second = new InetSocketAddress(Inet6Address.getByAddress(null, bytes, 2), 27960);
    int slot = pings.request(first);
    now++;
    assertTrue(pings.receiveInfo(second, info("\\nettype\\9")));
    assertTrue(pings.info(slot).startsWith("\\nettype\\2\\protocol\\71"));
  }

  @Test
  void repliesAndPollingUpdateAllCapacityRecordsWithDifferentNettypeViews() throws Exception {
    var address = peer(27960);
    for (int source : new int[] {0, 2, 3}) {
      row(source, 3, address, -1, 7);
      servers.setCount(source, 0);
    }
    pings.request(address);
    assertEquals("old", servers.entry(0, 3).orElseThrow().hostName());
    now += 42;
    pings.receiveInfo(
        address,
        info("\\hostname\\Authored\\nettype\\9\\clients\\6\\sv_maxclients\\12\\gametype\\4"));
    for (int source : new int[] {0, 2, 3}) {
      var entry = servers.entry(source, 3).orElseThrow();
      assertEquals(0, servers.count(source));
      assertEquals(9, entry.netType());
      assertEquals(6, entry.clients());
      assertEquals(12, entry.maxClients());
      assertEquals(7, entry.visible());
      assertEquals("", entry.mapName());
    }
    pings.get(0);
    for (int source : new int[] {0, 2, 3})
      assertEquals(1, servers.entry(source, 3).orElseThrow().netType());
  }

  @Test
  void reusedPendingInfoPropagatesUntilTheNewReplyArrives() throws Exception {
    var first = peer(27960);
    var second = peer(27961);
    pings.request(first);
    now += 42;
    pings.receiveInfo(first, info("\\hostname\\previous"));
    pings.clear(0);
    row(3, 0, second, -1, 1);
    assertTrue(pings.updateVisible(3));
    assertEquals("previous", servers.entry(3, 0).orElseThrow().hostName());
    assertEquals(0, servers.ping(3, 0));
    now++;
    pings.receiveInfo(second, info("\\hostname\\current"));
    assertEquals("current", servers.entry(3, 0).orElseThrow().hostName());
  }

  @Test
  void visibleQueueDrainsAfterEnqueueAndReusesFreedSlotsOnTheNextCall() throws Exception {
    for (int index = 0; index < 34; index++) row(2, index, peer(27000 + index), -1, 1);
    assertTrue(pings.updateVisible(2));
    assertEquals(32, sent.size());
    now += 42;
    pings.receiveInfo(peer(27000), info(""));
    assertTrue(pings.updateVisible(2));
    assertEquals(32, sent.size());
    assertEquals(31, pings.count());
    assertTrue(pings.updateVisible(2));
    assertEquals(33, sent.size());
    assertEquals(peer(27032), sent.getLast().address());
    assertFalse(pings.updateVisible(1));
    assertEquals(32, pings.count());
    assertEquals(1, pings.discoverySource());
    pings.discoverySource(0);
    assertFalse(pings.updateVisible(4));
    assertEquals(0, pings.discoverySource());
  }

  @Test
  void unresolvedDiscoveryPlaceholderWaitsForAResolvedEndpoint() throws Exception {
    servers.add(3, "pending", null);
    servers.resetPings(3);
    assertFalse(pings.updateVisible(3));
    assertTrue(sent.isEmpty());
    row(3, 0, peer(27960), -1, 1);
    assertTrue(pings.updateVisible(3));
    assertEquals(1, sent.size());
  }

  @Test
  void globalOverflowReplacementPreservesVisibilityAndDoesNotSendImmediately() throws Exception {
    row(2, 0, peer(27960), 0, 7);
    var original = new ArrayList<>(List.of(peer(27961), peer(27962)));
    pings.setGlobalOverflow(original);
    original.clear();
    assertFalse(pings.updateVisible(2));
    var entry = servers.entry(2, 0).orElseThrow();
    assertEquals(peer(27962), entry.address());
    assertEquals(-1, entry.ping());
    assertEquals(7, entry.visible());
    assertEquals("", entry.hostName());
    assertTrue(sent.isEmpty());
    assertEquals(List.of(peer(27961)), pings.globalOverflow());
    assertThrows(UnsupportedOperationException.class, () -> pings.globalOverflow().clear());
    assertTrue(pings.updateVisible(2));
    assertEquals(peer(27962), sent.getFirst().address());
  }

  @Test
  void localDiscoveryAddsBlankRecordsAndRetainsVisibilityUntilExplicitPing() throws Exception {
    servers.markVisible(0, -1, 7);
    assertTrue(pings.receiveInfo(peer(27960), info("\\hostname\\unsolicited")));
    assertEquals(1, servers.count(0));
    assertEquals("", servers.entry(0, 0).orElseThrow().hostName());
    assertEquals(-1, servers.ping(0, 0));
    assertEquals(7, servers.visible(0, 0));
    assertFalse(pings.receiveInfo(peer(27960), info("")));
    pings.discoverySource(2);
    assertFalse(pings.receiveInfo(peer(27961), info("")));
    assertEquals(1, servers.count(0));
  }

  @Test
  void nativeInfoByteRulesPreserveBoundedUnusualValuesAndFirstDuplicate() throws Exception {
    var address = peer(27960);
    pings.request(address);
    now++;
    byte[] response =
        "\\PROTOCOL\\071x\\protocol\\69\\hostname\\a%b\u00c8\n;\"\\NETTYPE\\9\\nettype\\8"
            .getBytes(StandardCharsets.ISO_8859_1);
    assertTrue(pings.receiveInfo(address, response));
    assertEquals(
        "\\nettype\\1\\PROTOCOL\\071x\\protocol\\69\\hostname\\a.b.\n;\"\\nettype\\8",
        pings.info(0));
    pings.clear(0);
    pings.request(address);
    now++;
    String maximum = "\\protocol\\71\\x\\" + "a".repeat(1200);
    assertTrue(pings.receiveInfo(address, maximum.getBytes(StandardCharsets.ISO_8859_1)));
    assertEquals(maximum.substring(0, 1023), pings.info(0));
    int count = pings.count();
    assertThrows(IllegalArgumentException.class, () -> pings.receiveInfo(address, new byte[16385]));
    assertThrows(
        IllegalArgumentException.class,
        () -> pings.request(InetSocketAddress.createUnresolved("invalid.example", 27960)));
    assertEquals(count, pings.count());
  }
}

package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class LanServerListTest {
  private static InetSocketAddress ip(int last) throws Exception {
    return new InetSocketAddress(
        InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) last}), 27960);
  }

  private static LanServerList.Entry sample(InetSocketAddress address, String host, int ping) {
    return new LanServerList.Entry(
        address, host, "q3dm1", "baseq3", 1, 4, 3, 8, 10, 200, ping, 3, 1, 2, 1);
  }

  @Test
  void mplayerAliasesGlobalWithBoundedPendingDiscoveryState() throws Exception {
    var list = new LanServerList();
    assertEquals(1, list.add(1, "First", ip(1)));
    assertEquals(1, list.count(2));
    assertEquals("127.0.0.1:27960", list.address(2, 0));
    list.setCount(2, -1);
    assertEquals(-1, list.count(1));
    assertEquals(-1, list.add(1, "Pending", ip(2)));
    list.remove(1, ip(1));
    assertEquals(-1, list.count(2));
    assertEquals("127.0.0.1:27960", list.address(1, 0));
    assertThrows(IllegalArgumentException.class, () -> list.setCount(0, -1));
    assertThrows(IllegalArgumentException.class, () -> list.setCount(2, 4097));
    assertThrows(IllegalArgumentException.class, () -> list.setCount(3, 129));
    assertThrows(IllegalArgumentException.class, () -> list.setCount(4, 0));
  }

  @Test
  void gettersAndVisibilityUseCapacityRatherThanCount() throws Exception {
    var list = new LanServerList();
    list.updateEntry(0, 127, sample(ip(1), "retained", 923));
    assertEquals(0, list.count(0));
    assertEquals(923, list.ping(0, 127));
    assertEquals(3, list.visible(0, 127));
    assertTrue(list.info(0, 127).endsWith("\\hostname\\retained"));
    list.markVisible(0, -1, -7);
    assertEquals(-7, list.visible(0, 0));
    assertEquals(-7, list.visible(0, 127));
    assertEquals(0, list.visible(3, 127));
    list.markVisible(0, 128, 1);
    assertEquals(0, list.visible(0, 128));
  }

  @Test
  void invalidSourcesAndSlotsReturnNativeEmptyResults() {
    var list = new LanServerList();
    for (int source : new int[] {-1, 4}) {
      assertEquals(0, list.count(source));
      assertEquals(0, list.capacity(source));
      assertEquals("", list.address(source, 0));
      assertEquals("", list.info(source, 0));
      assertEquals(-1, list.ping(source, 0));
      assertEquals(0, list.visible(source, 0));
      assertEquals(-1, list.add(source, "ignored", null));
      list.markVisible(source, -1, 1);
      list.resetPings(source);
      list.remove(source, null);
    }
    assertTrue(list.entry(0, -1).isEmpty());
    assertTrue(list.entry(0, 128).isEmpty());
    assertTrue(list.entry(2, 4095).isPresent());
    assertTrue(list.entry(2, 4096).isEmpty());
    assertEquals(0, list.compare(0, 0, 0, -1, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> list.updateEntry(2, 4096, LanServerList.Entry.empty()));
  }

  @Test
  void resettingPingsTouchesUnusedSlotsAndPreservesOtherMetadata() throws Exception {
    var list = new LanServerList();
    list.updateEntry(2, 4095, sample(ip(1), "tail", 987));
    list.resetPings(1);
    assertEquals(-1, list.ping(2, 0));
    assertEquals(-1, list.ping(2, 4095));
    assertEquals(3, list.visible(2, 4095));
    assertEquals("tail", list.entry(1, 4095).orElseThrow().hostName());
    assertEquals(0, list.ping(0, 0));
  }

  @Test
  void additionKeepsRetainedMetadataAndFullListRejectsEvenDuplicates() throws Exception {
    var list = new LanServerList();
    list.updateEntry(3, 0, sample(ip(1), "old", 432));
    assertEquals(1, list.add(3, "new", ip(2)));
    assertEquals(432, list.ping(3, 0));
    assertEquals(1, list.visible(3, 0));
    assertEquals("q3dm1", list.entry(3, 0).orElseThrow().mapName());
    assertEquals(0, list.add(3, "changed", ip(2)));
    assertEquals("new", list.entry(3, 0).orElseThrow().hostName());
    list.setCount(3, 128);
    assertEquals(-1, list.add(3, "same", ip(2)));
  }

  @Test
  void removalCopiesWholeRecordsAndLeavesTheLastSlotForReuse() throws Exception {
    var list = new LanServerList();
    list.updateEntry(3, 0, sample(ip(1), "A", 100));
    list.updateEntry(3, 1, sample(ip(2), "B", 200));
    list.setCount(3, 2);
    list.remove(3, ip(1));
    assertEquals(1, list.count(3));
    assertEquals(list.entry(3, 0), list.entry(3, 1));
    assertEquals(1, list.add(3, "C", ip(3)));
    assertEquals(200, list.ping(3, 1));
    assertEquals("C", list.entry(3, 1).orElseThrow().hostName());
  }

  @Test
  void failedResolutionPlaceholdersAreInertAndDoNotDeduplicate() {
    var list = new LanServerList();
    assertEquals(1, list.add(3, "Bad", null));
    assertEquals(1, list.add(3, "Bad2", null));
    assertEquals(2, list.count(3));
    assertEquals("", list.address(3, 0));
    assertTrue(list.info(3, 0).endsWith("\\hostname\\Bad"));
    list.remove(3, null);
    assertEquals(2, list.count(3));
  }

  @Test
  void hostIdentitiesSurviveMetadataUpdatesAndFollowStructuralChanges() throws Exception {
    var list = new LanServerList();
    assertEquals(0, list.identity(-1, 0));
    assertEquals(0, list.identity(2, 4096));
    assertEquals(0, list.identity(3, 0));
    list.add(3, "pending", null);
    long pending = list.identity(3, 0);
    assertTrue(pending > 0);
    list.markVisible(3, -1, -9);
    list.resetPings(3);
    list.updateEntry(3, 0, list.entry(3, 0).orElseThrow().withPing(123));
    assertEquals(pending, list.identity(3, 0));
    list.updateEntry(3, 0, sample(null, "renamed", 5));
    assertNotEquals(pending, list.identity(3, 0));
    long renamed = list.identity(3, 0);
    list.updateEntry(3, 0, sample(ip(1), "resolved", 10));
    long resolved = list.identity(3, 0);
    assertNotEquals(renamed, resolved);
    list.updateEntry(3, 0, sample(ip(1), "server reply", 20));
    assertEquals(resolved, list.identity(3, 0));
    list.add(3, "second", ip(2));
    long second = list.identity(3, 1);
    list.remove(3, ip(1));
    assertEquals(second, list.identity(3, 0));
    assertEquals(0, list.identity(3, 1));
    list.add(3, "new", ip(3));
    assertTrue(list.identity(3, 1) > second);
    list.updateEntry(2, 17, sample(ip(1), "global", 0));
    assertEquals(list.identity(2, 17), list.identity(1, 17));
  }

  @Test
  void textSortingRetainsColorsAndUsesSignedNativeBytes() throws Exception {
    var list = new LanServerList();
    list.updateEntry(0, 0, sample(ip(1), "^1a", -1));
    list.updateEntry(0, 1, sample(ip(2), "b", 100));
    assertEquals(1, list.compare(0, 0, 0, 0, 1));
    assertEquals(-1, list.compare(0, 0, 2, 0, 1));
    assertEquals(-1, list.compare(0, 4, 0, 0, 1));
    assertEquals(0, list.compare(0, 2, 0, 0, 1));
    assertEquals(0, list.compare(0, 5, 0, 0, 1));
    list.updateEntry(0, 0, sample(ip(1), "\u0080", -1));
    assertEquals(-1, list.compare(0, 0, 0, 0, 1));
    list.updateEntry(0, 0, sample(ip(1), "B", -1));
    assertEquals(0, list.compare(0, 0, 0, 0, 1));
  }

  @Test
  void clientCountSortBreaksEqualCountsByMaximumClients() {
    var list = new LanServerList();
    list.updateEntry(
        0, 0, new LanServerList.Entry(null, "same", "", "", 0, 0, 3, 1, 0, 0, 0, 0, 0, 0, 0));
    list.updateEntry(
        0, 1, new LanServerList.Entry(null, "same", "", "", 0, 0, 3, 9, 0, 0, 0, 0, 0, 0, 0));
    assertEquals(-1, list.compare(0, 2, 0, 0, 1));
    assertEquals(1, list.compare(0, 2, 1, 0, 1));
  }

  @Test
  void infoUsesNativeFieldOrderAndOmitsOnlyRejectedStringFields() throws Exception {
    var list = new LanServerList();
    list.updateEntry(0, 0, sample(ip(1), "Host", 50));
    assertEquals(
        "\\g_humanplayers\\2\\g_needpass\\1\\punkbuster\\1\\addr\\127.0.0.1:27960"
            + "\\nettype\\1\\gametype\\4\\game\\baseq3\\maxping\\200\\minping\\10\\ping\\50"
            + "\\sv_maxclients\\8\\clients\\3\\mapname\\q3dm1\\hostname\\Host",
        list.info(0, 0));
    list.updateEntry(0, 0, sample(ip(1), "a;b", 50));
    assertFalse(list.info(0, 0).contains("hostname"));
    assertEquals("a;b", list.entry(0, 0).orElseThrow().hostName());
    list.updateEntry(0, 0, sample(ip(1), "a\nb", 50));
    assertTrue(list.info(0, 0).endsWith("\\hostname\\a\nb"));
    assertTrue(list.info(0, 127).contains("\\clients\\0"));
  }

  @Test
  void entriesAreImmutableBoundedByteStringsAndRejectUnresolvedPeers() throws Exception {
    var list = new LanServerList();
    var snapshot = list.entry(0, 0).orElseThrow();
    list.add(0, "x".repeat(100), ip(1));
    assertEquals("", snapshot.hostName());
    assertEquals(79, list.entry(0, 0).orElseThrow().hostName().length());
    assertThrows(
        IllegalArgumentException.class,
        () -> list.add(0, "host", InetSocketAddress.createUnresolved("example.invalid", 27960)));
    assertThrows(IllegalArgumentException.class, () -> sample(null, "\u0100", 0));
    assertThrows(IllegalArgumentException.class, () -> sample(null, "a\0b", 0));
  }

  @Test
  void formatsObservedIpv6CompressionAndMappedFamilies() throws Exception {
    byte[] ordinary = {0x20, 1, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1};
    var address = new InetSocketAddress(Inet6Address.getByAddress(null, ordinary, 0), 1234);
    assertEquals("[2001:db8::1:0:0:1]:1234", LanServerList.formatAddress(address));
    byte[] mapped = new byte[16];
    mapped[10] = mapped[11] = -1;
    mapped[12] = (byte) 192;
    mapped[14] = 2;
    mapped[15] = 1;
    assertEquals(
        "[::ffff:192.0.2.1]:27960",
        LanServerList.formatAddress(
            new InetSocketAddress(Inet6Address.getByAddress(null, mapped, 0), 27960)));
    mapped[10] = mapped[11] = 0;
    assertEquals(
        "[::192.0.2.1]:27960",
        LanServerList.formatAddress(
            new InetSocketAddress(Inet6Address.getByAddress(null, mapped, 0), 27960)));
  }
}

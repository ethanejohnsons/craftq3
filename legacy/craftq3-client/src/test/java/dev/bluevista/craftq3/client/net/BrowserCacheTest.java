package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class BrowserCacheTest {
  private static InetSocketAddress v4() throws Exception {
    return new InetSocketAddress(InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 7}), 27960);
  }

  private static InetSocketAddress v6(int scope) throws Exception {
    byte[] mapped = HexFormat.of().parseHex("00000000000000000000ffffc0000207");
    return new InetSocketAddress(Inet6Address.getByAddress(null, mapped, scope), 65535);
  }

  private static LanServerList.Entry record(InetSocketAddress address) {
    return new LanServerList.Entry(
        address,
        "H",
        "M",
        "G",
        Integer.MIN_VALUE,
        Integer.MAX_VALUE,
        -1,
        0,
        1,
        2,
        -1,
        -17,
        99,
        123,
        -5);
  }

  private static LanServerList list(LanServerList.Entry... entries) {
    LanServerList list = new LanServerList();
    for (int i = 0; i < entries.length; i++) list.updateEntry(LanServerList.GLOBAL, i, entries[i]);
    list.setCount(LanServerList.GLOBAL, entries.length);
    return list;
  }

  private static byte[] checksum(byte[] bytes) {
    CRC32 crc = new CRC32();
    crc.update(bytes, 0, bytes.length - 4);
    ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) crc.getValue());
    return bytes;
  }

  @Test
  void emptyCacheHasExplicitPortableHeaderAndVersion() {
    byte[] bytes = BrowserCache.encode(new LanServerList());
    assertEquals(16, bytes.length);
    assertEquals("435142430001000000000000", HexFormat.of().formatHex(bytes, 0, 12));
    assertEquals(new BrowserCache.Snapshot(List.of(), List.of()), BrowserCache.decode(bytes));
    assertEquals("craftq3-browser-v1.bin", BrowserCache.FILE_NAME);
  }

  @Test
  void preservesRawMetadataOrderDuplicateRecordsAndAddressFamilyScope() throws Exception {
    var first = record(v4());
    var second =
        new LanServerList.Entry(
            v6(987654),
            "^1\u00ff\n;\\\"",
            "m\t",
            "g",
            9,
            8,
            7,
            6,
            5,
            4,
            -1,
            Integer.MIN_VALUE,
            3,
            2,
            1);
    LanServerList source = list(first, second, first);
    source.updateEntry(
        LanServerList.FAVORITES, 0, record(new InetSocketAddress(v4().getAddress(), 0)));
    source.setCount(LanServerList.FAVORITES, 1);
    BrowserCache.Snapshot decoded = BrowserCache.decode(BrowserCache.encode(source));
    assertEquals(List.of(first, second, first), decoded.global());
    assertEquals(1, decoded.favorites().size());
    assertEquals(0, decoded.favorites().getFirst().address().getPort());
    var address = decoded.global().get(1).address();
    assertInstanceOf(Inet6Address.class, address.getAddress());
    assertArrayEquals(
        second.address().getAddress().getAddress(), address.getAddress().getAddress());
    assertEquals(987654, ((Inet6Address) address.getAddress()).getScopeId());
    assertEquals(65535, address.getPort());
    assertEquals(Integer.MIN_VALUE, decoded.global().get(1).visible());
    assertEquals(-1, decoded.global().get(1).ping());
  }

  @Test
  void excludesLocalInactiveAndNullRecordsWhilePreservingResolvedPendingPings() throws Exception {
    LanServerList source =
        list(LanServerList.Entry.empty(), record(v4()), LanServerList.Entry.empty());
    source.updateEntry(LanServerList.GLOBAL, 4095, record(v6(0)));
    source.add(LanServerList.LOCAL, "local", v4());
    source.add(LanServerList.FAVORITES, "unresolved", null);
    var decoded = BrowserCache.decode(BrowserCache.encode(source));
    assertEquals(List.of(record(v4())), decoded.global());
    assertEquals(-1, decoded.global().getFirst().ping());
    assertTrue(decoded.favorites().isEmpty());
    source.setCount(LanServerList.GLOBAL, -1);
    assertTrue(BrowserCache.decode(BrowserCache.encode(source)).global().isEmpty());
  }

  @Test
  void restoreReplacesBothListsClearsTheirTailsAndLeavesLocalDiscoveryAlone() throws Exception {
    LanServerList target = list(record(v4()), record(v6(1)));
    target.add(LanServerList.FAVORITES, "old", v4());
    target.updateEntry(LanServerList.GLOBAL, 4095, record(v4()));
    target.updateEntry(LanServerList.FAVORITES, 127, record(v6(2)));
    target.updateEntry(LanServerList.LOCAL, 127, record(v4()));
    target.setCount(LanServerList.LOCAL, 3);
    LanServerList source = list(record(v6(3)));
    BrowserCache.restore(target, BrowserCache.encode(source));
    assertEquals(1, target.count(LanServerList.MPLAYER));
    assertEquals(record(v6(3)), target.entry(LanServerList.GLOBAL, 0).orElseThrow());
    assertEquals(0, target.count(LanServerList.FAVORITES));
    assertEquals(LanServerList.Entry.empty(), target.entry(LanServerList.GLOBAL, 1).orElseThrow());
    assertEquals(
        LanServerList.Entry.empty(), target.entry(LanServerList.GLOBAL, 4095).orElseThrow());
    assertEquals(
        LanServerList.Entry.empty(), target.entry(LanServerList.FAVORITES, 127).orElseThrow());
    assertEquals(3, target.count(LanServerList.LOCAL));
    assertEquals(record(v4()), target.entry(LanServerList.LOCAL, 127).orElseThrow());
  }

  @Test
  void maximumCountsAndTextLengthsFitThePublishedByteBound() throws Exception {
    var maximum =
        new LanServerList.Entry(
            v6(Integer.MAX_VALUE),
            "H".repeat(79),
            "M".repeat(31),
            "G".repeat(31),
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            9,
            10,
            11);
    LanServerList source = new LanServerList();
    for (int category : new int[] {LanServerList.GLOBAL, LanServerList.FAVORITES}) {
      for (int i = 0; i < source.capacity(category); i++) source.updateEntry(category, i, maximum);
      source.setCount(category, source.capacity(category));
    }
    byte[] bytes = BrowserCache.encode(source);
    assertEquals(BrowserCache.MAX_BYTES, bytes.length);
    var decoded = BrowserCache.decode(bytes);
    assertEquals(4096, decoded.global().size());
    assertEquals(128, decoded.favorites().size());
    assertEquals(maximum, decoded.global().getLast());
    assertEquals(
        Integer.MAX_VALUE,
        ((Inet6Address) decoded.favorites().getLast().address().getAddress()).getScopeId());
    assertThrows(
        IllegalArgumentException.class,
        () -> BrowserCache.decode(new byte[BrowserCache.MAX_BYTES + 1]));
  }

  @Test
  void everyTruncationAndTrailingPayloadIsRejectedWithoutAnyPartialRestore() throws Exception {
    LanServerList source = list(record(v4()));
    source.updateEntry(LanServerList.FAVORITES, 0, record(v6(6)));
    source.setCount(LanServerList.FAVORITES, 1);
    byte[] valid = BrowserCache.encode(source);
    LanServerList target = list(record(v6(9)));
    target.add(LanServerList.FAVORITES, "keep", v4());
    target.updateEntry(LanServerList.GLOBAL, 4095, record(v4()));
    byte[] before = BrowserCache.encode(target);
    for (int length = 0; length < valid.length; length++) {
      byte[] truncated = Arrays.copyOf(valid, length);
      assertThrows(IllegalArgumentException.class, () -> BrowserCache.restore(target, truncated));
      if (length >= 16) {
        checksum(truncated);
        assertThrows(IllegalArgumentException.class, () -> BrowserCache.restore(target, truncated));
      }
      assertArrayEquals(before, BrowserCache.encode(target));
      assertEquals(record(v4()), target.entry(LanServerList.GLOBAL, 4095).orElseThrow());
    }
    byte[] extra = Arrays.copyOf(valid, valid.length + 1);
    checksum(extra);
    assertThrows(IllegalArgumentException.class, () -> BrowserCache.restore(target, extra));
    assertArrayEquals(before, BrowserCache.encode(target));
  }

  @Test
  void rejectsUnsupportedHeaderCountsFamiliesScopesAndMalformedText() throws Exception {
    byte[] valid = BrowserCache.encode(list(record(v4())));
    List<byte[]> malformed = new ArrayList<>();
    byte[] badCrc = valid.clone();
    badCrc[badCrc.length - 1] ^= 1;
    malformed.add(badCrc);
    for (int[] edit :
        new int[][] {
          {0, 0},
          {5, 2},
          {7, 1},
          {8, 0x10},
          {9, 2},
          {9, 0},
          {10, 1},
          {11, 129},
          {12, 5},
          {17, 0xff},
          {20, 1},
          {23, 80},
          {24, 0},
          {25, 32},
          {27, 32}
        }) {
      byte[] changed = valid.clone();
      changed[edit[0]] = (byte) edit[1];
      malformed.add(checksum(changed));
    }
    byte[] negativeScope = BrowserCache.encode(list(record(v6(1))));
    negativeScope[29] = (byte) 0x80;
    malformed.add(checksum(negativeScope));
    LanServerList destination = list(record(v6(7)));
    destination.add(LanServerList.FAVORITES, "held", v4());
    byte[] original = BrowserCache.encode(destination);
    for (byte[] bytes : malformed) {
      assertThrows(IllegalArgumentException.class, () -> BrowserCache.restore(destination, bytes));
      assertArrayEquals(original, BrowserCache.encode(destination));
    }
  }

  @Test
  void decodedSnapshotsOwnTheirRecordsAndValidateBeforeApplication() throws Exception {
    LanServerList source = list(record(v4()));
    byte[] bytes = BrowserCache.encode(source);
    BrowserCache.Snapshot decoded = BrowserCache.decode(bytes);
    Arrays.fill(bytes, (byte) 0);
    source.updateEntry(LanServerList.GLOBAL, 0, record(v6(4)));
    assertEquals(record(v4()), decoded.global().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> decoded.global().clear());
    var input = new ArrayList<>(List.of(record(v4())));
    var snapshot = new BrowserCache.Snapshot(input, List.of());
    input.clear();
    assertEquals(1, snapshot.global().size());
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrowserCache.Snapshot(List.of(LanServerList.Entry.empty()), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrowserCache.Snapshot(Collections.nCopies(4097, record(v4())), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BrowserCache.Snapshot(List.of(), Collections.nCopies(129, record(v4()))));
  }
}

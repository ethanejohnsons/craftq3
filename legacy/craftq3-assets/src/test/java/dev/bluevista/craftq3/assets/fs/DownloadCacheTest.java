package dev.bluevista.craftq3.assets.fs;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadCacheTest {
  @TempDir Path root;

  private byte[] zip(String name, String text) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new ZipOutputStream(bytes)) {
      var entry = new ZipEntry(name);
      byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      var crc = new CRC32();
      crc.update(data);
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(data.length);
      entry.setCrc(crc.getValue());
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();
    }
    return bytes.toByteArray();
  }

  private int checksum(String text) {
    var crc = new CRC32();
    crc.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return Pk3Checksums.normal(new int[] {(int) crc.getValue()});
  }

  private DownloadCache.Entry add(DownloadCache cache, String text) throws IOException {
    try (var pending =
        cache.begin(new DownloadCache.Request("baseq3/custom.pk3", checksum(text)))) {
      pending.write(zip("marker", text));
      return pending.verifyAndCommit();
    }
  }

  @Test
  void commitsReopensAndMountsWithoutChangingInstallation() throws Exception {
    Path games = Files.createDirectories(root.resolve("games/baseq3")).getParent();
    Path original = games.resolve("baseq3/pak0.pk3");
    byte[] bytes = zip("marker", "original");
    Files.write(original, bytes);
    try (var cache = new DownloadCache(root.resolve("cache"))) {
      add(cache, "downloaded");
      assertEquals(1, cache.entries().size());
    }
    try (var cache = new DownloadCache(root.resolve("cache"));
        var fs = Pk3FileSystem.mount(games, null)) {
      cache.mountCached(fs);
      assertEquals("downloaded", fs.readText(new VirtualPath("marker")));
      assertEquals("custom", fs.packs().getFirst().name());
      assertArrayEquals(bytes, Files.readAllBytes(original));
      try (var files = Files.list(games.resolve("baseq3"))) {
        assertEquals(1, files.count());
      }
    }
  }

  @Test
  void rejectsWrongChecksumAndCleansStaging() throws Exception {
    try (var cache = new DownloadCache(root.resolve("cache"));
        var pending = cache.begin(new DownloadCache.Request("baseq3/custom.pk3", 123))) {
      pending.write(zip("marker", "data"));
      assertThrows(IOException.class, pending::verifyAndCommit);
      assertTrue(cache.entries().isEmpty());
      try (var files = Files.list(root.resolve("cache"))) {
        assertEquals(1, files.count());
      }
    }
  }

  @Test
  void rejectsCorruptedMemberDespiteUnchangedCentralChecksum() throws Exception {
    byte[] bytes = zip("marker", "data");
    bytes[30 + 6] ^= 1;
    try (var cache = new DownloadCache(root.resolve("cache"));
        var pending =
            cache.begin(new DownloadCache.Request("baseq3/custom.pk3", checksum("data")))) {
      pending.write(bytes);
      assertThrows(IOException.class, pending::verifyAndCommit);
      assertTrue(cache.entries().isEmpty());
    }
  }

  @Test
  void rejectsTraversalMember() throws Exception {
    try (var cache = new DownloadCache(root.resolve("cache"));
        var pending =
            cache.begin(new DownloadCache.Request("baseq3/custom.pk3", checksum("data")))) {
      pending.write(zip("../outside", "data"));
      assertThrows(IOException.class, pending::verifyAndCommit);
      assertFalse(Files.exists(root.resolve("outside")));
    }
  }

  @Test
  void rejectsCaseAmbiguousMembers() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (String name : new String[] {"marker", "MARKER"}) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(1);
        zip.closeEntry();
      }
    }
    try (var cache = new DownloadCache(root.resolve("cache"));
        var pending = cache.begin(new DownloadCache.Request("baseq3/custom.pk3", 0))) {
      pending.write(bytes.toByteArray());
      assertThrows(IOException.class, pending::verifyAndCommit);
    }
  }

  @Test
  void cancellationPreservesCommittedPack() throws Exception {
    try (var cache = new DownloadCache(root.resolve("cache"))) {
      var good = add(cache, "good");
      byte[] before = Files.readAllBytes(good.path());
      var pending = cache.begin(new DownloadCache.Request("baseq3/custom.pk3", checksum("next")));
      pending.write(zip("marker", "next"));
      pending.close();
      assertThrows(IOException.class, pending::verifyAndCommit);
      assertEquals(1, cache.entries().size());
      assertArrayEquals(before, Files.readAllBytes(good.path()));
      assertThrows(IOException.class, () -> cache.begin(good.request()));
    }
  }

  @Test
  void reactivatesOlderVersionWithoutDuplicateVisiblePack() throws Exception {
    Path games = Files.createDirectories(root.resolve("games/baseq3")).getParent();
    try (var cache = new DownloadCache(root.resolve("cache"));
        var fs = Pk3FileSystem.mount(games, null)) {
      var a = add(cache, "first");
      var b = add(cache, "second");
      fs.attachArchive(a.path(), "baseq3", "custom", a.request().checksum());
      fs.attachArchive(b.path(), "baseq3", "custom", b.request().checksum());
      assertEquals("second", fs.readText(new VirtualPath("marker")));
      assertEquals(1, fs.packs().size());
      fs.attachArchive(a.path(), "baseq3", "custom", a.request().checksum());
      assertEquals("first", fs.readText(new VirtualPath("marker")));
      assertEquals(1, fs.packs().size());
    }
  }

  @Test
  void rejectsUnsafeAndOfficialRequests() {
    for (String name :
        new String[] {
          "../x.pk3",
          "baseq3/pak0.pk3",
          "missionpack/pak3.pk3",
          "baseq3/a;b.pk3",
          "baseq3/x/y.pk3",
          "baseq3/a b.pk3"
        }) assertThrows(IllegalArgumentException.class, () -> new DownloadCache.Request(name, 0));
  }

  @Test
  void rejectsSymlinkCacheAndMembers() throws Exception {
    Path actual = Files.createDirectory(root.resolve("actual"));
    Path link = Files.createSymbolicLink(root.resolve("link"), actual);
    assertThrows(IOException.class, () -> new DownloadCache(link));
    Files.createSymbolicLink(actual.resolve("bad.pk3"), root.resolve("outside"));
    assertThrows(IOException.class, () -> new DownloadCache(actual));
  }

  @Test
  void exclusiveOwnershipAndMalformedIdentity() throws Exception {
    Path path = root.resolve("cache");
    try (var cache = new DownloadCache(path)) {
      assertThrows(
          java.nio.channels.OverlappingFileLockException.class, () -> new DownloadCache(path));
      assertTrue(cache.entries().isEmpty());
    }
    Files.writeString(path.resolve("bad.pk3"), "bad");
    try (var cache = new DownloadCache(path)) {
      assertThrows(IOException.class, cache::entries);
    }
  }

  @Test
  void enforcesFileQuota() throws Exception {
    Path path = Files.createDirectory(root.resolve("cache"));
    for (int i = 0; i < 128; i++)
      Files.createFile(path.resolve(".craftq3-download-" + Integer.toHexString(i) + ".tmp"));
    try (var cache = new DownloadCache(path)) {
      assertThrows(
          IOException.class, () -> cache.begin(new DownloadCache.Request("baseq3/custom.pk3", 0)));
    }
  }

  @Test
  void verifierHonorsCancellation() throws Exception {
    Path path = Files.write(root.resolve("test.pk3"), zip("marker", "data"));
    assertThrows(IOException.class, () -> Pk3Verifier.verify(path, checksum("data"), () -> true));
  }
}

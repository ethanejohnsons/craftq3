package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DownloadCacheTest {
  @TempDir Path root;

  private static byte[] zip(String name, String text) {
    return TestZip.create().stored().add(name, text).bytes();
  }

  private static int checksum(String text) {
    var crc = new CRC32();
    crc.update(text.getBytes(StandardCharsets.ISO_8859_1));
    return Pk3Checksums.normal(new int[] {(int) crc.getValue()});
  }

  private static DownloadCache.Entry add(DownloadCache cache, String name, String text)
      throws IOException {
    try (var pending = cache.begin(new DownloadCache.Request(name, checksum(text)))) {
      pending.write(zip("marker", text));
      return pending.verifyAndCommit();
    }
  }

  @Test
  void commitsReopensAndMountsWithoutChangingInstallation() throws Exception {
    Path games = root.resolve("games");
    Path original = games.resolve("baseq3/pak0.pk3");
    TestZip.create().add("marker", "original").write(original);
    byte[] bytes = Files.readAllBytes(original);
    try (var cache = DownloadCache.open(root.resolve("cache"))) {
      // Named to sort after pak0, as the original engine orders packs by name.
      add(cache, "baseq3/zzcustom.pk3", "downloaded");
      assertEquals(1, cache.entries().size());
    }
    try (var cache = DownloadCache.open(root.resolve("cache"))) {
      var builder = SearchPath.builder(games);
      cache.entries().forEach(builder::cachedArchive);
      try (var fs = builder.build()) {
        assertEquals("downloaded", fs.readText(VirtualPath.of("marker")));
        assertEquals("zzcustom", fs.packs().getFirst().name());
      }
      assertArrayEquals(bytes, Files.readAllBytes(original));
      try (var files = Files.list(games.resolve("baseq3"))) {
        assertEquals(1, files.count());
      }
    }
  }

  @Test
  void rejectsWrongChecksumAndCleansStaging() throws Exception {
    try (var cache = DownloadCache.open(root.resolve("cache"));
        var pending = cache.begin(new DownloadCache.Request("baseq3/custom.pk3", 123))) {
      pending.write(zip("marker", "data"));
      assertThrows(IOException.class, pending::verifyAndCommit);
      assertTrue(cache.entries().isEmpty());
      try (var files = Files.list(root.resolve("cache"))) {
        assertEquals(1, files.count(), "only the lock file remains");
      }
    }
  }

  @Test
  void rejectsCorruptedMemberDespiteUnchangedCentralChecksum() throws Exception {
    byte[] bytes = zip("marker", "data");
    bytes[30 + 6] ^= 1;
    try (var cache = DownloadCache.open(root.resolve("cache"));
        var pending =
            cache.begin(new DownloadCache.Request("baseq3/custom.pk3", checksum("data")))) {
      pending.write(bytes);
      assertThrows(IOException.class, pending::verifyAndCommit);
      assertTrue(cache.entries().isEmpty());
    }
  }

  @Test
  void rejectsTraversalAndCaseAmbiguousMembersThatAnInstallWouldMerelySkip() throws Exception {
    try (var cache = DownloadCache.open(root.resolve("cache"))) {
      try (var pending =
          cache.begin(new DownloadCache.Request("baseq3/custom.pk3", checksum("data")))) {
        pending.write(zip("../outside", "data"));
        assertThrows(IOException.class, pending::verifyAndCommit);
      }
      assertFalse(Files.exists(root.resolve("outside")));
      var bytes = new ByteArrayOutputStream();
      try (var zip = new ZipOutputStream(bytes)) {
        for (String name : new String[] {"marker", "MARKER"}) {
          zip.putNextEntry(new ZipEntry(name));
          zip.write(1);
          zip.closeEntry();
        }
      }
      try (var pending = cache.begin(new DownloadCache.Request("baseq3/custom.pk3", 0))) {
        pending.write(bytes.toByteArray());
        assertThrows(IOException.class, pending::verifyAndCommit);
      }
      assertTrue(cache.entries().isEmpty());
    }
  }

  @Test
  void cancellationPreservesCommittedPack() throws Exception {
    try (var cache = DownloadCache.open(root.resolve("cache"))) {
      var good = add(cache, "baseq3/custom.pk3", "good");
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
  void laterCachedVersionOfTheSameNameReplacesTheEarlierOne() throws Exception {
    Path games = Files.createDirectories(root.resolve("games/baseq3")).getParent();
    try (var cache = DownloadCache.open(root.resolve("cache"))) {
      var a = add(cache, "baseq3/custom.pk3", "first");
      var b = add(cache, "baseq3/custom.pk3", "second");
      try (var fs = SearchPath.builder(games).cachedArchive(a).cachedArchive(b).build()) {
        assertEquals("second", fs.readText(VirtualPath.of("marker")));
        assertEquals(1, fs.packs().size());
      }
      try (var fs = SearchPath.builder(games).cachedArchive(b).cachedArchive(a).build()) {
        assertEquals("first", fs.readText(VirtualPath.of("marker")));
        assertEquals(1, fs.packs().size());
      }
    }
  }

  @Test
  void rejectsUnsafeAndOfficialRequests() {
    for (String name :
        List.of(
            "../x.pk3",
            "baseq3/pak0.pk3",
            "BASEQ3/PAK8.pk3",
            "missionpack/pak3.pk3",
            "baseq3/a;b.pk3",
            "baseq3/x/y.pk3",
            "baseq3/a b.pk3",
            "baseq3/.pk3",
            "baseq3/readme.txt"))
      assertThrows(IllegalArgumentException.class, () -> new DownloadCache.Request(name, 0), name);
    assertEquals("baseq3/pak9.pk3", new DownloadCache.Request("BaseQ3/Pak9.pk3", 0).remoteName());
  }

  @Test
  void rejectsSymlinkCacheAndLinkedMembers() throws Exception {
    Path actual = Files.createDirectory(root.resolve("actual"));
    if (!Links.create(root.resolve("link"), actual)) return;
    assertThrows(IOException.class, () -> DownloadCache.open(root.resolve("link")));
    Files.createSymbolicLink(actual.resolve("bad.pk3"), root.resolve("outside"));
    assertThrows(IOException.class, () -> DownloadCache.open(actual));
  }

  @Test
  void exclusiveOwnershipAndMalformedIdentity() throws Exception {
    Path path = root.resolve("cache");
    try (var cache = DownloadCache.open(path)) {
      assertThrows(
          java.nio.channels.OverlappingFileLockException.class, () -> DownloadCache.open(path));
      assertTrue(cache.entries().isEmpty());
    }
    Files.writeString(path.resolve("bad.pk3"), "bad");
    try (var cache = DownloadCache.open(path)) {
      assertThrows(IOException.class, cache::entries);
    }
    Files.delete(path.resolve("bad.pk3"));
    Files.writeString(path.resolve("notes.txt"), "unexpected");
    assertThrows(IOException.class, () -> DownloadCache.open(path));
  }

  @Test
  void enforcesFileQuota() throws Exception {
    Path path = Files.createDirectory(root.resolve("cache"));
    for (int i = 0; i < DownloadCache.MAX_FILES; i++)
      Files.createFile(path.resolve(".craftq3-download-" + Integer.toHexString(i) + ".tmp"));
    try (var cache = DownloadCache.open(path)) {
      assertThrows(
          IOException.class, () -> cache.begin(new DownloadCache.Request("baseq3/custom.pk3", 0)));
    }
  }

  @Test
  void verifierHonorsCancellationAndRejectsEmptyOrMalformedPacks() throws Exception {
    Path path = Files.write(root.resolve("test.pk3"), zip("marker", "data"));
    assertThrows(IOException.class, () -> Pk3Verifier.verify(path, checksum("data"), () -> true));
    Pk3Verifier.verify(path, checksum("data"), () -> false);
    Path empty = Files.write(root.resolve("empty.pk3"), TestZip.create().bytes());
    assertThrows(IOException.class, () -> Pk3Verifier.verify(empty, 0, () -> false));
    Path junk = Files.write(root.resolve("junk.pk3"), new byte[64]);
    assertThrows(IOException.class, () -> Pk3Verifier.verify(junk, 0, () -> false));
  }
}

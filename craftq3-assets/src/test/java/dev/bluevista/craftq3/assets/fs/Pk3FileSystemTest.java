package dev.bluevista.craftq3.assets.fs;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pk3FileSystemTest {
  @TempDir Path root;

  private void pak(String file, Map<String, String> contents) throws IOException {
    Path path = root.resolve(file);
    Files.createDirectories(path.getParent());
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      for (var entry : contents.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(StandardCharsets.ISO_8859_1));
        zip.closeEntry();
      }
    }
  }

  private void loose(String path, String text) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void largeMoviesStreamWithoutRaisingBufferedOrNonMovieLimits() throws Exception {
    Files.createDirectories(root.resolve("baseq3"));
    var path = root.resolve("baseq3/pak0.pk3");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.putNextEntry(new ZipEntry("video/large.roq"));
      byte[] block = new byte[1024 * 1024];
      for (int i = 0; i < 65; i++) zip.write(block);
      zip.closeEntry();
    }
    try (var fs = Pk3FileSystem.mount(root, null)) {
      var movie = new VirtualPath("video/large.roq");
      try (var stream = fs.open(movie)) {
        assertEquals(32, stream.readNBytes(32).length);
      }
      assertThrows(IOException.class, () -> fs.read(movie));
    }
    // Same-sized nonmovie entries still fail the mount's original 64 MiB limit.
    byte[] archive = Files.readAllBytes(path);
    byte[] old = "large.roq".getBytes(StandardCharsets.US_ASCII);
    byte[] replacement = "large.dat".getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i + old.length <= archive.length; i++) {
      boolean match = true;
      for (int j = 0; j < old.length; j++) match &= archive[i + j] == old[j];
      if (match) System.arraycopy(replacement, 0, archive, i, old.length);
    }
    Files.write(path, archive);
    assertThrows(IOException.class, () -> Pk3FileSystem.mount(root, null));
  }

  @Test
  void entryStreamsPreserveOverridesReferencesAndEarlyClose() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("video/test.roq", "base"));
    pak("baseq3/pak1.pk3", Map.of("video/test.roq", "override"));
    try (var fs = Pk3FileSystem.mount(root, null)) {
      try (var stream = fs.open(new VirtualPath("VIDEO/TEST.ROQ"))) {
        assertEquals('o', stream.read());
        assertEquals(2, stream.skip(2));
        assertEquals("rride", new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1));
        assertEquals(-1, stream.read());
      }
      assertEquals(1, fs.packs().stream().filter(pack -> pack.references() != 0).count());
      var stream = fs.open(new VirtualPath("video/test.roq"));
      stream.close();
      assertThrows(IOException.class, stream::read);
      assertThrows(IOException.class, () -> fs.open(new VirtualPath("missing.roq")));
    }
  }

  @Test
  void looseStreamsRejectGrowthTruncationAndSymlinkReplacement() throws Exception {
    for (boolean grow : new boolean[] {true, false}) {
      loose("baseq3/video/test.roq", "original");
      try (var fs = Pk3FileSystem.mount(root, null);
          var stream = fs.open(new VirtualPath("video/test.roq"))) {
        Files.writeString(
            root.resolve("baseq3/video/test.roq"), grow ? "original enlarged" : "short");
        assertThrows(IOException.class, stream::readAllBytes);
      }
    }
    loose("baseq3/video/test.roq", "original");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      var path = root.resolve("baseq3/video/test.roq");
      Files.delete(path);
      Files.writeString(root.resolve("outside"), "outside");
      Files.createSymbolicLink(path, root.resolve("outside"));
      assertThrows(IOException.class, () -> fs.open(new VirtualPath("video/test.roq")));
    }
  }

  @Test
  void entryStreamRejectsIncorrectArchiveCrcAtEof() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("video/test.roq", "original"));
    var path = root.resolve("baseq3/pak0.pk3");
    var bytes = Files.readAllBytes(path);
    boolean changed = false;
    for (int i = 0; i + 20 < bytes.length; i++)
      if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
        bytes[i + 16] ^= 1;
        changed = true;
        break;
      }
    assertTrue(changed);
    Files.write(path, bytes);
    try (var fs = Pk3FileSystem.mount(root, null);
        var stream = fs.open(new VirtualPath("video/test.roq"))) {
      assertThrows(IOException.class, stream::readAllBytes);
    }
  }

  @Test
  void hostCanReadShadowedMountedSourceWithoutChangingSearchOrder() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("maps/test.bsp", "original"));
    pak("baseq3/pak1.pk3", Map.of("maps/test.bsp", "replacement"));
    try (var fs = Pk3FileSystem.mount(root, "baseq3")) {
      var path = new VirtualPath("maps/test.bsp");
      var order = fs.searchOrder();
      var original =
          order.stream()
              .filter(source -> source.container().endsWith("pak0.pk3"))
              .findFirst()
              .orElseThrow();
      assertEquals(
          "original", new String(fs.readFrom(original, path), StandardCharsets.ISO_8859_1));
      assertEquals("replacement", fs.readText(path));
      assertEquals(order, fs.searchOrder());
      var forged =
          new dev.bluevista.craftq3.core.fs.VirtualFileSystem.Origin(
              "baseq3", "/outside/pak0.pk3", true);
      assertThrows(IOException.class, () -> fs.readFrom(forged, path));
    }
  }

  @Test
  void archiveStreamingUsesOnlyIndexedPacksAndRejectsReplacementSymlinks() throws Exception {
    pak("baseq3/test.pk3", Map.of("marker", "archive stream"));
    Path path = root.resolve("baseq3/test.pk3");
    byte[] expected = Files.readAllBytes(path);
    try (var fs = Pk3FileSystem.mount(root, null)) {
      var pack = fs.packs().getFirst();
      try (var archive = fs.openArchive(pack)) {
        assertEquals(expected.length, archive.size());
        assertArrayEquals(expected, archive.input().readAllBytes());
      }
      var forged =
          new Pk3FileSystem.Pack(
              new dev.bluevista.craftq3.core.fs.VirtualFileSystem.Origin(
                  "baseq3", root.resolve("outside").toString(), true),
              pack.name(),
              pack.checksum(),
              pack.pureChecksum(),
              pack.references());
      assertThrows(IOException.class, () -> fs.openArchive(forged));
      Path moved = path.resolveSibling("held.pk3");
      Files.move(path, moved);
      Files.createSymbolicLink(path, moved.getFileName());
      assertThrows(IOException.class, () -> fs.openArchive(pack));
    }
  }

  @Test
  void priorityIsModThenLooseThenDescendingPak() throws Exception {
    loose("baseq3/maps/test.bsp", "loose");
    pak(
        "baseq3/pak0.pk3",
        Map.of(
            "maps/test.bsp",
            "base0",
            "maps/packed.bsp",
            "base0",
            "scripts/base.shader",
            "fallback"));
    pak("baseq3/pak9.pk3", Map.of("MAPS/TEST.BSP", "base9", "maps/packed.bsp", "base9"));
    pak("baseq3/pak10.pk3", Map.of("maps/test.bsp", "base10", "maps/packed.bsp", "base10"));
    try (var fs = Pk3FileSystem.mount(root, "baseq3")) {
      assertEquals("loose", fs.readText(new VirtualPath("maps/test.bsp")));
      assertEquals("base9", fs.readText(new VirtualPath("maps/packed.bsp")));
      assertEquals(3, fs.list("maps").size() + fs.list("scripts").size());
      assertTrue(
          fs.which(new VirtualPath("MAPS/PACKED.BSP"))
              .orElseThrow()
              .container()
              .endsWith("pak9.pk3"));
    }
    loose("mod/maps/test.bsp", "modLoose");
    try (var fs = Pk3FileSystem.mount(root, "mod")) {
      assertEquals("modLoose", fs.readText(new VirtualPath("maps/test.bsp")));
      assertEquals("fallback", fs.readText(new VirtualPath("scripts/base.shader")));
    }
    pak("mod/zzz.pk3", Map.of("maps/test.bsp", "modPak"));
    try (var fs = Pk3FileSystem.mount(root, "mod")) {
      assertEquals("modLoose", fs.readText(new VirtualPath("maps/test.bsp")));
      assertEquals("mod", fs.searchOrder().getFirst().game());
    }
  }

  @Test
  void legacyNamesAndCommentsPreserveGuestBytesAndUtf8NamesRemainSupported() throws Exception {
    Path path = root.resolve("baseq3/legacy.pk3");
    Files.createDirectories(path.getParent());
    try (var zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.ISO_8859_1)) {
      ZipEntry entry = new ZipEntry("README-\u0087.TXT");
      entry.setComment("legacy-\u0082");
      zip.putNextEntry(entry);
      zip.write("legacy".getBytes(StandardCharsets.US_ASCII));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("maps/plain.bsp"));
      zip.write("map".getBytes(StandardCharsets.US_ASCII));
      zip.closeEntry();
    }
    pak("baseq3/utf8.pk3", Map.of("caf\u00e9.txt", "utf8"));
    try (var fs = Pk3FileSystem.mount(root, null)) {
      assertEquals("legacy", fs.readText(new VirtualPath("readme-\u0087.txt")));
      assertEquals("map", fs.readText(new VirtualPath("maps/plain.bsp")));
      assertEquals("utf8", fs.readText(new VirtualPath("caf\u00e9.txt")));
    }
  }

  @Test
  void malformedDeclaredUtf8StillFailsAndIdentifiesItsArchive() throws Exception {
    pak("baseq3/bad-utf8.pk3", Map.of("caf\u00e9.txt", "data"));
    Path path = root.resolve("baseq3/bad-utf8.pk3");
    byte[] bytes = Files.readAllBytes(path);
    for (int i = 0; i < bytes.length - 1; i++) {
      if (bytes[i] == (byte) 0xc3 && bytes[i + 1] == (byte) 0xa9) bytes[i] = (byte) 0xff;
    }
    Files.write(path, bytes);
    IOException error = assertThrows(IOException.class, () -> Pk3FileSystem.mount(root, null));
    assertTrue(error.getMessage().contains("bad-utf8.pk3"));
    assertInstanceOf(java.util.zip.ZipException.class, error.getCause());
  }

  @Test
  void looseFilesAreCaseInsensitiveAndEnumerationRespectsDirectoryBoundary() throws Exception {
    loose("baseq3/Textures/Test.TGA", "pixels");
    loose("baseq3/textures2/other.tga", "other");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      assertEquals("pixels", fs.readText(new VirtualPath("TEXTURES\\TEST.TGA")));
      assertEquals(1, fs.list("Textures/").size());
      assertFalse(fs.which(new VirtualPath("missing")).isPresent());
      assertThrows(IOException.class, () -> fs.read(new VirtualPath("missing")));
      byte[] a = fs.read(new VirtualPath("textures/test.tga"));
      a[0] = 0;
      assertEquals("pixels", fs.readText(new VirtualPath("textures/test.tga")));
    }
  }

  @Test
  void rejectsTraversalAndAmbiguousArchives() throws Exception {
    pak("baseq3/bad.pk3", Map.of("../escape", "unsafe"));
    assertThrows(IOException.class, () -> Pk3FileSystem.mount(root, null));
    Files.delete(root.resolve("baseq3/bad.pk3"));
    pak("baseq3/bad.pk3", Map.of("A.txt", "first", "a.txt", "second"));
    assertThrows(IOException.class, () -> Pk3FileSystem.mount(root, null));
  }

  @Test
  void rejectsZipBombByDeclaredSize() throws Exception {
    Files.createDirectories(root.resolve("baseq3"));
    try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("baseq3/bomb.pk3")))) {
      zip.putNextEntry(new ZipEntry("huge"));
      byte[] chunk = new byte[1024 * 1024];
      for (int i = 0; i < 65; i++) zip.write(chunk);
    }
    assertThrows(IOException.class, () -> Pk3FileSystem.mount(root, null));
  }

  @Test
  void ignoresSymlinksAndRefusesReplacementAfterMount() throws Exception {
    loose("baseq3/file", "safe");
    loose("outside", "secret");
    Files.createSymbolicLink(root.resolve("baseq3/link"), root.resolve("outside"));
    try (var fs = Pk3FileSystem.mount(root, null)) {
      assertTrue(fs.which(new VirtualPath("link")).isEmpty());
      Files.delete(root.resolve("baseq3/file"));
      Files.createSymbolicLink(root.resolve("baseq3/file"), root.resolve("outside"));
      assertThrows(IOException.class, () -> fs.read(new VirtualPath("file")));
    }
  }

  @Test
  void missingModFailsAndClosedMountCannotBeUsed() throws Exception {
    Files.createDirectories(root.resolve("baseq3"));
    assertThrows(IOException.class, () -> Pk3FileSystem.mount(root, "absent"));
    assertThrows(IllegalArgumentException.class, () -> Pk3FileSystem.mount(root, "../escape"));
    var fs = Pk3FileSystem.mount(root, null);
    fs.close();
    fs.close();
    assertThrows(IllegalStateException.class, () -> fs.list(""));
  }

  @Test
  void rejectsOversizedMetadataBeforeZipIndexing() throws Exception {
    pak("baseq3/test.pk3", Map.of("ok", "data"));
    Path path = root.resolve("baseq3/test.pk3");
    byte[] bytes = Files.readAllBytes(path);
    java.nio.ByteBuffer.wrap(bytes)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .putInt(bytes.length - 22 + 12, Integer.MAX_VALUE);
    Files.write(path, bytes);
    assertThrows(IOException.class, () -> Pk3FileSystem.mount(root, null));
  }

  @Test
  void verifiesStoredEntryCrc() throws Exception {
    Path path = root.resolve("baseq3/test.pk3");
    Files.createDirectories(path.getParent());
    byte[] data = "sentinel".getBytes(StandardCharsets.US_ASCII);
    var crc = new java.util.zip.CRC32();
    crc.update(data);
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      ZipEntry entry = new ZipEntry("payload");
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(data.length);
      entry.setCrc(crc.getValue());
      zip.putNextEntry(entry);
      zip.write(data);
      zip.closeEntry();
    }
    byte[] bytes = Files.readAllBytes(path);
    bytes[30 + "payload".length()] ^= 1;
    Files.write(path, bytes);
    try (var fs = Pk3FileSystem.mount(root, null)) {
      assertThrows(IOException.class, () -> fs.read(new VirtualPath("payload")));
    }
  }

  @Test
  void validZipCommentsAndEmptyArchivesAreSupported() throws Exception {
    Path path = root.resolve("baseq3/empty.pk3");
    Files.createDirectories(path.getParent());
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      zip.setComment("CraftQ3 synthetic empty pack");
    }
    try (var fs = Pk3FileSystem.mount(root, null)) {
      assertEquals(1, fs.searchOrder().size());
      assertTrue(fs.packs().isEmpty());
    }
  }
}

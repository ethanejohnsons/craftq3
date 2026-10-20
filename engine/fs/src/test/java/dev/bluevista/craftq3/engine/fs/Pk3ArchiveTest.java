package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Pk3ArchiveTest {
  @TempDir Path root;

  private static String text(Pk3Archive archive, String path) throws IOException {
    return new String(
        archive.read(archive.find(VirtualPath.of(path)).orElseThrow()),
        StandardCharsets.ISO_8859_1);
  }

  private static int crc(String text) {
    var crc = new CRC32();
    crc.update(text.getBytes(StandardCharsets.ISO_8859_1));
    return (int) crc.getValue();
  }

  @Test
  void indexesBackslashSpaceAndDirectoryEntriesForStoredAndDeflatedData() throws Exception {
    Path file =
        TestZip.create()
            .directory("env/")
            .add("env\\sunset1_up.jpg", "sky")
            .add("sound/weener dog.wav", "woof")
            .directory("textures\\")
            .stored()
            .add("Textures\\Casun\\Pool2.JPG", "water")
            .write(root.resolve("casdm9v1.pk3"));
    try (var archive = Pk3Archive.open(file)) {
      assertEquals(3, archive.entries().size());
      assertEquals(0, archive.problemCount());
      assertEquals("sky", text(archive, "ENV/SUNSET1_UP.JPG"));
      assertEquals("woof", text(archive, "sound/weener dog.wav"));
      assertEquals("water", text(archive, "textures/casun/pool2.jpg"));
      assertEquals(
          "Textures\\Casun\\Pool2.JPG",
          archive.find(VirtualPath.of("textures/casun/pool2.jpg")).orElseThrow().name());
      assertTrue(archive.find(VirtualPath.of("env")).isEmpty());
    }
  }

  @Test
  void acceptsEndOfDirectoryCommentsAndPrependedData() throws Exception {
    Path commented =
        TestZip.create()
            .add("a.txt", "a")
            .comment("Created by a map editor")
            .write(root.resolve("c.pk3"));
    Path prefixed =
        TestZip.create()
            .add("b.txt", "b")
            .prefix("MZ self-extractor stub".getBytes(StandardCharsets.US_ASCII))
            .comment("PK\u0005\u0006 inside a comment")
            .write(root.resolve("p.pk3"));
    try (var a = Pk3Archive.open(commented);
        var b = Pk3Archive.open(prefixed)) {
      assertEquals("a", text(a, "a.txt"));
      assertEquals("b", text(b, "b.txt"));
    }
    var empty = new java.io.ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(empty)) {
      zip.setComment("CraftQ3 synthetic empty pack");
    }
    try (var archive = Pk3Archive.open(Files.write(root.resolve("e.pk3"), empty.toByteArray()))) {
      assertTrue(archive.entries().isEmpty());
      assertEquals(Pk3Checksums.normal(new int[0]), archive.checksum());
    }
  }

  @Test
  void emptyTruncatedAndUnindexableArchivesFailWithFormatException() throws Exception {
    Path empty = Files.write(root.resolve("empty.pk3"), new byte[0]);
    assertThrows(Pk3FormatException.class, () -> Pk3Archive.open(empty));
    Path junk = Files.write(root.resolve("junk.pk3"), new byte[100]);
    assertThrows(Pk3FormatException.class, () -> Pk3Archive.open(junk));
    Path tiny = Files.write(root.resolve("tiny.pk3"), new byte[] {'P', 'K'});
    assertThrows(Pk3FormatException.class, () -> Pk3Archive.open(tiny));
  }

  private Path patchEnd(String name, int offset, int value, boolean shortValue) throws IOException {
    byte[] bytes = TestZip.create().add("ok", "data").bytes();
    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    int end = bytes.length - 22;
    if (shortValue) buffer.putShort(end + offset, (short) value);
    else buffer.putInt(end + offset, value);
    return Files.write(root.resolve(name), bytes);
  }

  @Test
  void rejectsZip64MultiVolumeAndOversizedDirectories() throws Exception {
    assertThrows(Pk3FormatException.class, () -> Pk3Archive.open(patchEnd("disk.pk3", 4, 1, true)));
    assertThrows(
        Pk3FormatException.class, () -> Pk3Archive.open(patchEnd("count.pk3", 10, 0xffff, true)));
    assertThrows(
        Pk3FormatException.class, () -> Pk3Archive.open(patchEnd("size.pk3", 12, -1, false)));
    // A directory size that reaches past the record cannot be located.
    assertThrows(
        Pk3FormatException.class,
        () -> Pk3Archive.open(patchEnd("huge.pk3", 12, Integer.MAX_VALUE, false)));
    // More entries than the directory has room for.
    assertThrows(
        Pk3FormatException.class, () -> Pk3Archive.open(patchEnd("many.pk3", 10, 2000, true)));
  }

  @Test
  void skipsUnsafeEntriesButChecksumsEveryEntryLikeTheOriginal() throws Exception {
    Path file =
        TestZip.create()
            .add("good.txt", "good")
            .addFlagged("secret.txt", "locked", 1)
            .addWithMethod("imploded.txt", "old", 6)
            .add("../escape.txt", "unsafe")
            .add("c:/drive.txt", "unsafe")
            .add("empty.txt", "")
            .write(root.resolve("mixed.pk3"));
    try (var archive = Pk3Archive.open(file)) {
      assertEquals(
          java.util.List.of("good.txt", "empty.txt"),
          archive.entries().stream().map(Pk3Archive.Entry::name).toList());
      assertEquals(4, archive.problemCount());
      assertTrue(archive.problems().getFirst().contains("encrypted"));
      int[] expected = {crc("good"), crc("locked"), crc("old"), crc("unsafe"), crc("unsafe")};
      assertArrayEquals(expected, archive.checksumCrcs());
      assertEquals(Pk3Checksums.normal(expected), archive.checksum());
      assertEquals("good", text(archive, "good.txt"));
      assertEquals("", text(archive, "empty.txt"));
    }
  }

  @Test
  void laterDuplicateNamesWinAsTheOriginalHashChainDoes() throws Exception {
    Path file =
        TestZip.create().add("A.txt", "first").add("a.TXT", "second").write(root.resolve("d.pk3"));
    try (var archive = Pk3Archive.open(file)) {
      assertEquals(1, archive.entries().size());
      assertEquals(1, archive.problemCount());
      assertEquals("second", text(archive, "a.txt"));
    }
  }

  @Test
  void entryBytesAreCheckedAgainstTheirCrc() throws Exception {
    byte[] stored = TestZip.create().stored().add("payload", "sentinel").bytes();
    stored[30 + "payload".length()] ^= 1;
    byte[] deflated = TestZip.create().add("video/test.roq", "original original").bytes();
    for (int i = 0; i + 20 < deflated.length; i++)
      if (deflated[i] == 0x50 && deflated[i + 1] == 0x4b && deflated[i + 2] == 1) {
        deflated[i + 16] ^= 1;
        break;
      }
    try (var a = Pk3Archive.open(Files.write(root.resolve("s.pk3"), stored));
        var b = Pk3Archive.open(Files.write(root.resolve("d.pk3"), deflated))) {
      assertThrows(IOException.class, () -> text(a, "payload"));
      var entry = b.find(VirtualPath.of("video/test.roq")).orElseThrow();
      try (InputStream stream = b.open(entry)) {
        assertThrows(IOException.class, stream::readAllBytes);
      }
    }
  }

  @Test
  void cinematicsStreamPastTheBufferedLimitWhileOtherLargeEntriesAreSkipped() throws Exception {
    Path file = root.resolve("large.pk3");
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      byte[] block = new byte[1024 * 1024];
      for (String name : new String[] {"video/large.roq", "large.dat"}) {
        zip.putNextEntry(new ZipEntry(name));
        for (int i = 0; i < 65; i++) zip.write(block);
        zip.closeEntry();
      }
    }
    try (var archive = Pk3Archive.open(file)) {
      assertTrue(archive.find(VirtualPath.of("large.dat")).isEmpty());
      assertEquals(1, archive.problemCount());
      var movie = archive.find(VirtualPath.of("video/large.roq")).orElseThrow();
      try (var stream = archive.open(movie)) {
        assertEquals(32, stream.readNBytes(32).length);
      }
      assertThrows(IOException.class, () -> archive.read(movie));
    }
  }

  @Test
  void namesAreRawBytesWhateverTheUtf8Flag() throws Exception {
    Path file = root.resolve("names.pk3");
    try (var zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.ISO_8859_1)) {
      zip.putNextEntry(new ZipEntry("README-\u0087.TXT"));
      zip.write('l');
      zip.closeEntry();
    }
    Path utf8 = root.resolve("utf8.pk3");
    try (var zip = new ZipOutputStream(Files.newOutputStream(utf8))) {
      zip.putNextEntry(new ZipEntry("caf\u00e9.txt"));
      zip.write('u');
      zip.closeEntry();
    }
    String utf8Bytes =
        new String("caf\u00e9.txt".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    try (var a = Pk3Archive.open(file);
        var b = Pk3Archive.open(utf8)) {
      assertEquals("l", text(a, "readme-\u0087.txt"));
      assertEquals("u", text(b, utf8Bytes));
    }
  }

  @Test
  void anInterruptedReaderDoesNotBreakTheSharedArchive() throws Exception {
    Path file = TestZip.create().add("a.txt", "shared").write(root.resolve("i.pk3"));
    try (var archive = Pk3Archive.open(file)) {
      var entry = archive.find(VirtualPath.of("a.txt")).orElseThrow();
      Thread.currentThread().interrupt();
      try {
        assertThrows(ClosedByInterruptException.class, () -> archive.read(entry));
      } finally {
        Thread.interrupted();
      }
      assertEquals("shared", text(archive, "a.txt"));
    }
  }

  @Test
  void closedArchivesAndForeignEntriesAreRejected() throws Exception {
    Path one = TestZip.create().add("a.txt", "one").write(root.resolve("one.pk3"));
    Path two = TestZip.create().add("a.txt", "two").write(root.resolve("two.pk3"));
    var first = Pk3Archive.open(one);
    try (var second = Pk3Archive.open(two)) {
      var foreign = second.find(VirtualPath.of("a.txt")).orElseThrow();
      assertThrows(IOException.class, () -> first.read(foreign));
      try (var whole = second.openFile()) {
        assertArrayEquals(Files.readAllBytes(two), whole.readAllBytes());
      }
    }
    var entry = first.find(VirtualPath.of("a.txt")).orElseThrow();
    first.close();
    first.close();
    assertThrows(IOException.class, () -> first.read(entry));
  }
}

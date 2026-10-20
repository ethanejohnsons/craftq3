package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchPathTest {
  @TempDir Path root;

  private void pak(String file, Map<String, String> contents) throws IOException {
    var zip = TestZip.create();
    new TreeMap<>(contents).forEach(zip::add);
    zip.write(root.resolve(file));
  }

  private void loose(String path, String text) throws IOException {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static String origin(SearchPath fs, String path) {
    return fs.find(VirtualPath.of(path)).orElseThrow().mount().location().getFileName().toString();
  }

  private static String text(FileSource fs, String path) throws IOException {
    return fs.readText(VirtualPath.of(path));
  }

  @Test
  void originalOrderSearchesPaksBeforeLooseFilesWithLaterNamesWinning() throws Exception {
    loose("baseq3/maps/test.bsp", "loose");
    loose("baseq3/maps/looseonly.bsp", "loose");
    pak("baseq3/pak0.pk3", Map.of("maps/test.bsp", "base0", "maps/packed.bsp", "base0"));
    pak("baseq3/pak9.pk3", Map.of("MAPS/TEST.BSP", "base9", "maps/packed.bsp", "base9"));
    pak("baseq3/pak10.pk3", Map.of("maps/test.bsp", "base10", "maps/packed.bsp", "base10"));
    try (var fs = SearchPath.mount(root, null)) {
      assertEquals(SearchOrder.ORIGINAL_132, fs.order());
      assertEquals("base9", text(fs, "maps/test.bsp"));
      assertEquals("base9", text(fs, "maps/packed.bsp"));
      assertEquals("loose", text(fs, "maps/looseonly.bsp"));
      assertEquals("pak9.pk3", origin(fs, "MAPS/PACKED.BSP"));
      assertEquals(
          List.of("pak9", "pak10", "pak0"), fs.packs().stream().map(PackMount::name).toList());
      assertInstanceOf(DirectoryMount.class, fs.mounts().getLast());
    }
  }

  @Test
  void looseFirstOrderLetsLooseFilesOverridePaksOfTheSameDirectory() throws Exception {
    loose("baseq3/maps/test.bsp", "loose");
    pak("baseq3/pak0.pk3", Map.of("maps/test.bsp", "base0", "maps/packed.bsp", "base0"));
    try (var fs = SearchPath.mount(root, null, SearchOrder.LOOSE_FIRST)) {
      assertEquals("loose", text(fs, "maps/test.bsp"));
      assertEquals("base0", text(fs, "maps/packed.bsp"));
      assertInstanceOf(DirectoryMount.class, fs.mounts().getFirst());
    }
  }

  @Test
  void zzPrefixedOverridesWinBecauseUnderscoreSortsAfterLetters() throws Exception {
    pak("mod/zzz.pk3", Map.of("scripts/a.shader", "zzz"));
    pak("mod/zz_override.pk3", Map.of("scripts/a.shader", "zz_"));
    pak("mod/ZZY.pk3", Map.of("scripts/a.shader", "ZZY"));
    Files.createDirectories(root.resolve("baseq3"));
    try (var fs = SearchPath.mount(root, "mod")) {
      assertEquals("zz_", text(fs, "scripts/a.shader"));
      assertEquals(
          List.of("zz_override", "zzz", "ZZY"), fs.packs().stream().map(PackMount::name).toList());
    }
  }

  @Test
  void modDirectoryPrecedesBaseFallbackInBothOrders() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("maps/test.bsp", "base", "scripts/base.shader", "fallback"));
    loose("Mod/maps/test.bsp", "modLoose");
    for (SearchOrder order : SearchOrder.values()) {
      try (var fs = SearchPath.mount(root, "Mod", order)) {
        assertEquals("mod", fs.game());
        assertEquals("modLoose", text(fs, "maps/test.bsp"));
        assertEquals("fallback", text(fs, "scripts/base.shader"));
        assertEquals("mod", fs.mounts().getFirst().game());
      }
    }
    pak("Mod/zzz.pk3", Map.of("maps/test.bsp", "modPak"));
    try (var fs = SearchPath.mount(root, "mod")) {
      // Found case-insensitively; the mod's pak beats its loose file in the original order.
      assertEquals("modPak", text(fs, "maps/test.bsp"));
    }
    try (var fs = SearchPath.mount(root, "mod", SearchOrder.LOOSE_FIRST)) {
      assertEquals("modLoose", text(fs, "maps/test.bsp"));
    }
  }

  @Test
  void emptyAndMalformedPaksAreSkippedAndReportedWithoutFailingTheMount() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("good.txt", "good"));
    Files.write(root.resolve("baseq3/CorkScrew_110.pk3"), new byte[0]);
    Files.write(root.resolve("baseq3/broken.PK3"), "not a zip".getBytes(StandardCharsets.US_ASCII));
    Files.write(root.resolve("baseq3/notes.pk3.tmp"), new byte[0]);
    try (var fs = SearchPath.mount(root, null)) {
      assertEquals("good", text(fs, "good.txt"));
      assertEquals(1, fs.packs().size());
      var skipped = fs.report().skippedArchives();
      assertEquals(
          List.of("CorkScrew_110.pk3", "broken.PK3"),
          skipped.stream().map(s -> s.file().getFileName().toString()).sorted().toList());
      assertTrue(skipped.stream().allMatch(s -> s.game().equals("baseq3")));
    }
  }

  @Test
  void unsafeAndDuplicateMembersAreSkippedNotFatal() throws Exception {
    TestZip.create()
        .add("../escape", "unsafe")
        .add("A.txt", "first")
        .add("a.txt", "second")
        .write(root.resolve("baseq3/bad.pk3"));
    try (var fs = SearchPath.mount(root, null)) {
      assertEquals("second", text(fs, "a.txt"));
      assertEquals(2, fs.report().skippedEntries());
      assertTrue(fs.report().skippedArchives().isEmpty());
    }
  }

  @Test
  void zipBombsByDeclaredSizeAreSkippedEntries() throws Exception {
    Files.createDirectories(root.resolve("baseq3"));
    try (var zip = new ZipOutputStream(Files.newOutputStream(root.resolve("baseq3/bomb.pk3")))) {
      zip.putNextEntry(new ZipEntry("huge"));
      byte[] chunk = new byte[1024 * 1024];
      for (int i = 0; i < 65; i++) zip.write(chunk);
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("small"));
      zip.write('s');
    }
    try (var fs = SearchPath.mount(root, null)) {
      assertFalse(fs.exists(VirtualPath.of("huge")));
      assertEquals("s", text(fs, "small"));
    }
  }

  @Test
  void entryStreamsPreserveOverridesAndEarlyClose() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("video/test.roq", "base"));
    pak("baseq3/pak1.pk3", Map.of("video/test.roq", "override"));
    try (var fs = SearchPath.mount(root, null)) {
      try (var stream = fs.open(VirtualPath.of("VIDEO/TEST.ROQ"))) {
        assertEquals('o', stream.read());
        assertEquals(2, stream.skip(2));
        assertEquals("rride", new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1));
        assertEquals(-1, stream.read());
      }
      var stream = fs.open(VirtualPath.of("video/test.roq"));
      stream.close();
      assertThrows(IOException.class, stream::read);
      assertThrows(NoSuchFileException.class, () -> fs.open(VirtualPath.of("missing.roq")));
    }
  }

  @Test
  void looseStreamsRejectGrowthTruncationAndSymlinkReplacement() throws Exception {
    for (boolean grow : new boolean[] {true, false}) {
      loose("baseq3/video/test.roq", "original");
      try (var fs = SearchPath.mount(root, null);
          var stream = fs.open(VirtualPath.of("video/test.roq"))) {
        Files.writeString(
            root.resolve("baseq3/video/test.roq"), grow ? "original enlarged" : "short");
        assertThrows(IOException.class, stream::readAllBytes);
      }
    }
    loose("baseq3/video/test.roq", "original");
    try (var fs = SearchPath.mount(root, null)) {
      var path = root.resolve("baseq3/video/test.roq");
      Files.delete(path);
      Files.writeString(root.resolve("outside"), "outside");
      if (!Links.create(path, root.resolve("outside"))) return;
      assertThrows(IOException.class, () -> fs.open(VirtualPath.of("video/test.roq")));
    }
  }

  @Test
  void ignoresLooseSymlinksAndRefusesReplacementAfterMount() throws Exception {
    loose("baseq3/file", "safe");
    loose("outside", "secret");
    if (!Links.create(root.resolve("baseq3/link"), root.resolve("outside"))) return;
    try (var fs = SearchPath.mount(root, null)) {
      assertFalse(fs.exists(VirtualPath.of("link")));
      Files.delete(root.resolve("baseq3/file"));
      Files.createSymbolicLink(root.resolve("baseq3/file"), root.resolve("outside"));
      assertThrows(IOException.class, () -> fs.read(VirtualPath.of("file")));
    }
  }

  @Test
  void hostCanReadShadowedMountWithoutChangingSearchOrder() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("maps/test.bsp", "original"));
    pak("baseq3/pak1.pk3", Map.of("maps/test.bsp", "replacement"));
    try (var fs = SearchPath.mount(root, "baseq3");
        var other = SearchPath.mount(root, "baseq3")) {
      var path = VirtualPath.of("maps/test.bsp");
      var original =
          fs.packs().stream().filter(p -> p.name().equals("pak0")).findFirst().orElseThrow();
      assertEquals(
          "original", new String(fs.readFrom(original, path), StandardCharsets.ISO_8859_1));
      assertEquals("replacement", text(fs, "maps/test.bsp"));
      assertThrows(IOException.class, () -> fs.readFrom(other.packs().getFirst(), path));
    }
  }

  @Test
  void archivesAreHeldOpenSoReplacingTheFileAfterMountChangesNothing() throws Exception {
    pak("baseq3/test.pk3", Map.of("marker", "archive stream"));
    Path path = root.resolve("baseq3/test.pk3");
    byte[] expected = Files.readAllBytes(path);
    try (var fs = SearchPath.mount(root, null)) {
      var pack = fs.packs().getFirst();
      Files.move(path, path.resolveSibling("held.pk3"));
      pak("baseq3/test.pk3", Map.of("marker", "replaced"));
      try (var archive = pack.archive().openFile()) {
        assertArrayEquals(expected, archive.readAllBytes());
      }
      assertEquals("archive stream", text(fs, "marker"));
    }
  }

  @Test
  void looseFilesAreCaseInsensitiveAndListingRespectsDirectoryBoundaries() throws Exception {
    loose("baseq3/Textures/Test.TGA", "pixels");
    loose("baseq3/textures2/other.tga", "other");
    pak(
        "baseq3/pak0.pk3",
        Map.of("textures/packed.tga", "p", "textures/sub/deep.tga", "d", "TEXTURES/test.tga", "x"));
    try (var fs = SearchPath.mount(root, null)) {
      assertEquals("x", text(fs, "TEXTURES\\TEST.TGA"));
      // Search order, then central-directory order; the loose Test.TGA is a duplicate.
      assertEquals(List.of("test.tga", "packed.tga"), fs.list("Textures/", ".tga"));
      assertEquals(List.of("sub"), fs.list("textures", "/"));
      assertEquals(List.of("other.tga"), fs.list("textures2", "tga"));
      assertEquals(List.of(), fs.list("textures", ".jpg"));
      assertFalse(fs.exists(VirtualPath.of("missing")));
      assertThrows(NoSuchFileException.class, () -> fs.read(VirtualPath.of("missing")));
      byte[] a = fs.read(VirtualPath.of("textures2/other.tga"));
      a[0] = 0;
      assertEquals("other", text(fs, "textures2/other.tga"));
    }
  }

  @Test
  void missingGameDirectoryFailsAndClosedSearchPathCannotBeUsed() throws Exception {
    assertThrows(NoSuchFileException.class, () -> SearchPath.mount(root, null));
    Files.createDirectories(root.resolve("baseq3"));
    assertThrows(NoSuchFileException.class, () -> SearchPath.mount(root, "absent"));
    assertThrows(IllegalArgumentException.class, () -> SearchPath.mount(root, "../escape"));
    var fs = SearchPath.mount(root, null);
    fs.close();
    fs.close();
    assertThrows(IllegalStateException.class, () -> fs.list("", ""));
    assertThrows(IllegalStateException.class, () -> fs.exists(VirtualPath.of("x")));
  }

  @Test
  void cachedArchivesSortByServerNameAndReplaceSameNamedPaks() throws Exception {
    pak("baseq3/pak0.pk3", Map.of("marker", "pak0"));
    pak("baseq3/custom.pk3", Map.of("marker", "installed custom"));
    Path cached = TestZip.create().add("marker", "cached").write(root.resolve("cache/x.pk3"));
    int checksum;
    try (var archive = Pk3Archive.open(cached)) {
      checksum = archive.checksum();
    }
    try (var fs =
        SearchPath.builder(root).cachedArchive("baseq3", cached, "zcustom", checksum).build()) {
      assertEquals("cached", text(fs, "marker"));
      assertEquals("zcustom", fs.packs().getFirst().name());
      assertTrue(fs.packs().getFirst().cached());
    }
    try (var fs =
        SearchPath.builder(root).cachedArchive("baseq3", cached, "Custom", checksum).build()) {
      assertEquals(2, fs.packs().size());
      var custom = fs.packs().getLast();
      assertEquals("Custom", custom.name());
      assertTrue(custom.cached());
      assertEquals("pak0", text(fs, "marker"));
      assertEquals(
          "cached",
          new String(fs.readFrom(custom, VirtualPath.of("marker")), StandardCharsets.UTF_8));
    }
    try (var fs =
        SearchPath.builder(root).cachedArchive("baseq3", cached, "zcustom", checksum + 1).build()) {
      assertEquals("pak0", text(fs, "marker"));
      assertEquals(1, fs.report().skippedArchives().size());
    }
    var builder = SearchPath.builder(root).cachedArchive("othermod", cached, "x", checksum);
    assertThrows(IOException.class, builder::build);
  }
}

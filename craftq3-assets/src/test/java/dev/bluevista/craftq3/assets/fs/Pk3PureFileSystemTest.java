package dev.bluevista.craftq3.assets.fs;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Pk3PureFileSystemTest {
  @TempDir Path root;

  private void pack(String name, String... pairs) throws IOException {
    Path file = root.resolve("baseq3/" + name + ".pk3");
    Files.createDirectories(file.getParent());
    try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
      for (int i = 0; i < pairs.length; i += 2) {
        zip.putNextEntry(new ZipEntry(pairs[i]));
        zip.write(pairs[i + 1].getBytes(StandardCharsets.ISO_8859_1));
        zip.closeEntry();
      }
    }
  }

  private void loose(String name, String value) throws IOException {
    Path file = root.resolve("baseq3/" + name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, value);
  }

  private static Pk3FileSystem.Pack pack(Pk3FileSystem fs, String name) {
    return fs.packs().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
  }

  private static List<String> names(Pk3FileSystem fs) {
    return fs.packs().stream().map(Pk3FileSystem.Pack::name).toList();
  }

  @Test
  void indexesCentralDirectoryCrcsIncludingDataBearingDirectoriesButExcludingEmptyFiles()
      throws Exception {
    pack("ordered", "z", "abc", "empty", "", "directory/", "xyz");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      var pack = pack(fs, "ordered");
      assertEquals(1225466421, pack.checksum());
      assertEquals(-388958916, pack.pureChecksum());
      fs.restartView(42);
      assertEquals(442607164, pack(fs, "ordered").pureChecksum());
      assertEquals(1225466421, pack(fs, "ordered").checksum());
      assertTrue(fs.which(new VirtualPath("directory")).isEmpty());
    }
  }

  @Test
  void filtersImmediatelyThenReordersOnRestartAndRestoresDefaultViewOnClear() throws Exception {
    pack("a", "shared.dat", "a", "only-a", "a");
    pack("z", "shared.dat", "z", "only-z", "z");
    pack("u", "shared.dat", "u", "only-u", "u");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      int a = pack(fs, "a").checksum(), z = pack(fs, "z").checksum();
      fs.pureServerPaks(List.of(a, z));
      assertEquals("z", fs.readText(new VirtualPath("shared.dat")));
      assertFalse(fs.which(new VirtualPath("only-u")).isPresent());
      assertEquals(List.of("z", "u", "a"), names(fs));
      fs.restartView(42);
      assertEquals(List.of("a", "z", "u"), names(fs));
      assertEquals("a", fs.readText(new VirtualPath("shared.dat")));
      fs.pureServerPaks(List.of());
      assertEquals(List.of("z", "u", "a"), names(fs));
      assertEquals("z", fs.readText(new VirtualPath("shared.dat")));
      assertEquals(0, pack(fs, "a").references());
      assertTrue(fs.which(new VirtualPath("only-u")).isPresent());
    }
  }

  @Test
  void duplicateChecksumOccurrencesConsumeDistinctPacksAndMissingSumsDoNotInventFiles()
      throws Exception {
    pack("a", "only-a", "same");
    pack("b", "only-b", "same");
    pack("z", "only-z", "different");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      int same = pack(fs, "a").checksum();
      assertEquals(same, pack(fs, "b").checksum());
      fs.pureServerPaks(List.of(same));
      fs.restartView(0);
      assertEquals(List.of("b", "z", "a"), names(fs));
      assertTrue(fs.which(new VirtualPath("only-a")).isPresent());
      fs.pureServerPaks(List.of(same, same, same));
      fs.restartView(0);
      assertEquals(List.of("b", "a", "z"), names(fs));
      fs.pureServerPaks(List.of(123456));
      fs.restartView(0);
      assertTrue(fs.list("").isEmpty());
      assertThrows(IOException.class, () -> fs.read(new VirtualPath("only-a")));
      assertEquals(3, fs.packs().size());
    }
  }

  @Test
  void allowsNativeLooseExceptionsButOmitsAllLooseFilesFromPureEnumeration() throws Exception {
    List<String> allowed =
        List.of(
            "x.CFG",
            "x.menu",
            "x.game",
            "x.dat",
            "x.dm_66",
            "x.dm_67",
            "x.dm_68",
            "x.dm_71",
            "x.dm_68a",
            "x.dm_+68",
            "x.dm_ 68",
            "x.dm_4294967364",
            "y.dm_68/file");
    List<String> denied =
        List.of(
            "x.txt",
            "x.shader",
            "vm/cgame.qvm",
            "x.jpg",
            "x.wav",
            "x.dm_43",
            "x.dm_69",
            "x.dm_70",
            "x.dm_-68",
            "x.dm_68.bak",
            "dm_68");
    for (String name : allowed) loose(name, "allowed");
    for (String name : denied) loose(name, "denied");
    pack("a", "packed.dat", "pack");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      fs.pureServerPaks(List.of(pack(fs, "a").checksum()));
      fs.restartView(42);
      for (String name : allowed) assertEquals("allowed", fs.readText(new VirtualPath(name)), name);
      for (String name : denied) assertFalse(fs.which(new VirtualPath(name)).isPresent(), name);
      assertEquals(List.of(new VirtualPath("packed.dat")), fs.list(""));
      assertEquals("@ 42 ", fs.referencedPureChecksums());
    }
  }

  @Test
  void readReferencesUseRequestedSpellingWhileLookupStaysCanonical() throws Exception {
    pack(
        "a",
        "vm/cgame.qvm",
        "c",
        "vm/ui.qvm",
        "u",
        "vm/qagame.qvm",
        "g",
        "levelshots/a.tga",
        "l",
        "x/vm/cgame.qvm.foo",
        "x",
        "empty",
        "");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      assertTrue(fs.which(new VirtualPath("vm/cgame.qvm")).isPresent());
      assertEquals(0, pack(fs, "a").references());
      var cases =
          Map.of(
              "VM/CGAME.QVM",
              1,
              "vm/cgame.qvm",
              5,
              "vm/ui.qvm",
              3,
              "VM/QAGAME.QVM",
              0,
              "vm\\qagame.qvm",
              1,
              "levelshots/a.tga",
              0,
              "Levelshots/a.tga",
              1,
              "x/vm/cgame.qvm.foo",
              5,
              "empty",
              1);
      for (var entry : cases.entrySet()) {
        fs.clearReferences(0);
        fs.read(new VirtualPath(entry.getKey()));
        assertEquals(entry.getValue(), pack(fs, "a").references(), entry.getKey());
      }
      fs.clearReferences(1);
      assertEquals(0, pack(fs, "a").references() & 1);
    }
  }

  @Test
  void pureTranscriptSelectsFirstModulePacksThenEveryGeneralPackAndCombinesFeedAndCount()
      throws Exception {
    pack("z", "vm/cgame.qvm", "cgame", "vm/ui.qvm", "ui");
    pack("a", "x/cgame.qvm", "alternate", "textures/test.tga", "pixels");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      fs.restartView(-1);
      fs.read(new VirtualPath("vm/cgame.qvm"));
      fs.read(new VirtualPath("vm/ui.qvm"));
      fs.read(new VirtualPath("x/cgame.qvm"));
      int z = pack(fs, "z").pureChecksum(), a = pack(fs, "a").pureChecksum();
      assertEquals(
          z + " " + z + " @ " + z + " " + a + " " + (-1 ^ z ^ a ^ 2) + " ",
          fs.referencedPureChecksums());
      fs.clearReferences(Pk3FileSystem.CGAME_REFERENCE);
      assertEquals(
          z + " @ " + z + " " + a + " " + (-1 ^ z ^ a ^ 2) + " ", fs.referencedPureChecksums());
      fs.clearReferences(0);
      assertEquals("@ -1 ", fs.referencedPureChecksums());
    }
  }

  @Test
  void clearingEligibilityPreservesReferencesUntilRestartHasAppliedServerOrdering()
      throws Exception {
    pack("z", "data", "bytes");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      int z = pack(fs, "z").checksum();
      fs.pureServerPaks(List.of(z));
      fs.read(new VirtualPath("data"));
      fs.pureServerPaks(List.of());
      assertEquals(1, pack(fs, "z").references());
      fs.pureServerPaks(List.of(z));
      fs.restartView(0);
      fs.read(new VirtualPath("data"));
      fs.pureServerPaks(List.of());
      assertEquals(0, pack(fs, "z").references());
    }
  }

  @Test
  void validatesPolicyBeforeChangingViewAndCopiesCallerLists() throws Exception {
    pack("a", "data", "a");
    try (var fs = Pk3FileSystem.mount(root, null)) {
      var policy = new ArrayList<>(List.of(pack(fs, "a").checksum()));
      fs.pureServerPaks(policy);
      policy.clear();
      assertEquals("a", fs.readText(new VirtualPath("data")));
      assertThrows(
          IllegalArgumentException.class,
          () -> fs.pureServerPaks(java.util.Collections.nCopies(1025, 0)));
      assertEquals("a", fs.readText(new VirtualPath("data")));
      assertThrows(UnsupportedOperationException.class, () -> fs.packs().clear());
    }
  }
}

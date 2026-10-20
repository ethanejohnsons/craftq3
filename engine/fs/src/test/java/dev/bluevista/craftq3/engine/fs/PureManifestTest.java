package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ported from the legacy pure file-system suite; expected checksums are native observations. */
final class PureManifestTest {
  @TempDir Path root;
  private SearchPath searchPath;

  @AfterEach
  void close() throws IOException {
    if (searchPath != null) searchPath.close();
  }

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

  private PureManifest mount() throws IOException {
    searchPath = SearchPath.mount(root, null);
    return new PureManifest(searchPath);
  }

  private static PureManifest.Pack pack(PureManifest fs, String name) {
    return fs.packs().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
  }

  private static List<String> names(PureManifest fs) {
    return fs.packs().stream().map(PureManifest.Pack::name).toList();
  }

  private static String text(PureManifest fs, String path) throws IOException {
    return fs.view().readText(VirtualPath.of(path));
  }

  private static boolean visible(PureManifest fs, String path) {
    return fs.view().exists(VirtualPath.of(path));
  }

  @Test
  void indexesCentralDirectoryCrcsIncludingDataBearingDirectoriesButExcludingEmptyFiles()
      throws Exception {
    pack("ordered", "z", "abc", "empty", "", "directory/", "xyz");
    var fs = mount();
    var pack = pack(fs, "ordered");
    assertEquals(1225466421, pack.checksum());
    assertEquals(-388958916, pack.pureChecksum());
    fs.restart(42);
    assertEquals(442607164, pack(fs, "ordered").pureChecksum());
    assertEquals(1225466421, pack(fs, "ordered").checksum());
    assertFalse(visible(fs, "directory"));
  }

  @Test
  void filtersImmediatelyThenReordersOnRestartAndRestoresDefaultViewOnClear() throws Exception {
    pack("a", "shared.dat", "a", "only-a", "a");
    pack("z", "shared.dat", "z", "only-z", "z");
    pack("u", "shared.dat", "u", "only-u", "u");
    var fs = mount();
    int a = pack(fs, "a").checksum(), z = pack(fs, "z").checksum();
    fs.setServerPaks(List.of(a, z));
    assertTrue(fs.pure());
    assertEquals("z", text(fs, "shared.dat"));
    assertFalse(visible(fs, "only-u"));
    assertEquals(List.of("z", "u", "a"), names(fs));
    fs.restart(42);
    assertEquals(List.of("a", "z", "u"), names(fs));
    assertEquals("a", text(fs, "shared.dat"));
    fs.setServerPaks(List.of());
    assertEquals(List.of("z", "u", "a"), names(fs));
    assertEquals("z", text(fs, "shared.dat"));
    assertEquals(0, pack(fs, "a").references());
    assertTrue(visible(fs, "only-u"));
  }

  @Test
  void duplicateChecksumOccurrencesConsumeDistinctPacksAndMissingSumsDoNotInventFiles()
      throws Exception {
    pack("a", "only-a", "same");
    pack("b", "only-b", "same");
    pack("z", "only-z", "different");
    var fs = mount();
    int same = pack(fs, "a").checksum();
    assertEquals(same, pack(fs, "b").checksum());
    fs.setServerPaks(List.of(same));
    fs.restart(0);
    assertEquals(List.of("b", "z", "a"), names(fs));
    assertTrue(visible(fs, "only-a"));
    fs.setServerPaks(List.of(same, same, same));
    fs.restart(0);
    assertEquals(List.of("b", "a", "z"), names(fs));
    fs.setServerPaks(List.of(123456));
    fs.restart(0);
    assertTrue(fs.view().list("", "").isEmpty());
    assertThrows(IOException.class, () -> fs.view().read(VirtualPath.of("only-a")));
    assertEquals(3, fs.packs().size());
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
    var fs = mount();
    fs.setServerPaks(List.of(pack(fs, "a").checksum()));
    fs.restart(42);
    for (String name : allowed) assertEquals("allowed", text(fs, name), name);
    for (String name : denied) assertFalse(visible(fs, name), name);
    assertEquals(List.of("packed.dat"), fs.view().list("", ""));
    assertEquals("@ 42 ", fs.referencedPureChecksums());
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
    var fs = mount();
    assertTrue(visible(fs, "vm/cgame.qvm"));
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
      fs.view().read(VirtualPath.of(entry.getKey()));
      assertEquals(entry.getValue(), pack(fs, "a").references(), entry.getKey());
    }
    fs.clearReferences(0);
    try (var stream = fs.view().open(VirtualPath.of("vm/cgame.qvm"))) {
      stream.readAllBytes();
    }
    assertEquals(5, pack(fs, "a").references(), "streams record references too");
    fs.clearReferences(1);
    assertEquals(4, pack(fs, "a").references());
    fs.clearReferences(0);
    searchPath.read(VirtualPath.of("vm/cgame.qvm"));
    assertEquals(0, pack(fs, "a").references(), "the build-order view does not track");
  }

  @Test
  void pureTranscriptSelectsFirstModulePacksThenEveryGeneralPackAndCombinesFeedAndCount()
      throws Exception {
    pack("z", "vm/cgame.qvm", "cgame", "vm/ui.qvm", "ui");
    pack("a", "x/cgame.qvm", "alternate", "textures/test.tga", "pixels");
    var fs = mount();
    fs.restart(-1);
    fs.view().read(VirtualPath.of("vm/cgame.qvm"));
    fs.view().read(VirtualPath.of("vm/ui.qvm"));
    fs.view().read(VirtualPath.of("x/cgame.qvm"));
    int z = pack(fs, "z").pureChecksum(), a = pack(fs, "a").pureChecksum();
    assertEquals(
        z + " " + z + " @ " + z + " " + a + " " + (-1 ^ z ^ a ^ 2) + " ",
        fs.referencedPureChecksums());
    fs.clearReferences(PureManifest.CGAME_REFERENCE);
    assertEquals(
        z + " @ " + z + " " + a + " " + (-1 ^ z ^ a ^ 2) + " ", fs.referencedPureChecksums());
    fs.clearReferences(0);
    assertEquals("@ -1 ", fs.referencedPureChecksums());
  }

  @Test
  void clearingEligibilityPreservesReferencesUntilRestartHasAppliedServerOrdering()
      throws Exception {
    pack("z", "data", "bytes");
    var fs = mount();
    int z = pack(fs, "z").checksum();
    fs.setServerPaks(List.of(z));
    fs.view().read(VirtualPath.of("data"));
    fs.setServerPaks(List.of());
    assertEquals(1, pack(fs, "z").references());
    fs.setServerPaks(List.of(z));
    fs.restart(0);
    fs.view().read(VirtualPath.of("data"));
    fs.setServerPaks(List.of());
    assertEquals(0, pack(fs, "z").references());
  }

  @Test
  void validatesPolicyBeforeChangingViewAndCopiesCallerLists() throws Exception {
    pack("a", "data", "a");
    var fs = mount();
    var policy = new ArrayList<>(List.of(pack(fs, "a").checksum()));
    fs.setServerPaks(policy);
    policy.clear();
    assertEquals("a", text(fs, "data"));
    assertThrows(
        IllegalArgumentException.class,
        () -> fs.setServerPaks(java.util.Collections.nCopies(1025, 0)));
    assertEquals("a", text(fs, "data"));
    assertThrows(UnsupportedOperationException.class, () -> fs.packs().clear());
  }

  @Test
  void serverAnnouncementsListLoadedAndReferencedPacks() throws Exception {
    pack("pak0", "maps/a.bsp", "map");
    pack("textures", "textures/a.tga", "pixels");
    Path mod = root.resolve("mymod/modpak.pk3");
    TestZip.create().add("vm/qagame.qvm", "game").write(mod);
    searchPath = SearchPath.mount(root, "mymod");
    var fs = new PureManifest(searchPath);
    int pak0 = pack(fs, "pak0").checksum(), modpak = pack(fs, "modpak").checksum();
    int textures = pack(fs, "textures").checksum();
    assertEquals(modpak + " " + textures + " " + pak0 + " ", fs.loadedPakChecksums());
    assertEquals("modpak textures pak0", fs.loadedPakNames());
    fs.view().read(VirtualPath.of("maps/a.bsp"));
    // Mod packs always count as referenced; baseq3 packs only once read.
    assertEquals(modpak + " " + pak0 + " ", fs.referencedPakChecksums());
    assertEquals("mymod/modpak baseq3/pak0", fs.referencedPakNames());
  }

  @Test
  void markReferencedRecordsThePackWithoutReading() throws Exception {
    pack("pak0", "maps/a.bsp", "map");
    pack("other", "textures/b.tga", "pixels");
    var fs = mount();
    fs.restart(7);
    assertEquals("", fs.referencedPakNames());
    fs.view().markReferenced(VirtualPath.of("maps/a.bsp"));
    fs.view().markReferenced(VirtualPath.of("maps/missing.bsp"));
    assertEquals("baseq3/pak0", fs.referencedPakNames());
  }
}

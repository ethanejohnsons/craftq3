package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class VirtualPathTest {
  @Test
  void retainsCallerSpellingAlongsideCanonicalLookupPath() {
    var path = VirtualPath.of("VM\\CGame.QVM");
    assertEquals("VM\\CGame.QVM", path.spelling());
    assertEquals("vm/cgame.qvm", path.key());
    assertEquals("vm/cgame.qvm", path.toString());
    assertEquals("cgame.qvm", path.fileName());
    assertEquals("qvm", path.extension());
    assertEquals("", VirtualPath.of("maps/readme").extension());
  }

  @Test
  void canonicalIdentityIsConsistentAcrossHashAndSortedMaps() {
    var first = VirtualPath.of("VM\\CGame.QVM");
    var second = VirtualPath.of("vm/cgame.qvm");
    assertEquals(first, second);
    assertEquals(second, first);
    assertEquals(first.key().hashCode(), first.hashCode());
    assertEquals(0, first.compareTo(second));
    assertNotEquals(first, null);
    assertNotEquals(first, first.key());

    for (var map :
        List.of(new HashMap<VirtualPath, Integer>(), new TreeMap<VirtualPath, Integer>())) {
      map.put(first, 1);
      assertEquals(1, map.put(second, 2));
      assertEquals(1, map.size());
      assertEquals(2, map.get(VirtualPath.of("Vm/CgAmE.qVm")));
    }
    var paths = new TreeMap<VirtualPath, Integer>();
    paths.put(VirtualPath.of("z"), 1);
    paths.put(first, 2);
    paths.put(VirtualPath.of("A"), 3);
    assertEquals(
        List.of("a", "vm/cgame.qvm", "z"), paths.keySet().stream().map(VirtualPath::key).toList());
  }

  @Test
  void rejectsUnsafeSpellingsWithTheBrokenRule() {
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.of(null));
    for (String invalid :
        List.of(
            "",
            "a".repeat(256),
            "/root",
            "\\root",
            "root\\",
            "root/",
            "a\\\\b",
            "a//b",
            ".",
            "a\\.\\b",
            "a/../b",
            "..",
            "C:\\x",
            "c:x",
            "file.txt:stream",
            "a\u0000b",
            "a\u001fb",
            "a\u007fb")) {
      assertThrows(IllegalArgumentException.class, () -> VirtualPath.of(invalid), invalid);
      assertTrue(VirtualPath.parse(invalid).isEmpty(), invalid);
    }
    var error = assertThrows(IllegalArgumentException.class, () -> VirtualPath.of("a/../b"));
    assertTrue(error.getMessage().contains(".."));
  }

  @Test
  void allowsSpacesDotsInNamesAndCorpusSpellings() {
    for (String valid :
        List.of(
            "env\\sunset1_up.jpg",
            "sound/weener dog.wav",
            "q36x pakky/readme.txt",
            "..hidden/a",
            "a/b.",
            "a/.b",
            "a".repeat(255)))
      assertEquals(
          valid.replace('\\', '/').toLowerCase(java.util.Locale.ROOT),
          VirtualPath.of(valid).key(),
          valid);
  }

  @Test
  void foldsAsciiOnlyAsTheOriginalEngineCompares() {
    // The original engine lower-cases bytes in the C locale, so only A-Z change; this keeps
    // lookups byte-faithful for the ISO-8859-1 names stored in PK3s.
    var spaces = VirtualPath.of("Textures/\u00c9 Wall.TGA");
    assertEquals("Textures/\u00c9 Wall.TGA", spaces.spelling());
    assertEquals("textures/\u00c9 wall.tga", spaces.key());
    String dotted = "\u0130".repeat(255);
    assertEquals(dotted, VirtualPath.of(dotted).key());
  }

  @Test
  void gameDirectoryIsOneComponentInLookupForm() {
    assertEquals("baseq3", VirtualPath.gameDirectory("BaseQ3"));
    assertEquals("alternate fire", VirtualPath.gameDirectory("Alternate Fire"));
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.gameDirectory("baseq3/mod"));
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.gameDirectory("baseq3\\mod"));
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.gameDirectory(".."));
  }

  @Test
  void directoryKeysNormaliseForListing() {
    assertEquals("", VirtualPath.directoryKey(null));
    assertEquals("", VirtualPath.directoryKey(""));
    assertEquals("", VirtualPath.directoryKey("/"));
    assertEquals("maps/", VirtualPath.directoryKey("MAPS"));
    assertEquals("models/players/", VirtualPath.directoryKey("models\\Players\\"));
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.directoryKey("../x"));
  }

  @Test
  void pakOrderFoldsToUpperCaseLikeTheOriginalComparison() {
    // '_' (0x5f) sorts after the upper-case letters, so zz_ beats zzz; lower-case folding would
    // put it first.
    assertTrue(PathOrder.INSTANCE.compare("zz_maps.pk3", "zzz.pk3") > 0);
    assertTrue(PathOrder.INSTANCE.compare("pak9.pk3", "pak10.pk3") > 0);
    assertEquals(0, PathOrder.INSTANCE.compare("PAK0.pk3", "pak0.PK3"));
    assertEquals(0, PathOrder.INSTANCE.compare("a\\b", "a/b"));
    assertTrue(PathOrder.INSTANCE.compare("a", "ab") < 0);
    // High bytes compare as signed chars, so they sort before ASCII.
    assertTrue(PathOrder.INSTANCE.compare("\u00e9.pk3", "a.pk3") < 0);
  }
}

package dev.bluevista.craftq3.core.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class VirtualPathTest {
  @Test
  void retainsCallerSpellingAlongsideCanonicalLookupPath() {
    var path = new VirtualPath("VM\\CGame.QVM");
    assertEquals("VM\\CGame.QVM", path.requested());
    assertEquals("vm/cgame.qvm", path.value());
    assertEquals("VirtualPath[value=vm/cgame.qvm]", path.toString());
  }

  @Test
  void canonicalIdentityIsConsistentAcrossHashAndSortedMaps() {
    var first = new VirtualPath("VM\\CGame.QVM");
    var second = new VirtualPath("vm/cgame.qvm");
    assertEquals(first, second);
    assertEquals(second, first);
    assertEquals(first.value().hashCode(), first.hashCode());
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals(0, first.compareTo(second));
    assertNotEquals(first, null);
    assertNotEquals(first, first.value());

    for (var map :
        List.of(new HashMap<VirtualPath, Integer>(), new TreeMap<VirtualPath, Integer>())) {
      map.put(first, 1);
      assertEquals(1, map.put(second, 2));
      assertEquals(1, map.size());
      assertEquals(2, map.get(first));
      assertEquals(2, map.get(new VirtualPath("Vm/CgAmE.qVm")));
    }
    var paths = new TreeMap<VirtualPath, Integer>();
    paths.put(new VirtualPath("z"), 1);
    paths.put(first, 2);
    paths.put(new VirtualPath("A"), 3);
    assertEquals(
        List.of("a", "vm/cgame.qvm", "z"),
        paths.keySet().stream().map(VirtualPath::value).toList());
  }

  @Test
  void rejectsUnsafeOriginalSpellingBeforePublishingAPath() {
    assertThrows(IllegalArgumentException.class, () -> new VirtualPath(null));
    for (String invalid :
        List.of(
            "",
            "a".repeat(256),
            "\\root",
            "root\\",
            "a\\\\b",
            "a\\.\\b",
            "a\\..\\b",
            "C:\\x",
            "a\u0000b",
            "a\u001fb",
            "a\u007fb")) {
      assertThrows(IllegalArgumentException.class, () -> new VirtualPath(invalid));
    }
  }

  @Test
  void preservesExistingUnicodeSpacesAndInputLengthValidation() {
    String requested = "\u0130".repeat(255);
    var expanding = new VirtualPath(requested);
    assertEquals(requested, expanding.requested());
    assertEquals("i\u0307".repeat(255), expanding.value());
    var spaces = new VirtualPath("Textures/\u00c9 Wall.TGA");
    assertEquals("Textures/\u00c9 Wall.TGA", spaces.requested());
    assertEquals("textures/\u00e9 wall.tga", spaces.value());
  }

  @Test
  void gameDirectoryKeepsItsCanonicalSingleComponentContract() {
    assertEquals("baseq3", VirtualPath.gameDirectory("BaseQ3"));
    assertEquals("alternate fire", VirtualPath.gameDirectory("Alternate Fire"));
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.gameDirectory("baseq3/mod"));
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.gameDirectory("baseq3\\mod"));
    assertThrows(IllegalArgumentException.class, () -> VirtualPath.gameDirectory(".."));
  }
}

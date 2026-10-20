package dev.bluevista.craftq3.botlib.character;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.script.ScriptSources;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BotCharactersTest {
  private static final String DEFAULTS =
      """
      skill 1 { 3 91.0 4 92.0 5 "default1" }
      skill 4 { 3 94.0 4 95.0 5 "default4" }
      skill 5 { 3 96.0 4 97.0 5 "default5" }
      """;
  private static final String CUSTOM =
      """
      #include "indices.h"
      skill 1 { ID 10 1 10.0 2 "low" 3 1.0 }
      skill 4 { ID 40 1 40.0 2 "medium" 4 44.0 }
      skill 5 { ID 50 1 50.0 2 "high" 3 5.0 }
      """;

  @Test
  void interpolatesFloatAnchorsAfterDefaultFillAndKeepsLowerIntegersAndStrings() {
    try (var sources = sources(CUSTOM);
        var characters = new BotCharacters(sources)) {
      int h = characters.loadCharacter("bots/custom.c", 2);
      assertNotEquals(0, h);
      assertEquals(10, characters.characteristicInteger(h, 0));
      assertEquals(20, characters.characteristicFloat(h, 1));
      assertEquals("low", characters.characteristicString(h, 2));
      assertEquals(32, characters.characteristicFloat(h, 3));
      assertEquals(76, characters.characteristicFloat(h, 4));
      assertEquals("default1", characters.characteristicString(h, 5));
      int upper = characters.loadCharacter("bots/custom.c", 4.5f);
      assertEquals(40, characters.characteristicInteger(upper, 0));
      assertEquals(45, characters.characteristicFloat(upper, 1));
      assertEquals(49.5f, characters.characteristicFloat(upper, 3));
      assertEquals(70.5f, characters.characteristicFloat(upper, 4));
      assertEquals("medium", characters.characteristicString(upper, 2));
      assertEquals(
          characters.loadCharacter("bots/custom.c", 1),
          characters.loadCharacter("botfiles/BOTS/custom.c", -1));
      assertEquals(
          characters.loadCharacter("bots/custom.c", 5),
          characters.loadCharacter("bots/custom.c", 10));
      assertEquals(0, sources.openCount());
    }
  }

  @Test
  void onlyTwoFloatEndpointsInterpolateAndAbsentOrMixedTypesStayAbsent() {
    String mixed =
        "skill 1 { 0 1.0 1 10 2 \"low\" } skill 4 { 0 4 1 40.0 2 4.0 } skill 5 { 0 5.0 1 50.0 2 \"high\" }";
    var diagnostics = new ArrayList<String>();
    try (var sources = sources(mixed);
        var characters = new BotCharacters(sources, diagnostics::add)) {
      int h = characters.loadCharacter("bots/custom.c", 2);
      assertEquals(0, characters.characteristicFloat(h, 0));
      assertEquals(10, characters.characteristicFloat(h, 1));
      assertEquals("low", characters.characteristicString(h, 2));
      int upper = characters.loadCharacter("bots/custom.c", 4.5f);
      assertEquals(4, characters.characteristicFloat(upper, 0));
      assertEquals(45, characters.characteristicFloat(upper, 1));
      assertEquals("", characters.characteristicString(upper, 2));
      assertTrue(diagnostics.stream().anyMatch(text -> text.contains("Uninitialized")));
    }
  }

  @Test
  void fixedAnchorsMissingFilesAndMalformedBlocksUseDefaultFallback() {
    for (String custom :
        List.of(
            "skill 2 { 0 22.0 } skill 3 { 0 33.0 }",
            "skill 1 { 0 -1.0 }",
            "skill 1 { 0 1 0 2 }",
            "skill 1 { 80 42 }",
            "skill 1 { 0 bogus }")) {
      try (var sources = sources(custom);
          var characters = new BotCharacters(sources)) {
        int h = characters.loadCharacter("bots/custom.c", 1);
        assertNotEquals(0, h);
        assertEquals(91, characters.characteristicFloat(h, 3));
        assertEquals(0, characters.characteristicFloat(h, 0));
        assertEquals(0, sources.openCount());
        assertEquals(h, characters.loadCharacter("bots/missing.c", 1));
      }
    }
    try (var sources = new ScriptSources(memory(Map.of()));
        var characters = new BotCharacters(sources)) {
      assertEquals(0, characters.loadCharacter("bots/missing.c", 1));
    }
  }

  @Test
  void selectedFirstBlockWinsAndUnselectedBodyDoesNotNeedCharacteristicSyntax() {
    try (var sources =
            sources("skill 2 { arbitrary \"}\" { tokens } } skill 1 { 0 12 } skill 1 { 0 99 }");
        var characters = new BotCharacters(sources)) {
      int h = characters.loadCharacter("bots/custom.c", 1);
      assertEquals(12, characters.characteristicInteger(h, 0));
    }
  }

  @Test
  void cacheAndReloadModeMatchObservableNativeLifetimeWithoutReusingFreedHandles() {
    try (var sources = sources(CUSTOM);
        var characters = new BotCharacters(sources)) {
      int h = characters.loadCharacter("bots/custom.c", 2);
      characters.free(h);
      assertEquals(h, characters.loadCharacter("bots/custom.c", 2));
      assertEquals(20, characters.characteristicFloat(h, 1));
      characters.setReloadCharacters(true);
      assertEquals(h, characters.loadCharacter("bots/custom.c", 2));
      int first = characters.loadCharacter("bots/custom.c", 1);
      int second = characters.loadCharacter("bots/custom.c", 1);
      assertNotEquals(first, second);
      characters.free(h);
      assertEquals(0, characters.characteristicFloat(h, 1));
      int replacement = characters.loadCharacter("bots/custom.c", 2);
      assertNotEquals(h, replacement);
      assertEquals(20, characters.characteristicFloat(replacement, 1));
      assertEquals(0, characters.characteristicFloat(h, 1));
    }
  }

  @Test
  void numericConversionsBoundsStringsAndErrorsHaveExplicitResults() {
    try (var sources = sources("skill 1 { 0 12.75 1 \"abcdef\" }");
        var characters = new BotCharacters(sources)) {
      int h = characters.loadCharacter("bots/custom.c", 1);
      assertEquals(12, characters.characteristicInteger(h, 0));
      assertEquals(10, characters.characteristicBoundedFloat(h, 0, 0, 10));
      assertEquals(20, characters.characteristicBoundedInteger(h, 0, 20, 30));
      assertEquals(0, characters.characteristicBoundedFloat(h, 0, 20, 10));
      assertEquals(0, characters.characteristicBoundedInteger(h, 0, 20, 10));
      assertEquals(0, characters.characteristicFloat(h, 1));
      assertEquals("", characters.characteristicString(h, 0));
      assertEquals("abc", characters.characteristicString(h, 1, 4));
      assertEquals("", characters.characteristicString(h, 1, 0));
      assertEquals("", characters.characteristicString(h, 1, 1));
      assertEquals(0, characters.characteristicFloat(h, 80));
      assertEquals(0, characters.characteristicInteger(-1, 0));
      assertEquals(0, characters.loadCharacter("../escape", 1));
      assertEquals(0, characters.loadCharacter("bots/custom.c", Float.NaN));
      assertThrows(
          IllegalArgumentException.class,
          () -> characters.characteristicString(h, 1, Integer.MAX_VALUE));
    }
  }

  @Test
  void retainedProfilesAndParserHandlesRemainBounded() {
    try (var sources = sources(CUSTOM);
        var characters = new BotCharacters(sources)) {
      boolean exhausted = false;
      for (int i = 0; i < 100; i++) {
        if (characters.loadCharacter("bots/custom.c", 1 + i * 0.02f) == 0) {
          exhausted = true;
          break;
        }
      }
      assertTrue(exhausted);
      assertEquals(BotCharacters.MAX_HANDLES, characters.cachedCount());
      assertEquals(0, sources.openCount());
    }
  }

  @Test
  void closeDoesNotOwnScriptSourcesAndProfilesCopyTheirValueMap() throws Exception {
    try (var sources = sources(CUSTOM)) {
      var characters = new BotCharacters(sources);
      characters.loadCharacter("bots/custom.c", 1);
      characters.close();
      characters.close();
      assertThrows(IllegalStateException.class, () -> characters.characteristicFloat(1, 0));
      int source = sources.load("bots/custom.c");
      assertTrue(sources.free(source));
    }
    var values = new HashMap<Integer, BotCharacter.Value>();
    values.put(1, new BotCharacter.IntegerValue(3));
    var character = new BotCharacter("test", 1, values);
    values.clear();
    assertEquals(1, character.values().size());
    assertThrows(UnsupportedOperationException.class, () -> character.values().clear());
  }

  private static ScriptSources sources(String custom) {
    return new ScriptSources(
        memory(
            Map.of(
                "botfiles/bots/custom.c",
                custom,
                "botfiles/bots/default_c.c",
                DEFAULTS,
                "botfiles/indices.h",
                "#define ID 0")));
  }

  private static VirtualFileSystem memory(Map<String, String> files) {
    return new VirtualFileSystem() {
      public Optional<Origin> which(VirtualPath path) {
        return files.containsKey(path.value())
            ? Optional.of(new Origin("test", "memory", false))
            : Optional.empty();
      }

      public List<VirtualPath> list(String directory) {
        return files.keySet().stream()
            .filter(path -> path.startsWith(directory))
            .map(VirtualPath::new)
            .toList();
      }

      public List<Origin> searchOrder() {
        return List.of();
      }

      public byte[] read(VirtualPath path) throws NoSuchFileException {
        String text = files.get(path.value());
        if (text == null) throw new NoSuchFileException(path.value());
        return text.getBytes(StandardCharsets.ISO_8859_1);
      }

      public void close() {}
    };
  }
}

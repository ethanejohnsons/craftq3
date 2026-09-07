package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Authored cases from the unchanged CL_GetServerCommand observer's published effects. */
final class ConfigstringCommandsTest {
  @Test
  void ordinaryCsStoresArgsFromTwoButReturnsOriginalTextAndMissingValueClears() {
    var commands = new ConfigstringCommands(Map.of(100, "before", 101, "untouched"));
    String raw = "cs 100  alpha   \"two words\"  omega";
    assertEquals(Optional.of(raw), commands.consume(raw));
    assertEquals("alpha two words omega", commands.strings().get(100));
    assertEquals(Optional.of("cs 100"), commands.consume("cs 100"));
    assertFalse(commands.strings().containsKey(100));
    assertEquals("untouched", commands.strings().get(101));
    commands.consume("cs 100 \"\" tail \"\"");
    assertEquals(" tail ", commands.strings().get(100));
    commands.consume("cs 100 \"\"");
    assertFalse(commands.strings().containsKey(100));
  }

  @Test
  void fragmentsUseOnlyArgvTwoAndPreserveInitialIndexSpelling() {
    var commands = new ConfigstringCommands(Map.of(101, "before"));
    assertEquals(Optional.empty(), commands.consume("bcs0 00101 a b"));
    assertEquals("before", commands.strings().get(101));
    assertEquals(Optional.empty(), commands.consume("bcs1 999 c d"));
    assertEquals("before", commands.strings().get(101));
    assertEquals(Optional.of("cs 00101 \"ace\""), commands.consume("bcs2 777 e f"));
    assertEquals("ace", commands.strings().get(101));
    assertFalse(commands.strings().containsKey(777));
    assertFalse(commands.strings().containsKey(999));
    commands.consume("bcs0 100 \"has space\" ignored");
    commands.consume("bcs1 not-an-index \";still data\" ignored");
    assertEquals(
        Optional.of("cs 100 \"has space;still data!\""), commands.consume("bcs2 -100 ! ignored"));
    assertEquals("has space;still data!", commands.strings().get(100));
  }

  @Test
  void emptyFragmentArgumentsAreValidAndNewStartReplacesAnIncompleteAssembly() {
    var commands = new ConfigstringCommands(Map.of(100, "old", 101, "kept"));
    commands.consume("bcs0 101 abandoned");
    assertEquals(Optional.empty(), commands.consume("bcs0 100"));
    assertEquals(Optional.empty(), commands.consume("bcs1 ignored"));
    assertEquals(Optional.of("cs 100 \"\""), commands.consume("bcs2 ignored"));
    assertFalse(commands.strings().containsKey(100));
    assertEquals("kept", commands.strings().get(101));
  }

  @Test
  void repeatedFinalRetainsClosingQuoteAndNativeTrailingEmptyArgument() {
    var commands = new ConfigstringCommands(Map.of());
    commands.consume("bcs0 100 a");
    assertEquals(Optional.of("cs 100 \"ab\""), commands.consume("bcs2 100 b"));
    assertEquals("ab", commands.strings().get(100));
    String repeated = commands.consume("bcs2 100 c").orElseThrow();
    assertEquals("cs 100 \"ab\"c\"", repeated);
    assertEquals(List.of("cs", "100", "ab", "c", ""), CommandParser.tokenize(repeated).arguments());
    assertEquals("ab c ", commands.strings().get(100));
    var copied = new ConfigstringCommands(commands);
    assertEquals(Optional.of("cs 100 \"ab\"c\"d\""), copied.consume("bcs2 ignored d"));
    assertEquals("ab c d", copied.strings().get(100));
    assertEquals("ab c ", commands.strings().get(100));
  }

  @Test
  void copiedAssemblyAndPublishedMapsRemainIndependentAcrossTransactionalBranches() {
    var input = new LinkedHashMap<>(Map.of(100, "old", 101, "retained"));
    var original = new ConfigstringCommands(input);
    input.put(100, "outside mutation");
    Map<Integer, String> published = original.strings();
    original.consume("bcs0 100 a");
    original.consume("bcs1 999 b");
    var copy = new ConfigstringCommands(original);
    original.consume("bcs2 ignored c");
    copy.consume("bcs1 ignored d");
    copy.consume("bcs2 ignored e");
    assertEquals("abc", original.strings().get(100));
    assertEquals("abde", copy.strings().get(100));
    assertEquals("old", published.get(100));
    assertEquals("retained", copy.strings().get(101));
    assertThrows(UnsupportedOperationException.class, () -> published.put(1, "mutation"));
  }

  @Test
  void assemblyBoundsIncludeOriginalPrefixClosingQuoteAndTerminatingNul() {
    // Native observer indices and all 23 measured near-limit cases; split into wire-sized chunks.
    for (String token : List.of("50", "100", "1023", "000000100")) {
      int prefix = ("cs " + token + " \"").length();
      for (int length = 8192 - prefix - 3; length < 8192 - prefix + 2; length++) {
        int count = length;
        if (prefix + length + 1 < 8192) {
          var commands = assemble(token, count, true);
          assertEquals("a".repeat(length), commands.strings().get(Integer.parseInt(token)));
        } else assertThrows(IllegalArgumentException.class, () -> assemble(token, count, true));
      }
    }
    for (int count : List.of(8182, 8183)) {
      var commands = assemble("100", count, false);
      assertTrue(commands.strings().isEmpty());
    }
    assertThrows(IllegalArgumentException.class, () -> assemble("100", 8184, false));
  }

  @Test
  void failedIntermediateOrFinalAppendDoesNotChangePendingAssembly() {
    for (String failed : List.of("bcs1 ignored xxx", "bcs2 ignored xx")) {
      var commands = assemble("100", 8181, false);
      assertThrows(IllegalArgumentException.class, () -> commands.consume(failed));
      assertTrue(commands.strings().isEmpty());
      String expected = "a".repeat(8181) + "b";
      assertEquals(Optional.of("cs 100 \"" + expected + "\""), commands.consume("bcs2 ignored b"));
      assertEquals(expected, commands.strings().get(100));
    }
  }

  @Test
  void failedGamestateCommitPreservesStringsAndAssemblyForALaterValidCommit() {
    var commands = new ConfigstringCommands(Map.of(0, "a".repeat(8000), 1, "b".repeat(7988)));
    var before = commands.strings();
    commands.consume("bcs0 2 hello");
    assertThrows(IllegalArgumentException.class, () -> commands.consume("bcs2 ignored world"));
    assertSame(before, commands.strings());
    assertThrows(
        IllegalArgumentException.class, () -> commands.consume("cs 2 another-too-large-value"));
    assertSame(before, commands.strings());
    commands.consume("cs 1");
    assertEquals(Optional.of("cs 2 \"hello!\""), commands.consume("bcs2 ignored !"));
    assertEquals("hello!", commands.strings().get(2));
    assertFalse(commands.strings().containsKey(1));
    assertEquals(7988, before.get(1).length());
  }

  @Test
  void unrelatedTextRemainsInertAndDoesNotDiscardPendingFragments() {
    var commands = new ConfigstringCommands(Map.of(7, "unchanged"));
    commands.consume("bcs0 100 a");
    for (String text :
        List.of(
            "",
            "print \"cs 7 injected; quit\"",
            "map_restart",
            "disconnect reason",
            "CS 7 ignored")) assertEquals(Optional.of(text), commands.consume(text));
    assertEquals("unchanged", commands.strings().get(7));
    assertEquals(Optional.of("cs 100 \"ab\""), commands.consume("bcs2 ignored b"));
    assertEquals("ab", commands.strings().get(100));
  }

  @Test
  void invalidInputsFailWithoutPublishingPartialState() {
    assertThrows(IllegalArgumentException.class, () -> new ConfigstringCommands(Map.of(-1, "bad")));
    assertThrows(
        IllegalArgumentException.class, () -> new ConfigstringCommands(Map.of(1024, "bad")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ConfigstringCommands(Map.of(100, "a".repeat(8192))));
    var commands = new ConfigstringCommands(Map.of(100, "old"));
    var original = commands.strings();
    assertThrows(IllegalArgumentException.class, () -> commands.consume("bcs1 100 orphan"));
    assertThrows(IllegalArgumentException.class, () -> commands.consume("bcs2 100 orphan"));
    commands.consume("bcs0 100 a");
    for (String text :
        List.of(
            "cs -1 bad",
            "cs 1024 bad",
            "cs 100 bad\0data",
            "cs 100 \u0100",
            "bcs0 1024 abandoned",
            "bcs0 100 " + "x".repeat(8192))) {
      assertThrows(IllegalArgumentException.class, () -> commands.consume(text));
      assertSame(original, commands.strings());
    }
    assertEquals(Optional.of("cs 100 \"ab\""), commands.consume("bcs2 ignored b"));
    assertEquals("ab", commands.strings().get(100));
  }

  private static ConfigstringCommands assemble(String index, int count, boolean complete) {
    var commands = new ConfigstringCommands(Map.of());
    int remaining = count;
    boolean first = true;
    while (remaining > 900) {
      assertEquals(
          Optional.empty(),
          commands.consume(
              (first ? "bcs0" : "bcs1") + " " + index + " \"" + "a".repeat(900) + "\""));
      first = false;
      remaining -= 900;
    }
    String operation = complete ? "bcs2" : first ? "bcs0" : "bcs1";
    var result = commands.consume(operation + " " + index + " \"" + "a".repeat(remaining) + "\"");
    assertEquals(complete, result.isPresent());
    return commands;
  }
}

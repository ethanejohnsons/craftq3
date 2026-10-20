package dev.bluevista.craftq3.botlib.chat;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.chat.ChatLibrary.Synonym;
import dev.bluevista.craftq3.botlib.chat.ChatLibrary.SynonymGroup;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ChatSynonymsTest {
  @Test
  void groupsCascadeInSourceOrderAndRetainTheSelectedSpelling() {
    var groups = List.of(group(1, "red", "rose"), group(1, "rose", "pink"));
    assertEquals("pink", apply("red", groups, true));
    assertEquals("rose", apply("pink", groups, false));
    assertEquals("RED", apply("RED", groups, false));
    assertEquals("Pink", apply("Pink", groups, true));
  }

  @Test
  void scansAsciiCaseInsensitivelyWithTheObservedDelimiterAndSpacingRules() {
    assertEquals("Z one Z one", ChatSynonyms.replaceWords("one one one one", "ONE", "Z"));
    assertEquals("Z  Z  Z", ChatSynonyms.replaceWords("one  one  one", "one", "Z"));
    assertEquals("Z   one   Z", ChatSynonyms.replaceWords("one   one   one", "one", "Z"));
    assertEquals(" one", ChatSynonyms.replaceWords(" one", "one", "Z"));
    assertEquals("  Z", ChatSynonyms.replaceWords("  one", "one", "Z"));
    assertEquals("x.Z", ChatSynonyms.replaceWords("x.ONE", "one", "Z"));
    assertEquals(
        "one? one; one: one\t", ChatSynonyms.replaceWords("one? one; one: one\t", "one", "Z"));
  }

  @Test
  void protectsExistingSelectedPhrasesUsingItsOwnTokenWalk() {
    assertEquals("red end", ChatSynonyms.replaceWords("red end", "red", "red end"));
    assertEquals("red end red end", ChatSynonyms.replaceWords("red red end", "red", "red end"));
    assertEquals(
        "red end  red end end", ChatSynonyms.replaceWords("red  red end", "red", "red end"));
    assertEquals("heLLo  HELLO", ChatSynonyms.replaceWords("heLLo  heLLo", "hello", "HELLO"));
    assertEquals("not  not.", ChatSynonyms.replaceWords("will not  WILL NOT.", "will not", "not"));
  }

  @Test
  void normalizesEveryNoncanonicalEntryInOrderAndLeavesProtectionMarkers() {
    var groups =
        List.of(
            new SynonymGroup(
                1, List.of(new Synonym("one", 1), new Synonym("TWO", 1), new Synonym("three", 1))));
    assertEquals("one two one Two one", apply("Two two TWO Two two", groups, false));
    assertEquals("~TWO one", apply("~TWO TWO", groups, false));
    assertEquals("one One ONE", apply("one One ONE", groups, false));
  }

  @Test
  void eligibleGroupsConsumeOneDrawEvenWithoutMatchesOrPositiveWeight() {
    var groups =
        List.of(
            group(1, "red", "rose"),
            group(2, "one", "two"),
            new SynonymGroup(1, List.of(new Synonym("empty", 0))));
    int[] calls = {0};
    assertEquals(
        "absent",
        ChatSynonyms.apply(
            "absent",
            groups,
            1,
            true,
            () -> {
              calls[0]++;
              return 0.5;
            }));
    assertEquals(2, calls[0]);
    assertEquals("red", ChatSynonyms.apply("red", groups, 1, true, () -> 0));
    assertEquals(
        "two",
        ChatSynonyms.apply(
            "two",
            groups,
            1,
            false,
            () -> {
              throw new AssertionError("Normalization must not draw");
            }));
  }

  @Test
  void rejectsExpandedMessagesAndNonfiniteWeightSums() {
    assertThrows(
        IllegalStateException.class,
        () -> ChatSynonyms.replaceWords("a ".repeat(127), "a", "long"));
    var group =
        new SynonymGroup(
            1, List.of(new Synonym("a", Float.MAX_VALUE), new Synonym("b", Float.MAX_VALUE)));
    assertThrows(IllegalStateException.class, () -> apply("a", List.of(group), true));
  }

  private static SynonymGroup group(int context, String first, String second) {
    return new SynonymGroup(context, List.of(new Synonym(first, 1), new Synonym(second, 1)));
  }

  private static String apply(String text, List<SynonymGroup> groups, boolean weighted) {
    return ChatSynonyms.apply(text, groups, 1, weighted, () -> 16384 / 32767.0f);
  }
}

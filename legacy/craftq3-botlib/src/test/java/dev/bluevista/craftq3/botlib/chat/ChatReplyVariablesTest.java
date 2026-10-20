package dev.bluevista.craftq3.botlib.chat;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.chat.ChatLibrary.Synonym;
import dev.bluevista.craftq3.botlib.chat.ChatLibrary.SynonymGroup;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ChatReplyVariablesTest {
  @Test
  void replacesAdjacentAndLeadingAliasesUsingAsymmetricBoundaries() {
    var groups = List.of(group("hello", "hi"));
    assertEquals(" hello hello  hello", ChatReplyVariables.normalize(" hi hi  hi", groups, 1));
    assertEquals("\thello x\thello", ChatReplyVariables.normalize("\thi x\thi", groups, 1));
    assertEquals("x.hi hi? hi\t ~hi", ChatReplyVariables.normalize("x.hi hi? hi\t ~hi", groups, 1));
    assertEquals("hello. hello, hello!", ChatReplyVariables.normalize("hi. hi, hi!", groups, 1));
    assertEquals("HELLO hello", ChatReplyVariables.normalize("HELLO HI", groups, 1));
    assertEquals("hi", ChatReplyVariables.normalize("hi", groups, 2));
  }

  @Test
  void visitsInsertedLaterWordsAndStopsGroupsAfterEachAliasReplacement() {
    var groups = List.of(group("z", "a"), group("b a", "c"));
    assertEquals("b z", ChatReplyVariables.normalize("c", groups, 1));
    groups = List.of(group("a", "b"), group("b", "c"));
    assertEquals("a b", ChatReplyVariables.normalize("b c", groups, 1));
    groups = List.of(group("first", "x"), group("second", "x"));
    assertEquals("first", ChatReplyVariables.normalize("x", groups, 1));
  }

  @Test
  void canonicalEntriesProtectOnlyTheirOwnGroupAndPreserveCase() {
    var groups = List.of(group("x", "a"), group("y", "x"));
    assertEquals("x y", ChatReplyVariables.normalize("a x", groups, 1));
    groups = List.of(group("a b", "a"));
    assertEquals("a b  a b", ChatReplyVariables.normalize("a  a b", groups, 1));
    assertEquals("A B", ChatReplyVariables.normalize("A B", groups, 1));
  }

  @Test
  void recursivelyGrowingAliasesFailWithinTheOutputBound() {
    var groups = List.of(group("a b", "a", "b"));
    assertThrows(IllegalStateException.class, () -> ChatReplyVariables.normalize("a", groups, 1));
  }

  private static SynonymGroup group(String... words) {
    return new SynonymGroup(1, Arrays.stream(words).map(word -> new Synonym(word, 1)).toList());
  }
}

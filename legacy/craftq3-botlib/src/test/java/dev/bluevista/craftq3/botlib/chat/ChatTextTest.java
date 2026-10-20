package dev.bluevista.craftq3.botlib.chat;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ChatTextTest {
  @Test
  void ordinaryWhitespaceAndSelectedPunctuationNormalize() {
    assertEquals("", BotChat.unifyWhiteSpaces(" \t\n!"));
    assertEquals("a b c", BotChat.unifyWhiteSpaces("a!b;c"));
    assertEquals("don't (x), y?", BotChat.unifyWhiteSpaces("don't (x), y?"));
    assertEquals("a b", BotChat.unifyWhiteSpaces("a  b"));
    assertEquals("a", BotChat.unifyWhiteSpaces("a \t\n"));
  }

  @Test
  void removedRunsLeaveObservedFollowingSpansUntouched() {
    assertEquals("a  b", BotChat.unifyWhiteSpaces("  a  b"));
    assertEquals("a!b c d e", BotChat.unifyWhiteSpaces("  a!b!c!d!e"));
    assertEquals("a!b!c d e", BotChat.unifyWhiteSpaces("    a!b!c!d!e"));
    assertEquals("a b!c", BotChat.unifyWhiteSpaces(" a\t\t b!c "));
    assertEquals("a ", BotChat.unifyWhiteSpaces(";!! !;a "));
  }

  @Test
  void highBytesAreWhitespaceAndNulTerminatesTheInput() {
    assertEquals("a b", BotChat.unifyWhiteSpaces("a\u007fb"));
    assertEquals("a b", BotChat.unifyWhiteSpaces("a\u00ffb"));
    assertEquals("a b", BotChat.unifyWhiteSpaces("a\u0080b"));
    assertEquals("a", BotChat.unifyWhiteSpaces("a\0b\u0100"));
    assertThrows(IllegalArgumentException.class, () -> BotChat.unifyWhiteSpaces("a\u0100b"));
    assertThrows(
        IllegalArgumentException.class, () -> BotChat.unifyWhiteSpaces("x".repeat(1_048_577)));
  }
}

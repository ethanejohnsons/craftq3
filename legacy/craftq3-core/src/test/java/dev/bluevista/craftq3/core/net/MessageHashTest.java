package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MessageHashTest {
  @Test
  void observedByteHashValuesAndFiltering() {
    assertEquals(0, MessageHash.key("", 32));
    assertEquals(11548, MessageHash.key("a", 32));
    assertEquals(7728, MessageHash.key("A", 32));
    assertEquals(23313, MessageHash.key("ab", 32));
    assertEquals(64439, MessageHash.key("hello", 32));
    for (String text : new String[] {"%", ".", "\u0080", "\u00ff"})
      assertEquals(5479, MessageHash.key(text, 32));
    assertEquals(15111, MessageHash.key("\u007f", 32));
  }

  @Test
  void nativeLimitsNulAndKeyComposition() {
    assertEquals(MessageHash.key("ab", 32), MessageHash.key("ab\0tail", 32));
    assertEquals(MessageHash.key("ab", 32), MessageHash.key("abc", 2));
    assertEquals(0, MessageHash.key("ignored", 0));
    assertEquals(0x12345678 ^ 65 ^ 64439, MessageHash.userCommandKey(0x12345678, 65, "hello"));
    assertEquals(
        MessageHash.userCommandKey(3, 5, "a".repeat(32)),
        MessageHash.userCommandKey(3, 5, "a".repeat(32) + "tail"));
  }

  @Test
  void boundsAndConsumedByteDomainAreChecked() {
    assertThrows(IllegalArgumentException.class, () -> MessageHash.key("a", -1));
    assertThrows(IllegalArgumentException.class, () -> MessageHash.key("a", 16385));
    assertThrows(IllegalArgumentException.class, () -> MessageHash.key("\u2603", 32));
    assertEquals(11548, MessageHash.key("a\u2603", 1));
    assertEquals(11548, MessageHash.key("a\0\u2603", 32));
  }
}

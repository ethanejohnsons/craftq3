package dev.bluevista.craftq3.botlib.chat;

import static dev.bluevista.craftq3.botlib.chat.ChatLibrary.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.botlib.script.SourceLocation;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMatcherTest {
  @Test
  void literalRulesUseContextAsciiCaseFoldingAndFirstMatchWithWholeTextAnchoring() {
    var matcher = new ChatMatcher(List.of(rule(1, 10, text("hello")), rule(1, 20, text("hello"))));
    var bytes = buffer();
    assertTrue(matcher.find("HELLO", 1, bytes, 0));
    assertEquals(10, bytes.getInt(256));
    assertEquals(0, bytes.get(255));
    assertEquals((byte) 0xff, bytes.get(264));
    assertEquals((byte) 0xab, bytes.get(265));
    assertEquals(0xabababab, bytes.getInt(268));
    bytes = buffer();
    assertFalse(matcher.find("hello ", 1, bytes, 0));
    assertEquals(0xabababab, bytes.getInt(256));
    bytes = buffer();
    assertFalse(matcher.find("hello", 0, bytes, 0));
    assertEquals((byte) 0xab, bytes.get(264));
  }

  @Test
  void orderedAlternativesAndSequentialCapturesDoNotBacktrack() {
    var matcher =
        new ChatMatcher(List.of(rule(1, 10, new Capture(0), text("a", "b"), new Capture(1))));
    var bytes = buffer();
    assertTrue(matcher.find("xxbxxaxx", 1, bytes, 0));
    assertEquals("xxbxx", ChatMatcher.variable(bytes, 0, 0, 256));
    assertEquals("xx", ChatMatcher.variable(bytes, 0, 1, 256));
    assertEquals("xxb", ChatMatcher.variable(bytes, 0, 0, 4));
    assertEquals("", ChatMatcher.variable(bytes, 0, 2, 256));
    matcher = new ChatMatcher(List.of(rule(1, 10, new Capture(0), text("a"), text("end"))));
    assertFalse(matcher.find("xxaFAILaend", 1, bytes, 0));
    assertEquals(2, bytes.getInt(268));
    matcher = new ChatMatcher(List.of(rule(1, 10, text("a", "ab"), text("b"))));
    assertFalse(matcher.find("abb", 1, bytes, 0));
  }

  @Test
  void failedRulesRetainPartialLengthsAndResetOnlyCaptureOffsets() {
    var matcher =
        new ChatMatcher(
            List.of(
                rule(1, 10, new Capture(0), text("a"), text("end")),
                rule(1, 20, new Capture(1), text("b"), text("end"))));
    var bytes = buffer();
    assertFalse(matcher.find("xaNO", 1, bytes, 0));
    assertEquals((byte) 255, bytes.get(264));
    assertEquals(1, bytes.getInt(268));
    assertEquals(0, bytes.get(272));
    assertEquals(0xabababab, bytes.getInt(276));
    assertEquals(0xabababab, bytes.getInt(256));
    assertEquals((byte) 0xab, bytes.get(265));
  }

  @Test
  void emptyLiteralsPreservePendingCapturesAndRepeatedVariablesUseLastCapture() {
    var matcher =
        new ChatMatcher(List.of(rule(1, 10, new Capture(0), text(""), text("a"), new Capture(0))));
    var bytes = buffer();
    assertTrue(matcher.find("xxatail", 1, bytes, 0));
    assertEquals("tail", ChatMatcher.variable(bytes, 0, 0, 256));
    matcher = new ChatMatcher(List.of(rule(1, 10, text("start"), new Capture(0))));
    assertTrue(matcher.find("start", 1, bytes, 0));
    assertEquals("", ChatMatcher.variable(bytes, 0, 0, 256));
  }

  @Test
  void recordBoundsCursorOrderAndBudgetFailureAreTransactional() {
    var bytes = ByteBuffer.allocate(400).order(ByteOrder.BIG_ENDIAN);
    Arrays.fill(bytes.array(), (byte) 0xab);
    bytes.position(20);
    var matcher = new ChatMatcher(List.of(rule(1, 10, text("hello"))));
    assertTrue(matcher.find("hello", 1, bytes, 8));
    assertEquals(20, bytes.position());
    assertEquals(ByteOrder.BIG_ENDIAN, bytes.order());
    assertEquals((byte) 0xab, bytes.get(7));
    assertEquals((byte) 0xab, bytes.get(336));
    byte[] before = bytes.array().clone();
    assertThrows(
        IllegalStateException.class,
        () ->
            new ChatMatcher(List.of(rule(1, 10, text("longliteral"))), 2)
                .find("longliteral", 1, bytes, 8));
    assertArrayEquals(before, bytes.array());
    assertThrows(IllegalArgumentException.class, () -> matcher.find("hello", 1, bytes, 100));
    assertThrows(IllegalArgumentException.class, () -> ChatMatcher.variable(bytes, 8, 8, 256));
    assertThrows(IllegalArgumentException.class, () -> matcher.find("\u2603", 1, bytes, 8));
    assertArrayEquals(before, bytes.array());
  }

  @Test
  void textTruncationAndHighCaptureOffsetsStayWithinOwnedRecord() {
    var matcher = new ChatMatcher(List.of());
    var bytes = buffer();
    assertFalse(matcher.find("x".repeat(400), 0, bytes, 0));
    assertEquals(0, bytes.get(255));
    assertEquals('x', bytes.get(254));
    matcher = new ChatMatcher(List.of(rule(1, 10, new Capture(0), text("a"), new Capture(1))));
    assertTrue(matcher.find("z".repeat(140) + "atail", 1, bytes, 0));
    assertEquals("tail", ChatMatcher.variable(bytes, 0, 1, 256));
    bytes.putInt(276, 1000);
    assertThrows(IllegalArgumentException.class, () -> ChatMatcher.variable(bytes, 0, 1, 256));
  }

  private static ByteBuffer buffer() {
    var bytes = ByteBuffer.allocate(ChatMatcher.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    Arrays.fill(bytes.array(), (byte) 0xab);
    return bytes;
  }

  private static Alternatives text(String... alternatives) {
    return new Alternatives(List.of(alternatives));
  }

  private static MatchRule rule(int context, int type, MatchPart... parts) {
    return new MatchRule(context, List.of(parts), type, 7, new SourceLocation("authored", 1, 1));
  }
}

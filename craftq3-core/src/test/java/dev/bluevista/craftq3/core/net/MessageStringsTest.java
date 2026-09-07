package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class MessageStringsTest {
  @Test
  void nativeWireExampleFiltersPercentAndHighBytes() {
    var writer = new MessageWriter();
    writer.stringValue("hello%world");
    assertEquals(89, writer.bitPosition());
    assertEquals("1c0c1373967ec6ec11e62501", HexFormat.of().formatHex(writer.bytes()));
    assertEquals("hello.world", new MessageReader(writer.bytes()).stringValue());
    writer = new MessageWriter();
    writer.stringValue("\1\t\u001f\u007f\u0080\u00ff");
    assertEquals("\1\t\u001f\u007f..", new MessageReader(writer.bytes()).stringValue());
  }

  @Test
  void readAlsoFiltersRawSymbolsAndLineReadsConsumeOnlyTheNewline() {
    var writer = new MessageWriter();
    for (char c : "a%\u00ff\r\nb\0".toCharArray()) writer.byteValue(c);
    var reader = new MessageReader(writer.bytes());
    assertEquals("a..\r", reader.stringLine());
    assertEquals("b", reader.stringValue());
    assertEquals(writer.bitPosition(), reader.bitPosition());
  }

  @Test
  void embeddedNulNullAndOverlongWritesFollowNativeEmptyStringBehavior() {
    var writer = new MessageWriter();
    writer.stringValue("first\0ignored");
    writer.stringValue(null);
    writer.stringValue("x".repeat(1024));
    writer.bigStringValue("y".repeat(8192));
    writer.intValue(73);
    var reader = new MessageReader(writer.bytes());
    assertEquals("first", reader.stringValue());
    assertEquals("", reader.stringValue());
    assertEquals("", reader.stringValue());
    assertEquals("", reader.bigStringValue());
    assertEquals(73, reader.intValue());
  }

  @Test
  void exactCapacityRoundTripsAndReadOverflowConsumesOneDiscardedSymbol() {
    for (boolean big : new boolean[] {false, true}) {
      int count = big ? 8191 : 1023;
      var writer = new MessageWriter();
      writer.bits(5, 3);
      String text = "a".repeat(count);
      if (big) writer.bigStringValue(text);
      else writer.stringValue(text);
      var reader = new MessageReader(writer.bytes());
      assertEquals(5, reader.bits(3));
      assertEquals(text, big ? reader.bigStringValue() : reader.stringValue());
      assertEquals(writer.bitPosition(), reader.bitPosition());
    }
    var writer = new MessageWriter();
    for (int i = 0; i < 1025; i++) writer.byteValue('a');
    writer.byteValue(0);
    var reader = new MessageReader(writer.bytes());
    assertEquals("a".repeat(1023), reader.stringValue());
    assertEquals('a', reader.byteValue());
    assertEquals(0, reader.byteValue());
  }

  @Test
  void failedReadAndWriteRestoreTheirMessageBoundaries() {
    var writer = new MessageWriter(3);
    writer.bits(5, 3);
    byte[] before = writer.bytes();
    assertThrows(IllegalArgumentException.class, () -> writer.stringValue("too long"));
    assertArrayEquals(before, writer.bytes());
    assertEquals(3, writer.bitPosition());
    assertThrows(IllegalArgumentException.class, () -> writer.stringValue("a\u0100"));
    assertArrayEquals(before, writer.bytes());

    var source = new MessageWriter();
    source.bits(5, 3);
    source.byteValue('a');
    var reader = new MessageReader(source.bytes(), source.bitPosition());
    reader.bits(3);
    assertThrows(IllegalArgumentException.class, reader::stringValue);
    assertEquals(3, reader.bitPosition());
  }
}

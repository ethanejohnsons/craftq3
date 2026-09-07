package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.ClientMessageCodec.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientMessageCodecTest {
  @Test
  void batchesCommandsWithAcknowledgedServerKeyAndBothDeltaModes() {
    for (boolean delta : new boolean[] {false, true}) {
      var before = command(1000, 21);
      var middle = command(1016, 42);
      var after = command(1032, 63);
      var message =
          new Message(
              123,
              432,
              87,
              List.of(new Command(4, "say 50% \u0080")),
              new Movement(delta, List.of(before, middle, after)));
      var writer = new MessageWriter();
      ClientMessageCodec.write(writer, message, 0x7f03ff02, "server key");
      var lookup = new AtomicInteger();
      var reader = new MessageReader(writer.bytes());
      var decoded =
          ClientMessageCodec.read(
              reader,
              0x7f03ff02,
              sequence -> {
                lookup.set(sequence);
                return "server key";
              });
      assertEquals(87, lookup.get());
      assertEquals(123, decoded.serverId());
      assertEquals(432, decoded.messageAcknowledge());
      assertEquals(87, decoded.serverCommandAcknowledge());
      assertEquals(List.of(new Command(4, "say 50. .")), decoded.commands());
      assertEquals(delta, decoded.movement().requestDelta());
      assertArrayEquals(before, decoded.movement().commands().get(0));
      assertArrayEquals(middle, decoded.movement().commands().get(1));
      assertArrayEquals(after, decoded.movement().commands().get(2));
      assertEquals(writer.bitPosition(), reader.bitPosition());
    }
  }

  @Test
  void reliableOnlyNeedsNoMovementHistoryAndBodyBoundaryExcludesEof() {
    var message = new Message(0, 0, 0, List.of(new Command(1, "userinfo value")), null);
    var body = new MessageWriter();
    ClientMessageCodec.writeBody(body, message, 0, null);
    var truncated = new MessageReader(body.bytes(), body.bitPosition());
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientMessageCodec.read(truncated, 0, ignored -> null));
    assertEquals(0, truncated.bitPosition());
    body.byteValue(5);
    var decoded =
        ClientMessageCodec.read(
            new MessageReader(body.bytes()),
            0,
            ignored -> {
              throw new AssertionError("No movement lookup expected");
            });
    assertEquals(message, decoded);
  }

  @Test
  void missingKeysEveryTruncatedBitAndCapacityFailuresAreTransactional() {
    var message =
        new Message(
            17,
            300,
            99,
            List.of(new Command(1, "hello")),
            new Movement(true, List.of(command(1234, 55))));
    var writer = new MessageWriter();
    writer.bits(7, 3);
    ClientMessageCodec.write(writer, message, 123, "text");
    var missing = new MessageReader(writer.bytes());
    missing.bits(3);
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientMessageCodec.read(missing, 123, ignored -> null));
    assertEquals(3, missing.bitPosition());
    for (int end = 3; end < writer.bitPosition(); end++) {
      var reader = new MessageReader(writer.bytes(), end);
      reader.bits(3);
      assertThrows(
          IllegalArgumentException.class,
          () -> ClientMessageCodec.read(reader, 123, ignored -> "text"));
      assertEquals(3, reader.bitPosition());
    }
    var limited = new MessageWriter(8);
    limited.bits(5, 3);
    var prefix = limited.bytes();
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientMessageCodec.write(limited, message, 123, "text"));
    assertEquals(3, limited.bitPosition());
    assertArrayEquals(prefix, limited.bytes());
  }

  @Test
  void invalidCountsUnsupportedOperationsAndOperationsAfterMovementFail() {
    for (int count : new int[] {0, 33, 255}) {
      var invalid = header();
      invalid.byteValue(2);
      invalid.byteValue(count);
      invalid.byteValue(5);
      assertThrows(IllegalArgumentException.class, () -> read(invalid));
    }
    for (int opcode : new int[] {0, 6, 7, 255}) {
      var invalid = header();
      invalid.byteValue(opcode);
      invalid.byteValue(5);
      assertThrows(IllegalArgumentException.class, () -> read(invalid));
    }
    var afterMove = new MessageWriter();
    ClientMessageCodec.writeBody(
        afterMove,
        new Message(0, 0, 0, List.of(), new Movement(true, List.of(command(1, 1)))),
        0,
        "");
    afterMove.byteValue(4);
    afterMove.intValue(1);
    afterMove.stringValue("late");
    afterMove.byteValue(5);
    assertThrows(IllegalArgumentException.class, () -> read(afterMove));
    var commands = header();
    for (int i = 0; i < 65; i++) {
      commands.byteValue(4);
      commands.intValue(i);
      commands.stringValue("x");
    }
    commands.byteValue(5);
    assertThrows(IllegalArgumentException.class, () -> read(commands));
    var noops = header();
    for (int i = 0; i < 129; i++) noops.byteValue(1);
    noops.byteValue(5);
    assertThrows(IllegalArgumentException.class, () -> read(noops));
  }

  @Test
  void ownershipAndPublicCountStringAndStateBoundsAreEnforced() {
    byte[] command = command(1, 3);
    var source = new ArrayList<byte[]>(List.of(command));
    var movement = new Movement(false, source);
    Arrays.fill(command, (byte) 0);
    source.clear();
    movement.commands().getFirst()[0] = 99;
    assertArrayEquals(command(1, 3), movement.commands().getFirst());
    assertThrows(UnsupportedOperationException.class, () -> movement.commands().clear());
    assertThrows(IllegalArgumentException.class, () -> new Movement(true, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Movement(true, List.of(new byte[23])));
    for (int i = 0; i < 32; i++) source.add(command(1, 1));
    assertEquals(32, new Movement(true, source).commands().size());
    source.add(command(1, 1));
    assertThrows(IllegalArgumentException.class, () -> new Movement(true, source));
    assertThrows(IllegalArgumentException.class, () -> new Command(1, "x".repeat(1024)));
    assertThrows(IllegalArgumentException.class, () -> new Command(1, "a\0b"));
    assertThrows(IllegalArgumentException.class, () -> new Command(1, "\u2603"));
    var reliable = new ArrayList<Command>();
    for (int i = 0; i < 65; i++) reliable.add(new Command(i, "x"));
    assertThrows(IllegalArgumentException.class, () -> new Message(0, 0, 0, reliable, null));
  }

  private static Message read(MessageWriter writer) {
    return ClientMessageCodec.read(new MessageReader(writer.bytes()), 0, ignored -> "");
  }

  private static MessageWriter header() {
    var writer = new MessageWriter();
    writer.intValue(0);
    writer.intValue(0);
    writer.intValue(0);
    return writer;
  }

  private static byte[] command(int time, int pitch) {
    byte[] bytes = new byte[24];
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, time).putInt(4, pitch);
    bytes[20] = 2;
    bytes[21] = 127;
    return bytes;
  }
}

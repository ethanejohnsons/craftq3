package dev.bluevista.craftq3.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.delta.EntityDeltaCodec;
import dev.bluevista.craftq3.core.net.delta.PlayerDeltaCodec;
import dev.bluevista.craftq3.core.net.delta.StateFields;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class StateDeltaCodecTest {
  @Test
  void completeEntityFixtureMatchesCapturedNativeBytesAtNonzeroBitOffset() {
    byte[] target = entityFixture();
    var writer = new MessageWriter();
    writer.bits(5, 3);
    assertTrue(EntityDeltaCodec.write(writer, null, target, true));
    assertEquals(148, writer.bitPosition());
    assertEquals("2dd4cb3f67b2bd52cd17f02400000000c02409", hex(writer.bytes()));
    var reader = new MessageReader(writer.bytes(), writer.bitPosition());
    assertEquals(5, reader.bits(3));
    var update = EntityDeltaCodec.read(reader, null);
    assertEquals(17, update.number());
    assertFalse(update.removed());
    assertFalse(update.endOfList());
    byte[] expected = target.clone();
    word(expected, 28, 0); // A changed negative zero uses zero encoding.
    assertArrayEquals(expected, update.state());
    assertEquals(writer.bitPosition(), reader.bitPosition());
  }

  @Test
  void playerFixtureMatchesCapturedNativeArraysAndSignedFields() {
    byte[] target = playerFixture();
    var writer = new MessageWriter();
    writer.bits(5, 3);
    PlayerDeltaCodec.write(writer, null, target);
    assertEquals(177, writer.bitPosition());
    assertEquals("1d1aefa480d1930000987c4b92fac1d3dbeafcc5a1fa01", hex(writer.bytes()));
    var reader = new MessageReader(writer.bytes(), writer.bitPosition());
    reader.bits(3);
    byte[] expected = target.clone();
    word(expected, 136, 0); // externalEventTime is never transmitted.
    assertArrayEquals(expected, PlayerDeltaCodec.read(reader, null));
    assertEquals(writer.bitPosition(), reader.bitPosition());
  }

  @Test
  void entityOmissionForcedBaselineRemovalAndListTerminatorAreDistinct() {
    byte[] baseline = entityFixture();
    var writer = new MessageWriter();
    assertFalse(EntityDeltaCodec.write(writer, null, null, true));
    assertFalse(EntityDeltaCodec.write(writer, baseline, baseline, false));
    assertEquals(0, writer.bitPosition());
    assertTrue(EntityDeltaCodec.write(writer, baseline, baseline, true));
    assertTrue(EntityDeltaCodec.write(writer, baseline, null, false));
    EntityDeltaCodec.writeEnd(writer);
    var reader = new MessageReader(writer.bytes(), writer.bitPosition());
    assertArrayEquals(baseline, EntityDeltaCodec.read(reader, baseline).state());
    var removed = EntityDeltaCodec.read(reader, baseline);
    assertTrue(removed.removed());
    assertEquals(17, removed.number());
    byte[] cleared = new byte[208];
    word(cleared, 0, 1023);
    assertArrayEquals(cleared, removed.state());
    var end = EntityDeltaCodec.read(reader, baseline);
    assertTrue(end.endOfList());
    assertFalse(end.removed());
    assertArrayEquals(cleared, end.state());
    assertEquals(0, reader.remainingBits());
  }

  @Test
  void compactFloatBoundariesSpecialValuesAndUnchangedNegativeZeroFollowWireSemantics() {
    int[] patterns = {
      0x80000000,
      Float.floatToIntBits(-4096),
      Float.floatToIntBits(4095),
      Float.floatToIntBits(-4097),
      Float.floatToIntBits(4096),
      Float.floatToIntBits(0.5f),
      0x7fc12345,
      0x7f800000,
      0xff800000,
      1,
      0x7f7fffff
    };
    for (int pattern : patterns) {
      byte[] entity = new byte[208], player = new byte[468];
      word(entity, 24, pattern);
      word(player, 20, pattern);
      var writer = new MessageWriter();
      EntityDeltaCodec.write(writer, null, entity, true);
      var decoded =
          EntityDeltaCodec.read(new MessageReader(writer.bytes(), writer.bitPosition()), null);
      assertEquals(pattern == 0x80000000 ? 0 : pattern, word(decoded.state(), 24));
      writer = new MessageWriter();
      PlayerDeltaCodec.write(writer, null, player);
      byte[] result =
          PlayerDeltaCodec.read(new MessageReader(writer.bytes(), writer.bitPosition()), null);
      assertEquals(pattern == 0x80000000 ? 0 : pattern, word(result, 20));
    }
    byte[] baseline = new byte[208];
    word(baseline, 24, 0x80000000);
    var writer = new MessageWriter();
    EntityDeltaCodec.write(writer, baseline, baseline, true);
    assertEquals(
        0x80000000,
        word(EntityDeltaCodec.read(new MessageReader(writer.bytes()), baseline).state(), 24));
  }

  @Test
  void changedNarrowFieldsTruncateAndSignExtendButUnchangedBaselineWordsRemainExact() {
    byte[] entity = new byte[208];
    word(entity, 4, -1);
    word(entity, 180, 4095);
    var writer = new MessageWriter();
    EntityDeltaCodec.write(writer, null, entity, true);
    byte[] decoded = EntityDeltaCodec.read(new MessageReader(writer.bytes()), null).state();
    assertEquals(255, word(decoded, 4));
    assertEquals(1023, word(decoded, 180));
    byte[] player = new byte[468];
    word(player, 44, 65535);
    word(player, 164, 128);
    word(player, 144, 127);
    writer = new MessageWriter();
    PlayerDeltaCodec.write(writer, null, player);
    decoded = PlayerDeltaCodec.read(new MessageReader(writer.bytes()), null);
    assertEquals(-1, word(decoded, 44));
    assertEquals(-128, word(decoded, 164));
    assertEquals(31, word(decoded, 144));
    writer = new MessageWriter();
    PlayerDeltaCodec.write(writer, player, player);
    assertArrayEquals(player, PlayerDeltaCodec.read(new MessageReader(writer.bytes()), player));
  }

  @Test
  void allPlayerArraySlotsUseSignedShortsExceptFullWidthPowerups() {
    byte[] target = new byte[468];
    for (int offset = 184; offset < 440; offset += 4) word(target, offset, 0x12348000 + offset);
    var writer = new MessageWriter();
    PlayerDeltaCodec.write(writer, null, target);
    byte[] result = PlayerDeltaCodec.read(new MessageReader(writer.bytes()), null);
    for (int offset = 184; offset < 440; offset += 4) {
      int expected =
          offset >= 312 && offset < 376 ? word(target, offset) : (short) word(target, offset);
      assertEquals(expected, word(result, offset), "Array byte offset " + offset);
    }
  }

  @Test
  void playerNonNetworkedWordsComeFromOwnedBaseline() {
    byte[] baseline = new byte[468], target = new byte[468];
    for (int offset : new int[] {136, 452, 456, 460, 464}) {
      word(baseline, offset, 1000 + offset);
      word(target, offset, -1);
    }
    var writer = new MessageWriter();
    PlayerDeltaCodec.write(writer, baseline, target);
    assertEquals("02", hex(writer.bytes()));
    byte[] result = PlayerDeltaCodec.read(new MessageReader(writer.bytes()), baseline);
    assertArrayEquals(baseline, result);
    Arrays.fill(baseline, (byte) 0);
    assertEquals(1136, word(result, 136));
    assertEquals(-1, word(target, 136));
  }

  @Test
  void entireDeltaRollsBackAtEveryTruncatedBitBoundary() {
    for (boolean entity : new boolean[] {true, false}) {
      var writer = new MessageWriter();
      writer.bits(5, 3);
      if (entity) EntityDeltaCodec.write(writer, null, entityFixture(), true);
      else PlayerDeltaCodec.write(writer, null, playerFixture());
      for (int limit = 3; limit < writer.bitPosition(); limit++) {
        var reader = new MessageReader(writer.bytes(), limit);
        assertEquals(5, reader.bits(3));
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              if (entity) EntityDeltaCodec.read(reader, null);
              else PlayerDeltaCodec.read(reader, null);
            });
        assertEquals(3, reader.bitPosition(), "Truncation at bit " + limit);
      }
    }
  }

  @Test
  void capacityFailureClearsPartialDeltaAndAllowsSubsequentValidWrite() {
    for (int prefix = 0; prefix < 8; prefix++) {
      for (boolean entity : new boolean[] {true, false}) {
        var writer = new MessageWriter(8);
        var expected = new MessageWriter(8);
        if (prefix != 0) {
          writer.bits(127, prefix);
          expected.bits(127, prefix);
        }
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              if (entity) EntityDeltaCodec.write(writer, null, entityFixture(), true);
              else PlayerDeltaCodec.write(writer, null, playerFixture());
            });
        assertEquals(prefix, writer.bitPosition());
        assertArrayEquals(expected.bytes(), writer.bytes());
        writer.byteValue(73);
        expected.byteValue(73);
        assertArrayEquals(expected.bytes(), writer.bytes());
      }
    }
  }

  @Test
  void malformedFieldCountsAndInvalidStateBoundsAreAtomic() {
    var writer = new MessageWriter();
    writer.bits(1, 10);
    writer.bits(0, 1);
    writer.bits(1, 1);
    writer.byteValue(52);
    var entityReader = new MessageReader(writer.bytes());
    assertThrows(IllegalArgumentException.class, () -> EntityDeltaCodec.read(entityReader, null));
    assertEquals(0, entityReader.bitPosition());
    writer = new MessageWriter();
    writer.byteValue(49);
    var playerReader = new MessageReader(writer.bytes());
    assertThrows(IllegalArgumentException.class, () -> PlayerDeltaCodec.read(playerReader, null));
    assertEquals(0, playerReader.bitPosition());
    var output = new MessageWriter();
    output.bits(1, 1);
    for (int number : new int[] {-1, 1023, 1024}) {
      byte[] state = new byte[208];
      word(state, 0, number);
      assertThrows(
          IllegalArgumentException.class, () -> EntityDeltaCodec.write(output, null, state, true));
      assertThrows(
          IllegalArgumentException.class,
          () -> EntityDeltaCodec.readBody(entityReader, null, number));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> EntityDeltaCodec.write(output, new byte[207], new byte[208], true));
    assertThrows(
        IllegalArgumentException.class, () -> PlayerDeltaCodec.write(output, null, new byte[469]));
    assertEquals(1, output.bitPosition());
  }

  @Test
  void bodyReaderAndResultOwnershipSupportExternalSnapshotMerge() {
    byte[] baseline = new byte[208], target = entityFixture();
    var writer = new MessageWriter();
    EntityDeltaCodec.write(writer, baseline, target, true);
    var reader = new MessageReader(writer.bytes());
    int number = reader.bits(10);
    var result = EntityDeltaCodec.readBody(reader, baseline, number);
    Arrays.fill(baseline, (byte) 1);
    Arrays.fill(target, (byte) 2);
    byte[] exposed = result.state();
    Arrays.fill(exposed, (byte) 3);
    assertEquals(17, word(result.state(), 0));
    assertEquals(123456, word(result.state(), 16));
    byte[] constructorInput = new byte[208];
    var owned = new EntityDeltaCodec.EntityUpdate(0, false, constructorInput);
    constructorInput[0] = 1;
    assertEquals(0, owned.state()[0]);
  }

  @Test
  void fieldMetadataExactlyPartitionsNetworkedWordsAndCannotBeMutated() {
    assertEquals(51, StateFields.ENTITY.size());
    assertEquals(48, StateFields.PLAYER.size());
    var offsets = new HashSet<Integer>();
    for (var field : StateFields.ENTITY) assertTrue(offsets.add(field.byteOffset()));
    for (int offset = 4; offset < 208; offset += 4) assertTrue(offsets.contains(offset));
    offsets.clear();
    for (var field : StateFields.PLAYER) assertTrue(offsets.add(field.byteOffset()));
    for (int offset = 184; offset < 440; offset += 4) assertTrue(offsets.add(offset));
    for (int offset : new int[] {136, 452, 456, 460, 464}) assertTrue(offsets.add(offset));
    assertEquals(117, offsets.size());
    assertThrows(UnsupportedOperationException.class, () -> StateFields.ENTITY.clear());
  }

  @Test
  void nestedTransactionsRestoreOnlyTheirOwnBitsAndOuterFailureCanRestoreAll() {
    var writer = new MessageWriter();
    writer.bits(5, 3);
    writer.transaction(
        outer -> {
          outer.bits(3, 2);
          assertThrows(
              IllegalStateException.class,
              () ->
                  outer.transaction(
                      inner -> {
                        inner.intValue(-1);
                        throw new IllegalStateException("inner");
                      }));
          outer.byteValue(42);
          return null;
        });
    var expected = new MessageWriter();
    expected.bits(5, 3);
    expected.bits(3, 2);
    expected.byteValue(42);
    assertArrayEquals(expected.bytes(), writer.bytes());
    assertThrows(
        AssertionError.class,
        () ->
            writer.transaction(
                outer -> {
                  outer.transaction(
                      inner -> {
                        inner.intValue(123);
                        return null;
                      });
                  throw new AssertionError("outer");
                }));
    assertArrayEquals(expected.bytes(), writer.bytes());
    var reader = new MessageReader(writer.bytes());
    assertThrows(
        AssertionError.class,
        () ->
            reader.transaction(
                outer -> {
                  outer.bits(3);
                  outer.transaction(inner -> inner.bits(2));
                  throw new AssertionError("read");
                }));
    assertEquals(0, reader.bitPosition());
    assertEquals(5, reader.bits(3));
  }

  private static byte[] entityFixture() {
    byte[] state = new byte[208];
    word(state, 0, 17);
    word(state, 16, 123456);
    word(state, 24, 0x3fc00000);
    word(state, 28, 0x80000000);
    word(state, 180, 1023);
    word(state, 172, 65535);
    return state;
  }

  private static byte[] playerFixture() {
    byte[] state = new byte[468];
    word(state, 0, 12345);
    word(state, 44, -12);
    word(state, 164, -24);
    word(state, 184, -1);
    word(state, 436, -9999);
    word(state, 324, 123456789);
    word(state, 136, 123);
    return state;
  }

  private static void word(byte[] bytes, int offset, int value) {
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
  }

  private static int word(byte[] bytes, int offset) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}

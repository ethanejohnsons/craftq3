package dev.bluevista.craftq3.engine.net.delta;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.net.NetFormatException;
import dev.bluevista.craftq3.engine.net.RawBitWriter;
import dev.bluevista.craftq3.engine.net.delta.Protocol48Codec.Variant;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import java.util.List;
import org.junit.jupiter.api.Test;

final class Protocol48CodecTest {
  private static final int ENTITY_FIELDS = 51, PLAYER_FIELDS = 42;

  // Wire indexes of protocol-48 entity fields.
  private static final int E_TYPE = 0,
      TR_BASE_X = 5,
      TR_BASE_Y = 6,
      TR_BASE_Z = 7,
      CONSTANT_LIGHT = 37,
      FRAME = 41,
      CLIENT_NUM = 42,
      EVENT = 44;

  @Test
  void explicitMaskSelectsFieldsInDeclarationOrderOntoCanonicalOffsets() {
    var bits = new RawBitWriter();
    bits.bit(false).bit(true).bits(31, 5);
    long mask =
        1L << E_TYPE
            | 1L << TR_BASE_X
            | 1L << TR_BASE_Y
            | 1L << CONSTANT_LIGHT
            | 1L << FRAME
            | 1L << CLIENT_NUM;
    bits.bits(mask, ENTITY_FIELDS);
    bits.bits(2, 8).floatValue(-1507).floatValue(1.5f).bits(1, 8).bits(300, 16).bits(7, 8);
    byte[] state = new byte[208];
    assertTrue(Protocol48Codec.readEntityInPlace(reader(bits), state, 140));
    assertEquals(140, StateBytes.word(state, 0));
    assertEquals(2, StateBytes.word(state, 4), "eType");
    assertEquals(-1507f, Float.intBitsToFloat(StateBytes.word(state, 24)), "pos.trBase[0]");
    assertEquals(1.5f, Float.intBitsToFloat(StateBytes.word(state, 28)), "pos.trBase[1]");
    assertEquals(1, StateBytes.word(state, 152), "8-bit field stored as constantLight");
    assertEquals(300, StateBytes.word(state, 172), "frame precedes clientNum on the wire");
    assertEquals(7, StateBytes.word(state, 168), "clientNum");
  }

  @Test
  void tableMasksCarryValuesOnly() {
    byte[] state = new byte[208];
    StateBytes.word(state, 24, Float.floatToRawIntBits(10));
    StateBytes.word(state, 32, Float.floatToRawIntBits(-90));
    var corpse = new RawBitWriter().bit(false).bit(true).bits(14, 5).floatValue(-91);
    assertTrue(Protocol48Codec.readEntityInPlace(reader(corpse), state, 66));
    assertEquals(-91f, Float.intBitsToFloat(StateBytes.word(state, 32)));
    assertEquals(10f, Float.intBitsToFloat(StateBytes.word(state, 24)), "untouched");

    var event = new RawBitWriter().bit(false).bit(true).bits(7, 5).bits(0x233, 10);
    assertTrue(Protocol48Codec.readEntityInPlace(reader(event), state, 66));
    assertEquals(0x233, StateBytes.word(state, 180));
  }

  @Test
  void changeBitAndRemoveBit() {
    byte[] state = new byte[208];
    StateBytes.word(state, 4, 3);
    var unchanged = reader(new RawBitWriter().bit(false).bit(false));
    assertTrue(Protocol48Codec.readEntityInPlace(unchanged, state, 9));
    assertEquals(3, StateBytes.word(state, 4));
    assertEquals(2, unchanged.bitPosition());
    assertFalse(Protocol48Codec.readEntityInPlace(reader(new RawBitWriter().bit(true)), state, 9));
  }

  @Test
  void unknownTableMaskIsRejected() {
    var bits = new RawBitWriter().bit(false).bit(true).bits(5, 5).bits(0, 32);
    var failure =
        assertThrows(
            NetFormatException.class,
            () -> Protocol48Codec.readEntityInPlace(reader(bits), new byte[208], 4));
    assertTrue(failure.getMessage().contains("table mask 5"));
  }

  @Test
  void playerFieldsHaveChangeBitsTimersBeforeAnimationsAndArrays() {
    var bits = new RawBitWriter();
    for (int i = 0; i < PLAYER_FIELDS; i++) {
      boolean set = i == 0 || i == 5 || i == 18 || i == 20 || i == 37;
      bits.bit(set);
      if (!set) continue;
      switch (i) {
        case 0 -> bits.intValue(927850);
        case 5 -> bits.floatValue(-20.5f);
        case 18 -> bits.bits(50, 8); // legsTimer
        case 20 -> bits.bits(15, 8); // legsAnim
        case 37 -> bits.bits(26, 8); // viewheight
        default -> throw new AssertionError();
      }
    }
    bits.bit(false).bit(false).bit(false).bit(true).bits(136, 10).bit(false).bit(false);
    bits.bit(true).shortValue(1).shortValue(-5); // stats[0]
    bits.bit(false).bit(false);
    bits.bit(true).shortValue(1 << 8).intValue(Integer.MAX_VALUE); // powerups[8]
    byte[] state = new byte[468];
    var input = reader(bits);
    Protocol48Codec.readPlayerInPlace(input, state, Variant.PROTOCOL_48);
    assertEquals(bits.bitLength(), input.bitPosition());
    assertEquals(927850, StateBytes.word(state, 0));
    assertEquals(-20.5f, Float.intBitsToFloat(StateBytes.word(state, 20)));
    assertEquals(50, StateBytes.word(state, 72), "legsTimer");
    assertEquals(15, StateBytes.word(state, 76), "legsAnim");
    assertEquals(26, StateBytes.word(state, 164), "viewheight");
    assertEquals(136, StateBytes.word(state, 448), "jumppad_ent");
    assertEquals(-5, StateBytes.word(state, PlayerDeltaCodec.STATS_OFFSET));
    assertEquals(Integer.MAX_VALUE, StateBytes.word(state, PlayerDeltaCodec.POWERUPS_OFFSET + 32));

    var protocol46 = new RawBitWriter();
    for (int i = 0; i < PLAYER_FIELDS; i++) protocol46.bit(false);
    protocol46.bit(false).bit(false).bit(false).bit(true).bits(136, 10);
    assertThrows(
        NetFormatException.class,
        () -> Protocol48Codec.readPlayerInPlace(reader(protocol46), state, Variant.PROTOCOL_46));
  }

  @Test
  void snapshotsMergeDeltasLikeProtocol68() {
    byte[] item = new byte[208];
    StateBytes.word(item, 0, 70);
    StateBytes.word(item, 4, 2);
    var baselines = new Baselines(List.of(item));
    var full =
        new RawBitWriter().intValue(1000).byteValue(0).byteValue(0).byteValue(1).byteValue(1);
    emptyPlayer(full, 6);
    full.bits(70, 10).bit(false).bit(false); // the item, unchanged from its baseline
    full.bits(80, 10).bit(false).bit(true).bits(7, 5).bits(1, 10); // a new event entity
    full.bits(1023, 10);
    var first =
        Protocol48Codec.readSnapshot(
            reader(full), 5, baselines, ignored -> null, Variant.PROTOCOL_48);
    assertEquals(List.of(70, 80), numbers(first));

    var delta = new RawBitWriter().intValue(1050).byteValue(1).byteValue(0).byteValue(0);
    emptyPlayer(delta, 6);
    delta.bits(80, 10).bit(true).bits(1023, 10);
    var second =
        Protocol48Codec.readSnapshot(
            reader(delta), 6, baselines, n -> n == 5 ? first : null, Variant.PROTOCOL_48);
    assertEquals(List.of(70), numbers(second));
    assertArrayEquals(item, second.entities().getFirst());

    var missing = reader(delta);
    assertThrows(
        NetFormatException.class,
        () ->
            Protocol48Codec.readSnapshot(
                missing, 6, baselines, ignored -> null, Variant.PROTOCOL_48));
    assertEquals(0, missing.bitPosition(), "rewound");
    assertTrue(
        Protocol48Codec.readSnapshotAvailable(
                reader(delta), 6, baselines, ignored -> null, Variant.PROTOCOL_48)
            .isEmpty());
  }

  @Test
  void baselinesStartFromTheZeroState() {
    var bits = new RawBitWriter().bits(12, 10).bit(false).bit(true).bits(31, 5);
    bits.bits(1L << E_TYPE | 1L << TR_BASE_Z, ENTITY_FIELDS).bits(7, 8).floatValue(64);
    var update = Protocol48Codec.readBaseline(reader(bits));
    assertEquals(12, update.number());
    assertEquals(7, StateBytes.word(update.state(), 4));
    assertEquals(64f, Float.intBitsToFloat(StateBytes.word(update.state(), 32)));
    var removal = reader(new RawBitWriter().bits(12, 10).bit(true));
    assertThrows(NetFormatException.class, () -> Protocol48Codec.readBaseline(removal));
    assertEquals(0, removal.bitPosition());
  }

  private static void emptyPlayer(RawBitWriter bits, int trailing) {
    for (int i = 0; i < PLAYER_FIELDS + trailing + 4; i++) bits.bit(false);
  }

  private static List<Integer> numbers(Snapshot snapshot) {
    return snapshot.entities().stream().map(state -> StateBytes.word(state, 0)).toList();
  }

  private static MessageReader reader(RawBitWriter bits) {
    return new MessageReader(bits.toByteArray(), bits.bitLength()).coding(MessageReader.Coding.RAW);
  }
}

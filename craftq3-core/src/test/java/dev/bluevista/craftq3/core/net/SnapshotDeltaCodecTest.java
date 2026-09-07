package dev.bluevista.craftq3.core.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.core.net.delta.EntityDeltaCodec;
import dev.bluevista.craftq3.core.net.delta.PlayerDeltaCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SnapshotDeltaCodecTest {
  private static final Baselines EMPTY = Baselines.EMPTY;

  @Test
  void fullSnapshotMatchesNativeParserCapture() {
    var snapshot = snapshot(300, List.of());
    var writer = new MessageWriter();
    SnapshotDeltaCodec.write(writer, null, snapshot, EMPTY);
    var decoded =
        SnapshotDeltaCodec.read(
            new MessageReader(writer.bytes()),
            300,
            EMPTY,
            ignored -> {
              throw new AssertionError("Full snapshot consulted history");
            });
    assertEquals(300, decoded.sequence());
    assertEquals(0, decoded.time());
    assertArrayEquals(new byte[468], decoded.player());
    assertTrue(decoded.entities().isEmpty());
    // Exact bit string from the authored empty-body observation, accepted by CL_ParseSnapshot.
    assertEquals(25, writer.bitPosition());
    assertEquals("aaaa2601", HexFormat.of().formatHex(writer.bytes()));
  }

  @Test
  void mergesChangesRemovalsRetainedEntitiesAndLevelBaselines() {
    var a = entity(1, 11);
    var removed = entity(3, 33);
    var changed = entity(5, 55);
    var retained = entity(8, 88);
    var before = snapshot(10, List.of(a, removed, changed, retained));
    var baselines = new Baselines(List.of(entity(0, 19), entity(6, 66), entity(9, 99)));
    var after =
        snapshot(
            14, List.of(entity(0, 19), a, entity(5, 56), entity(6, 66), retained, entity(9, 90)));
    var writer = new MessageWriter();
    SnapshotDeltaCodec.write(writer, before, after, baselines);
    var queried = new AtomicInteger();
    var decoded =
        SnapshotDeltaCodec.read(
            new MessageReader(writer.bytes()),
            14,
            baselines,
            sequence -> {
              queried.set(sequence);
              return before;
            });
    assertEquals(10, queried.get());
    assertEquals(after.entities().size(), decoded.entities().size());
    for (int i = 0; i < after.entities().size(); i++)
      assertArrayEquals(after.entities().get(i), decoded.entities().get(i));
    var allRemoved = new MessageWriter();
    SnapshotDeltaCodec.write(allRemoved, after, snapshot(15, List.of()), baselines);
    assertTrue(
        SnapshotDeltaCodec.read(new MessageReader(allRemoved.bytes()), 15, after, baselines)
            .entities()
            .isEmpty());
  }

  @Test
  void snapshotAndBaselineOwnAllStateArrays() {
    byte[] area = {1, 2, 3}, player = new byte[468], entity = entity(7, 12);
    var entries = new ArrayList<byte[]>(List.of(entity));
    var snapshot = new Snapshot(1, 42, 255, area, player, entries);
    var baselines = new Baselines(entries);
    Arrays.fill(area, (byte) 0);
    Arrays.fill(player, (byte) 5);
    Arrays.fill(entity, (byte) 0);
    entries.clear();
    snapshot.areaMask()[0] = 9;
    snapshot.player()[0] = 9;
    snapshot.entities().getFirst()[0] = 9;
    baselines.state(7)[0] = 9;
    assertArrayEquals(new byte[] {1, 2, 3}, snapshot.areaMask());
    assertArrayEquals(new byte[468], snapshot.player());
    assertArrayEquals(entity(7, 12), snapshot.entities().getFirst());
    assertArrayEquals(entity(7, 12), baselines.state(7));
    assertArrayEquals(new byte[208], baselines.state(6));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.entities().clear());
  }

  @Test
  void historyAndDeltaDistanceAreExplicitlyChecked() {
    var before = snapshot(1, List.of(entity(1, 4)));
    var after = snapshot(256, List.of(entity(1, 5)));
    var writer = new MessageWriter();
    writer.bits(3, 2);
    SnapshotDeltaCodec.write(writer, before, after, EMPTY);
    var reader = new MessageReader(writer.bytes());
    reader.bits(2);
    assertThrows(
        IllegalArgumentException.class, () -> SnapshotDeltaCodec.read(reader, 256, null, EMPTY));
    assertEquals(2, reader.bitPosition());
    assertThrows(
        IllegalArgumentException.class,
        () -> SnapshotDeltaCodec.read(reader, 256, snapshot(2, List.of()), EMPTY));
    assertEquals(2, reader.bitPosition());
    assertEquals(256, SnapshotDeltaCodec.read(reader, 256, before, EMPTY).sequence());
    int bits = writer.bitPosition();
    assertThrows(
        IllegalArgumentException.class,
        () -> SnapshotDeltaCodec.write(writer, before, snapshot(257, List.of()), EMPTY));
    assertThrows(
        IllegalArgumentException.class,
        () -> SnapshotDeltaCodec.write(writer, before, before, EMPTY));
    assertEquals(bits, writer.bitPosition());
  }

  @Test
  void malformedBodiesAndEveryTruncatedBitRestoreTheCursor() {
    var before = snapshot(2, List.of(entity(1, 33)));
    var after = new Snapshot(3, 42, 5, new byte[] {1, 2, 3}, new byte[468], List.of(entity(2, 77)));
    var writer = new MessageWriter();
    writer.bits(7, 3);
    SnapshotDeltaCodec.write(writer, before, after, EMPTY);
    for (int limit = 3; limit < writer.bitPosition(); limit++) {
      var reader = new MessageReader(writer.bytes(), limit);
      reader.bits(3);
      assertThrows(
          IllegalArgumentException.class, () -> SnapshotDeltaCodec.read(reader, 3, before, EMPTY));
      assertEquals(3, reader.bitPosition());
    }
    var unordered = bodyHeader(0);
    EntityDeltaCodec.write(unordered, null, entity(2, 2), true);
    EntityDeltaCodec.write(unordered, null, entity(1, 1), true);
    EntityDeltaCodec.writeEnd(unordered);
    var reader = new MessageReader(unordered.bytes());
    assertThrows(
        IllegalArgumentException.class, () -> SnapshotDeltaCodec.read(reader, 1, null, EMPTY));
    assertEquals(0, reader.bitPosition());
    var oversized = bodyHeader(33);
    assertThrows(
        IllegalArgumentException.class,
        () -> SnapshotDeltaCodec.read(new MessageReader(oversized.bytes()), 1, null, EMPTY));
    var capacity = new MessageWriter(8);
    capacity.bits(5, 3);
    byte[] prefix = capacity.bytes();
    assertThrows(
        IllegalArgumentException.class,
        () -> SnapshotDeltaCodec.write(capacity, null, after, EMPTY));
    assertEquals(3, capacity.bitPosition());
    assertArrayEquals(prefix, capacity.bytes());
  }

  @Test
  void stateCountsIdsAndCanonicalSizesAreBounded() {
    assertThrows(IllegalArgumentException.class, () -> snapshot(1, List.of(entity(1023, 0))));
    assertThrows(
        IllegalArgumentException.class, () -> snapshot(1, List.of(entity(2, 0), entity(2, 1))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Snapshot(1, 0, 0, new byte[33], new byte[468], List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Snapshot(1, 0, 0, new byte[0], new byte[1], List.of()));
    var entities = new ArrayList<byte[]>();
    for (int i = 0; i < 256; i++) entities.add(entity(i, 0));
    assertEquals(256, snapshot(1, entities).entities().size());
    entities.add(entity(256, 0));
    assertThrows(IllegalArgumentException.class, () -> snapshot(1, entities));
    var overflow = bodyHeader(0);
    for (byte[] entity : entities) EntityDeltaCodec.write(overflow, null, entity, true);
    EntityDeltaCodec.writeEnd(overflow);
    var reader = new MessageReader(overflow.bytes());
    assertThrows(
        IllegalArgumentException.class, () -> SnapshotDeltaCodec.read(reader, 1, null, EMPTY));
    assertEquals(0, reader.bitPosition());
  }

  private static MessageWriter bodyHeader(int areaBytes) {
    var writer = new MessageWriter();
    writer.intValue(0);
    writer.byteValue(0);
    writer.byteValue(0);
    writer.byteValue(areaBytes);
    PlayerDeltaCodec.write(writer, null, new byte[468]);
    return writer;
  }

  private static Snapshot snapshot(int sequence, List<byte[]> entities) {
    return new Snapshot(sequence, 0, 0, new byte[0], new byte[468], entities);
  }

  private static byte[] entity(int id, int type) {
    byte[] bytes = new byte[208];
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, id).putInt(4, type);
    return bytes;
  }
}

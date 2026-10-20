package dev.bluevista.craftq3.assets.aas;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AasReaderTest {
  @Test
  void decodesBothVersionsWithoutMutatingInput() throws Exception {
    byte[] plain = AasFixture.map(4), encoded = AasFixture.map(5), original = encoded.clone();
    AasMap a = AasReader.read(plain, AasFixture.CHECKSUM),
        b = AasReader.read(encoded, AasFixture.CHECKSUM);
    assertEquals(4, a.version());
    assertEquals(5, b.version());
    assertArrayEquals(original, encoded);
    assertEquals(a.lumps(), b.lumps());
    assertEquals(a.areas(), b.areas());
    assertEquals(a.reachabilities(), b.reachabilities());
    assertEquals(AasFixture.CHECKSUM, b.bspChecksum());
    assertEquals(14, b.lumps().size());
    // Corpus-confirmed v5 mask over the first lump's 124-byte offset: DC 53 CA 41 XOR 7C 00 00 00.
    assertArrayEquals(
        new byte[] {(byte) 0xa0, 0x53, (byte) 0xca, 0x41}, Arrays.copyOfRange(encoded, 12, 16));
    assertThrows(AasFormatException.class, () -> AasReader.read(encoded, 123));
  }

  @Test
  void retainsTypedGeometryOrientationClustersAndTravelMetadata() throws Exception {
    AasMap map = AasReader.read(AasFixture.map(5));
    assertEquals(new Vec3(-15, -15, -24), map.boundingBoxes().getFirst().min());
    assertEquals(new Vec3(-1, 1, 0), map.vertices().get(5));
    assertEquals(new AasMap.Plane(new Vec3(-1, 0, 0), 1, 0), map.planes().get(1));
    assertEquals(-6, map.edgeIndices().get(3));
    assertEquals(-2, map.faceIndices().get(2));
    assertEquals(new AasMap.Node(0, -1, 2), map.nodes().get(1));
    assertEquals(-1, map.areaSettings().get(2).cluster());
    assertEquals(new AasMap.Portal(2, 1, 2, 1, 1), map.portals().get(1));
    assertEquals(1, map.clusters().get(2).firstPortal());
    var walk = map.reachabilities().get(1);
    assertEquals(AasMap.TravelType.WALK, walk.kind().orElseThrow());
    assertEquals(1 << 24, walk.travelFlags());
    assertEquals(65535, walk.travelTime());
    assertEquals(0xbeef, walk.reserved());
    assertEquals(-1, walk.edge());
    assertTrue(walk.hasGeometryReferences());
    var mover = map.reachabilities().get(2);
    assertEquals(0x20002, mover.face());
    assertEquals(-81659335, mover.edge());
    assertFalse(mover.hasGeometryReferences());
    assertEquals(AasMap.TravelType.FUNC_BOB, mover.kind().orElseThrow());
  }

  @Test
  void modelOwnsItsCollectionsAndIndexStorage() throws Exception {
    byte[] bytes = AasFixture.map(4);
    AasMap map = AasReader.read(bytes);
    Arrays.fill(bytes, (byte) 0);
    assertEquals(4, map.areas().size());
    assertThrows(UnsupportedOperationException.class, () -> map.vertices().clear());
    assertThrows(UnsupportedOperationException.class, () -> map.areaSettings().clear());
    assertThrows(UnsupportedOperationException.class, () -> map.lumps().clear());
    int[] source = {1, -2};
    var indices = new AasMap.Indices(source);
    source[0] = 20;
    indices.toArray()[1] = 30;
    assertEquals(new AasMap.Indices(new int[] {1, -2}), indices);
    assertEquals(1, indices.get(0));
    assertEquals(-2, indices.get(1));
    assertThrows(IndexOutOfBoundsException.class, () -> indices.get(2));
  }

  @Test
  void rejectsMalformedHeadersLumpsTruncationsAndElementBudgets() {
    for (int[] corruption :
        new int[][] {
          {0, 0},
          {4, 3},
          {12, -1},
          {12, 0},
          {16, 1},
          {16, Integer.MAX_VALUE},
          {20, AasFixture.offset(0)}
        }) {
      assertThrows(
          AasFormatException.class,
          () -> AasReader.read(AasFixture.integer(corruption[0], corruption[1])));
    }
    byte[] fixture = AasFixture.map(4);
    for (int i = 0; i < fixture.length; i++) {
      int length = i;
      assertThrows(AasFormatException.class, () -> AasReader.read(Arrays.copyOf(fixture, length)));
    }
    ByteBuffer overBudget = ByteBuffer.allocate(124 + 65 * 32).order(ByteOrder.LITTLE_ENDIAN);
    overBudget.putInt(0x53414145).putInt(4).putInt(0).putInt(124).putInt(65 * 32);
    assertThrows(AasFormatException.class, () -> AasReader.read(overBudget.array()));
    assertThrows(AasFormatException.class, () -> AasReader.read(null));
    Random random = new Random(12345);
    for (int i = 0; i < 1000; i++) {
      int offset = 12 + random.nextInt(28) * 4, value = random.nextInt();
      assertThrows(
          AasFormatException.class, () -> AasReader.read(AasFixture.integer(offset, value)));
    }
  }

  @Test
  void checksGeometryRangesAndOrientedAreaReferences() {
    int[][] bad = {
      {AasFixture.offset(0) + 8, Float.floatToIntBits(100)},
      {AasFixture.offset(1), Float.floatToIntBits(Float.NaN)},
      {AasFixture.offset(2) + 16, 6},
      {AasFixture.offset(3) + 8, 99},
      {AasFixture.offset(4), Integer.MIN_VALUE},
      {AasFixture.offset(4), 0},
      {AasFixture.offset(5) + 24, 99},
      {AasFixture.offset(5) + 24 + 8, Integer.MAX_VALUE},
      {AasFixture.offset(5) + 48 + 12, 2},
      {AasFixture.offset(6), -1},
      {AasFixture.offset(7) + 48, 2},
      {AasFixture.offset(7) + 144 + 8, 1},
      {AasFixture.offset(8) + 28 + 24, 99},
      {AasFixture.offset(8) + 56 + 24, 1},
      {AasFixture.offset(9) + 44, 99},
      {AasFixture.offset(9) + 44 + 4, 99},
      {AasFixture.offset(9) + 44 + 8, Integer.MIN_VALUE}
    };
    for (int[] field : bad)
      assertThrows(
          AasFormatException.class,
          () -> AasReader.read(AasFixture.integer(field[0], field[1])),
          "offset=" + field[0]);
    // Removing exactly one settings record is aligned but no longer matches the area count.
    assertThrows(
        AasFormatException.class, () -> AasReader.read(AasFixture.integer(16 + 8 * 8, 3 * 28)));
  }

  @Test
  void checksNodesForInvalidChildrenAndCyclesWithoutRecursiveTraversal() {
    for (int child : new int[] {1, 99, -99, Integer.MIN_VALUE}) {
      assertThrows(
          AasFormatException.class,
          () -> AasReader.read(AasFixture.integer(AasFixture.offset(10) + 12 + 4, child)));
    }
    assertThrows(
        AasFormatException.class,
        () -> AasReader.read(AasFixture.integer(AasFixture.offset(10) + 24 + 4, 1)));
  }

  @Test
  void checksPortalClusterAndLocalAreaCrossReferences() {
    int[][] bad = {
      {AasFixture.offset(8) + 56, 0}, {AasFixture.offset(8) + 56 + 12, Integer.MIN_VALUE},
      {AasFixture.offset(8) + 28 + 16, 2}, {AasFixture.offset(11) + 20, 1},
      {AasFixture.offset(11) + 20 + 4, 3}, {AasFixture.offset(11) + 20 + 12, 99},
      {AasFixture.offset(12), -1}, {AasFixture.offset(13) + 16 + 4, 3},
      {AasFixture.offset(13) + 32 + 12, 0}
    };
    for (int[] field : bad)
      assertThrows(
          AasFormatException.class,
          () -> AasReader.read(AasFixture.integer(field[0], field[1])),
          "offset=" + field[0]);
  }

  @Test
  void preservesUnknownTravelBitsAndSpecialMovementFields() throws Exception {
    byte[] bytes = AasFixture.map(4);
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(AasFixture.offset(9) + 44 + 36, 18 | (2 << 24));
    b.putInt(AasFixture.offset(9) + 44 + 4, Integer.MAX_VALUE);
    b.putInt(AasFixture.offset(9) + 44 + 8, Integer.MIN_VALUE);
    var jump = AasReader.read(bytes).reachabilities().get(1);
    assertEquals(AasMap.TravelType.JUMP_PAD, jump.kind().orElseThrow());
    assertFalse(jump.hasGeometryReferences());
    assertEquals(Integer.MIN_VALUE, jump.edge());
    b.putInt(AasFixture.offset(9) + 44 + 36, 0x7f123456);
    var unknown = AasReader.read(bytes).reachabilities().get(1);
    assertTrue(unknown.kind().isEmpty());
    assertEquals(0x123456, unknown.baseTravelType());
    assertEquals(0x7f000000, unknown.travelFlags());
  }
}

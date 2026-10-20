package dev.bluevista.craftq3.engine.formats.aas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AasReaderTest {
  @Test
  void decodesBothVersionsWithoutMutatingInput() {
    byte[] plain = AasFixture.map(4);
    byte[] encoded = AasFixture.map(5);
    byte[] original = encoded.clone();
    AasMap a = AasReader.read(plain, AasFixture.CHECKSUM);
    AasMap b = AasReader.read(encoded, AasFixture.CHECKSUM);
    assertEquals(4, a.version());
    assertEquals(5, b.version());
    assertArrayEquals(original, encoded);
    assertEquals(a.lumps(), b.lumps());
    assertArrayEquals(a.reachabilities().start(2).toArray(), b.reachabilities().start(2).toArray());
    assertArrayEquals(a.areaSettings().toArray(), b.areaSettings().toArray());
    assertEquals(AasFixture.CHECKSUM, b.bspChecksum());
    assertEquals(14, b.lumps().size());
    // Corpus-confirmed v5 mask over the first lump's 124-byte offset: DC 53 CA 41 XOR 7C 00 00 00.
    assertArrayEquals(
        new byte[] {(byte) 0xa0, 0x53, (byte) 0xca, 0x41}, Arrays.copyOfRange(encoded, 12, 16));
    assertThrows(AasFormatException.class, () -> AasReader.read(encoded, 123));
  }

  @Test
  void retainsTypedGeometryOrientationClustersAndTravelMetadata() {
    AasMap map = AasReader.read(AasFixture.map(5));
    assertEquals(new Vec3f(-15, -15, -24), map.boundingBoxes().getFirst().mins());
    assertEquals(new Vec3f(-1, 1, 0), map.vertices().get(5));
    assertEquals(new Vec3f(-1, 0, 0), map.planes().normal(1));
    assertEquals(1, map.planes().distance(1));
    assertEquals(0, map.planes().type(1));
    assertEquals(-6, map.edgeIndices().get(3));
    assertEquals(-2, map.faceIndices().get(2));
    assertEquals(-1, map.nodes().get(1, AasMap.NODE_FRONT));
    assertEquals(2, map.nodes().get(1, AasMap.NODE_BACK));
    assertEquals(-1, map.areaSettings().get(2, AasMap.SETTINGS_CLUSTER));
    assertEquals(2, map.portals().get(1, AasMap.PORTAL_AREA));
    assertEquals(1, map.clusters().get(2, AasMap.CLUSTER_FIRST_PORTAL));
    AasMap.Reachabilities reach = map.reachabilities();
    assertEquals(TravelType.WALK, reach.kind(1).orElseThrow());
    assertEquals(1 << 24, reach.travelFlags(1));
    assertEquals(65535, reach.travelTime(1));
    assertEquals(0xbeef, reach.reserved(1));
    assertEquals(-1, reach.edge(1));
    assertTrue(reach.hasGeometryReferences(1));
    assertEquals(0x20002, reach.face(2));
    assertEquals(-81659335, reach.edge(2));
    assertFalse(reach.hasGeometryReferences(2));
    assertEquals(TravelType.FUNC_BOB, reach.kind(2).orElseThrow());
    assertEquals(new Vec3f(1, 2, 3), reach.end(2));
    assertEquals(new Vec3f(0.5f, 0, 0), map.areas().center(1));
  }

  @Test
  void modelOwnsItsStorage() {
    byte[] bytes = AasFixture.map(4);
    AasMap map = AasReader.read(bytes);
    Arrays.fill(bytes, (byte) 0);
    assertEquals(4, map.areas().count());
    assertEquals(-6, map.edgeIndices().get(3));
    int[] copy = map.edgeIndices().toArray();
    copy[3] = 0;
    assertEquals(-6, map.edgeIndices().get(3));
    assertThrows(UnsupportedOperationException.class, () -> map.lumps().clear());
    assertThrows(IndexOutOfBoundsException.class, () -> map.areaSettings().get(0, 7));
  }

  @Test
  void rejectsMalformedHeadersLumpsTruncationsAndElementBudgets() {
    for (int[] corruption :
        new int[][] {{0, 0}, {4, 3}, {12, -1}, {16, 1}, {16, Integer.MAX_VALUE}, {16, -32}}) {
      assertThrows(
          AasFormatException.class,
          () -> AasReader.read(AasFixture.integer(corruption[0], corruption[1])),
          Arrays.toString(corruption));
    }
    byte[] fixture = AasFixture.map(4);
    for (int i = 0; i < fixture.length; i++) {
      int length = i;
      assertThrows(AasFormatException.class, () -> AasReader.read(Arrays.copyOf(fixture, length)));
    }
    ByteBuffer overBudget = ByteBuffer.allocate(124 + 65 * 32).order(ByteOrder.LITTLE_ENDIAN);
    overBudget.putInt(AasMap.IDENT).putInt(4).putInt(0).putInt(124).putInt(65 * 32);
    assertThrows(AasFormatException.class, () -> AasReader.read(overBudget.array()));
    assertThrows(AasFormatException.class, () -> AasReader.read((byte[]) null));
  }

  @Test
  void acceptsOverlappingLumpsLikeTheOriginalLoader() {
    // Point the empty-in-use vertex lump at the bounding boxes: both decode as finite floats.
    byte[] bytes = AasFixture.integer(20, AasFixture.offset(0));
    AasMap map = AasReader.read(bytes);
    assertTrue(map.diagnostics().stream().anyMatch(d -> d.contains("overlap")));
  }

  @Test
  void randomHeaderCorruptionFailsOnlyWithFormatErrors() {
    Random random = new Random(12345);
    for (int i = 0; i < 1000; i++) {
      int offset = 12 + random.nextInt(28) * 4;
      int value = random.nextInt();
      try {
        AasReader.read(AasFixture.integer(offset, value));
      } catch (AasFormatException expected) {
        assertTrue(expected.getMessage() != null);
      }
    }
  }

  @Test
  void checksEveryIndexBotlibFollows() {
    int[][] bad = {
      {AasFixture.offset(1), Float.floatToIntBits(Float.NaN)},
      {AasFixture.offset(3) + 8, 99},
      {AasFixture.offset(4), Integer.MIN_VALUE},
      {AasFixture.offset(4), 99},
      {AasFixture.offset(5) + 24, 99},
      {AasFixture.offset(5) + 24 + 8, Integer.MAX_VALUE},
      {AasFixture.offset(6), -99},
      {AasFixture.offset(7) + 48 + 8, 99},
      {AasFixture.offset(8) + 28 + 24, 99},
      {AasFixture.offset(8) + 56 + 12, Integer.MIN_VALUE},
      {AasFixture.offset(8) + 28 + 16, 2},
      {AasFixture.offset(9) + 44, 99},
      {AasFixture.offset(9) + 44 + 4, 99},
      {AasFixture.offset(9) + 44 + 8, Integer.MIN_VALUE},
      {AasFixture.offset(11) + 20 + 4, 3},
      {AasFixture.offset(11) + 20 + 12, 99},
      {AasFixture.offset(12), -1},
      {AasFixture.offset(13) + 16 + 12, 99}
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
    for (int child : new int[] {99, -99, Integer.MIN_VALUE}) {
      assertThrows(
          AasFormatException.class,
          () -> AasReader.read(AasFixture.integer(AasFixture.offset(10) + 12 + 4, child)));
    }
    assertThrows(
        AasFormatException.class,
        () -> AasReader.read(AasFixture.integer(AasFixture.offset(10) + 12 + 4, 1)));
  }

  @Test
  void strictIssuesReportRedundantFieldsThatDisagree() {
    assertTrue(AasValidator.strictIssues(AasReader.read(AasFixture.map(4))).isEmpty());
    int[][] inconsistent = {
      {AasFixture.offset(0) + 8, Float.floatToIntBits(100)},
      {AasFixture.offset(2) + 16, 6},
      {AasFixture.offset(5) + 48 + 12, 2},
      {AasFixture.offset(7) + 48, 2},
      {AasFixture.offset(8) + 56 + 24, 1},
      {AasFixture.offset(8) + 56, 0},
      {AasFixture.offset(11) + 20, 1},
      {AasFixture.offset(13) + 32 + 12, 0}
    };
    for (int[] field : inconsistent) {
      List<String> issues =
          AasValidator.strictIssues(AasReader.read(AasFixture.integer(field[0], field[1])));
      assertFalse(issues.isEmpty(), "offset=" + field[0]);
    }
  }

  @Test
  void preservesUnknownTravelBitsAndSpecialMovementFields() {
    byte[] bytes = AasFixture.map(4);
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(AasFixture.offset(9) + 44 + 36, 18 | (2 << 24));
    b.putInt(AasFixture.offset(9) + 44 + 4, Integer.MAX_VALUE);
    b.putInt(AasFixture.offset(9) + 44 + 8, Integer.MIN_VALUE);
    AasMap.Reachabilities jump = AasReader.read(bytes).reachabilities();
    assertEquals(TravelType.JUMP_PAD, jump.kind(1).orElseThrow());
    assertFalse(jump.hasGeometryReferences(1));
    assertEquals(Integer.MIN_VALUE, jump.edge(1));
    b.putInt(AasFixture.offset(9) + 44 + 36, 0x7f123456);
    AasMap.Reachabilities unknown = AasReader.read(bytes).reachabilities();
    assertTrue(unknown.kind(1).isEmpty());
    assertEquals(0x123456, unknown.baseTravelType(1));
    assertEquals(0x7f000000, unknown.travelFlags(1));
  }
}

package dev.bluevista.craftq3.assets.bsp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BspReaderTest {
  private static ByteBuffer view(byte[] bytes) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static int lump(byte[] bytes, int i) {
    return view(bytes).getInt(8 + i * 8);
  }

  @Test
  void readsAllSeventeenLumpsAndRelativeMeshIndices() throws Exception {
    var m = BspReader.read(BspFixture.map(false));
    assertEquals("worldspawn", m.entities().getFirst().get("classname"));
    assertEquals("0 -200 100", m.entities().get(1).get("origin"));
    assertEquals(7, m.textures().getFirst().flags());
    assertEquals(1, m.planes().getFirst().normal().z());
    assertEquals(-1, m.nodes().getFirst().front());
    assertEquals(1, m.leaves().getFirst().faceCount());
    assertEquals(0, m.leafFaces().getFirst());
    assertEquals(0, m.leafBrushes().getFirst());
    assertEquals(1, m.models().size());
    assertEquals(1, m.brushes().getFirst().sideCount());
    assertEquals(0, m.brushSides().getFirst().plane());
    assertEquals(4, m.vertices().size());
    assertEquals(0.25f, m.vertices().getFirst().textureUv().u());
    assertEquals(2, m.meshVertices().getLast());
    assertEquals("fog/test", m.effects().getFirst().name());
    assertEquals(1, m.faces().getFirst().firstVertex());
    assertEquals(200, m.lightmaps().getFirst().unsigned(0));
    assertEquals(0x010203, m.lightVolumes().getFirst().ambientRgb());
    assertEquals(8, m.lightVolumes().getFirst().longitude());
    assertTrue(m.visibility().visible(0, 0));
    assertFalse(m.visibility().visible(0, 1));
    assertThrows(UnsupportedOperationException.class, () -> m.vertices().clear());
    byte[] copy = m.lightmaps().getFirst().copy();
    copy[0] = 0;
    assertEquals(200, m.lightmaps().getFirst().unsigned(0));
  }

  @Test
  void acceptsUnusedZeroEffectOnOriginalMapFlaresOnly() throws Exception {
    byte[] bytes = BspFixture.map(false);
    view(bytes).putInt(8 + 12 * 8 + 4, 0);
    view(bytes).putInt(lump(bytes, 13) + 8, 4);
    assertEquals(0, BspReader.read(bytes).faces().getFirst().effect());
    view(bytes).putInt(lump(bytes, 13) + 4, 1);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    view(bytes).putInt(lump(bytes, 13) + 4, 0);
    view(bytes).putInt(lump(bytes, 13) + 8, 1);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
  }

  @Test
  void validatesPatchControlGrids() throws Exception {
    byte[] bytes = BspFixture.map(true);
    assertEquals(3, BspReader.read(bytes).faces().getFirst().patchWidth());
    view(bytes).putInt(lump(bytes, 13) + 96, 4);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
  }

  @Test
  void ignoresOnlyPatchIndexFieldsWhileCheckingItsControlGrid() throws Exception {
    byte[] bytes = BspFixture.map(true);
    int face = lump(bytes, 13);
    view(bytes).putInt(face + 20, Integer.MAX_VALUE).putInt(face + 24, Integer.MIN_VALUE);
    assertEquals(9, BspReader.read(bytes).vertices().size());
    view(bytes).putInt(face + 16, 10);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    view(bytes).putInt(face + 16, 9).putInt(face + 96, 4);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));

    byte[] clean = BspFixture.map(true);
    byte[] indexed = Arrays.copyOf(clean, clean.length + 12);
    view(indexed).putInt(8 + 11 * 8, clean.length).putInt(8 + 11 * 8 + 4, 12);
    view(indexed).putInt(clean.length, 1098907648).putInt(clean.length + 4, -1);
    view(indexed).putInt(lump(indexed, 13) + 24, 3);
    assertEquals(1098907648, BspReader.read(indexed).meshVertices().getFirst());
  }

  @Test
  void keepsActiveTriangleIndexBoundsStrictForBothIndexedSurfaceTypes() {
    for (int type : new int[] {1, 3}) {
      for (int[] mutation : new int[][] {{20, -1}, {20, Integer.MAX_VALUE}, {24, -1}, {24, 4}}) {
        byte[] bytes = BspFixture.map(false);
        int face = lump(bytes, 13);
        view(bytes).putInt(face + 8, type).putInt(face + mutation[0], mutation[1]);
        assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
      }
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(lump(bytes, 13) + 8, type);
      view(bytes).putInt(lump(bytes, 11), 1098907648);
      assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    }
  }

  @Test
  void canonicalizesOnlyNonFiniteLightmapCoordinatesUsedExclusivelyByVertexLitFaces()
      throws Exception {
    for (int type : new int[] {1, 2, 3}) {
      for (float nonFinite :
          new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
        for (int coordinate : new int[] {20, 24}) {
          byte[] bytes = BspFixture.map(type == 2);
          view(bytes).putInt(lump(bytes, 13) + 8, type).putInt(lump(bytes, 13) + 28, -3);
          view(bytes).putFloat(lump(bytes, 10) + 44 + coordinate, nonFinite);
          var vertex = BspReader.read(bytes).vertices().get(1);
          assertEquals(coordinate == 20 ? 0 : 0.5f, vertex.lightmapUv().u());
          assertEquals(coordinate == 24 ? 0 : 0.5f, vertex.lightmapUv().v());
          assertEquals(0.25f, vertex.textureUv().u());
          assertEquals(0.75f, vertex.textureUv().v());
        }
      }
    }
  }

  @Test
  void rejectsNonFiniteActiveAttributesAndUnreferencedLightmapCoordinates() {
    for (int coordinate : new int[] {0, 4, 8, 12, 16, 28, 32, 36}) {
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(lump(bytes, 13) + 28, -3);
      view(bytes).putFloat(lump(bytes, 10) + 44 + coordinate, Float.NaN);
      assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    }
    for (int lightmap : new int[] {-2, -1, 0}) {
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(lump(bytes, 13) + 28, lightmap);
      view(bytes).putFloat(lump(bytes, 10) + 44 + 20, Float.NaN);
      assertTrue(
          assertThrows(BspFormatException.class, () -> BspReader.read(bytes))
              .getMessage()
              .contains("lightmap UV at BSP vertex 1"));
    }
    byte[] bytes = BspFixture.map(false);
    view(bytes).putInt(lump(bytes, 13) + 28, -3);
    view(bytes).putFloat(lump(bytes, 10) + 20, Float.NaN);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
  }

  @Test
  void rejectsNonFiniteLightmapCoordinatesSharedWithAnyNonVertexLitFace() throws Exception {
    byte[] original = BspFixture.map(false);
    int oldFace = lump(original, 13);
    byte[] bytes = Arrays.copyOf(original, original.length + 208);
    System.arraycopy(original, oldFace, bytes, original.length, 104);
    System.arraycopy(original, oldFace, bytes, original.length + 104, 104);
    view(bytes).putInt(8 + 13 * 8, original.length).putInt(8 + 13 * 8 + 4, 208);
    view(bytes).putInt(original.length + 28, -3);
    view(bytes).putFloat(lump(bytes, 10) + 44 + 24, Float.NaN);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    view(bytes).putInt(original.length + 104 + 28, -3);
    assertEquals(0, BspReader.read(bytes).vertices().get(1).lightmapUv().v());
  }

  @Test
  void rejectsInvalidHeadersOffsetsOverlapAndPartialRecords() {
    assertThrows(BspFormatException.class, () -> BspReader.read(new byte[143]));
    for (int[] mutation :
        new int[][] {
          {0, 0},
          {4, 47},
          {8, -1},
          {8, Integer.MAX_VALUE},
          {12, Integer.MAX_VALUE},
          {16, 144},
          {20, 71}
        }) {
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(mutation[0], mutation[1]);
      assertThrows(
          BspFormatException.class, () -> BspReader.read(bytes), Arrays.toString(mutation));
    }
  }

  @Test
  void rejectsBadReferencesCyclesFloatsAndVisibility() {
    for (int[] mutation :
        new int[][] {
          {3, 4, 0},
          {3, 4, Integer.MIN_VALUE},
          {5, 0, 1},
          {8, 4, Integer.MAX_VALUE},
          {10, 0, 0x7fc00000},
          {11, 0, -1},
          {11, 8, 3},
          {13, 8, 5},
          {13, 28, 1},
          {16, 0, Integer.MAX_VALUE},
          {16, 4, 0}
        }) {
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(lump(bytes, mutation[0]) + mutation[1], mutation[2]);
      assertThrows(
          BspFormatException.class, () -> BspReader.read(bytes), Arrays.toString(mutation));
    }
  }

  @Test
  void absentVisibilityIsConservativelyVisible() throws Exception {
    byte[] bytes = BspFixture.map(false);
    view(bytes).putInt(8 + 16 * 8 + 4, 0);
    assertTrue(BspReader.read(bytes).visibility().visible(0, 10));
  }

  @Test
  void deterministicMalformedInputSmokeFuzzHasOnlyDescriptiveFailures() {
    Random random = new Random(46);
    for (int i = 0; i < 1000; i++) {
      byte[] bytes = BspFixture.map(i % 2 == 0);
      int where = random.nextInt(144);
      bytes[where] = (byte) random.nextInt(256);
      try {
        BspReader.read(bytes);
      } catch (BspFormatException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }

  @Test
  void entityCommentsTerminatorsAndMalformedTokens() throws Exception {
    assertEquals(
        "a\\b",
        EntityParser.parse("/* hi */ { \"path\" \"a\\b\" } // end\n\0").getFirst().get("path"));
    for (String invalid :
        new String[] {"{", "{ \"x\" }", "{ \"x\" \"unterminated", "/* missing", "\0evil"})
      assertThrows(BspFormatException.class, () -> EntityParser.parse(invalid));
  }
}

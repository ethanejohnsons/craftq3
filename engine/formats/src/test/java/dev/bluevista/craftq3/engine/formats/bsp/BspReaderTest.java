package dev.bluevista.craftq3.engine.formats.bsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.hash.Md4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BspReaderTest {
  private static ByteBuffer view(byte[] bytes) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static int lump(byte[] bytes, BspLump kind) {
    return view(bytes).getInt(kind.headerOffset());
  }

  private static boolean noted(BspMap map, String text) {
    return map.diagnostics().stream().anyMatch(note -> note.contains(text));
  }

  @Test
  void readsAllSeventeenLumpsIntoPrimitiveViews() {
    byte[] bytes = BspFixture.map(false);
    BspMap m = BspReader.read(bytes);
    assertEquals(BspMap.VERSION_Q3, m.version());
    assertFalse(m.extendedVersion());
    assertEquals(Md4.blockChecksum(bytes), m.checksum());
    assertEquals("worldspawn", m.entities().getFirst().classname().orElseThrow());
    assertEquals("0 -200 100", m.entities().get(1).get("ORIGIN").orElseThrow());
    assertTrue(m.entityText().startsWith("{\n\"classname\""));
    assertEquals(7, m.textures().getFirst().surfaceFlags());
    assertEquals(1, m.planes().normal(0, 2));
    assertEquals(-1, m.nodes().front(0));
    assertEquals(1, m.leaves().leafFaceCount(0));
    assertEquals(0, m.leafFaces().get(0));
    assertEquals(0, m.leafBrushes().get(0));
    assertEquals(1, m.models().size());
    assertEquals(1, m.brushes().sideCount(0));
    assertEquals(0, m.brushSides().plane(0));
    assertEquals(4, m.vertices().count());
    assertEquals(0.25f, m.vertices().texCoord(0, 0));
    assertEquals(0xff0000ff, m.vertices().rgba(0));
    assertEquals(2, m.meshVertices().get(m.meshVertices().count() - 1));
    assertEquals("fog/test", m.effects().getFirst().name());
    assertEquals(1, m.faces().firstVertex(0));
    assertEquals(SurfaceType.PLANAR, m.faces().surfaceType(0));
    assertEquals(1, m.faces().normal(0, 2));
    assertEquals(200, m.lightmaps().unsigned(0, 0));
    assertEquals(0x010203, m.lightGrid().ambient(0));
    assertEquals(8, m.lightGrid().longitude(0));
    assertTrue(m.visibility().visible(0, 0));
    assertFalse(m.visibility().visible(0, 1));
    assertTrue(m.diagnostics().isEmpty(), m.diagnostics().toString());
    byte[] copy = m.lightmaps().page(0);
    copy[0] = 0;
    assertEquals(200, m.lightmaps().unsigned(0, 0));
    float[] positions = m.vertices().positionsCopy();
    positions[0] = 99;
    assertEquals(-128, m.vertices().position(0, 0));
  }

  @Test
  void acceptsTheRtcwVersion47HeaderAsAnExtension() {
    byte[] bytes = BspFixture.map(false);
    view(bytes).putInt(4, BspMap.VERSION_EXTENDED);
    BspMap map = BspReader.read(bytes);
    assertTrue(map.extendedVersion());
    assertTrue(noted(map, "version 47"));
    view(bytes).putInt(4, 45);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
  }

  @Test
  void acceptsOverlappingLumpsWithADiagnostic() {
    byte[] bytes = BspFixture.map(false);
    // Point the leaf-brush lump at the leaf-face lump: both hold the single index 0.
    view(bytes).putInt(BspLump.LEAF_BRUSHES.headerOffset(), lump(bytes, BspLump.LEAF_FACES));
    BspMap map = BspReader.read(bytes);
    assertEquals(0, map.leafBrushes().get(0));
    assertTrue(noted(map, "LEAF_FACES and LEAF_BRUSHES overlap"));
  }

  @Test
  void unusableFogIndexMeansNoFog() {
    byte[] bytes = BspFixture.map(false);
    int faces = lump(bytes, BspLump.FACES);
    view(bytes).putInt(faces + 4, 1);
    BspMap map = BspReader.read(bytes);
    assertEquals(-1, map.faces().effect(0));
    assertTrue(noted(map, "fog 1 ignored"));
    // Original q3dm17 flares leave the unused field at zero; that is silently treated as none.
    view(bytes).putInt(BspLump.EFFECTS.headerOffset() + 4, 0);
    view(bytes).putInt(faces + 4, 0).putInt(faces + 8, SurfaceType.FLARE.id());
    BspMap flare = BspReader.read(bytes);
    assertEquals(-1, flare.faces().effect(0));
    assertTrue(flare.diagnostics().isEmpty(), flare.diagnostics().toString());
  }

  @Test
  void validatesPatchControlGrids() {
    byte[] bytes = BspFixture.map(true);
    assertEquals(3, BspReader.read(bytes).faces().patchWidth(0));
    view(bytes).putInt(lump(bytes, BspLump.FACES) + 96, 4);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    view(bytes).putInt(lump(bytes, BspLump.FACES) + 96, BspValidator.MAX_PATCH_SIZE + 2);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
  }

  @Test
  void ignoresOnlyPatchIndexFieldsWhileCheckingItsControlGrid() {
    byte[] bytes = BspFixture.map(true);
    int face = lump(bytes, BspLump.FACES);
    view(bytes).putInt(face + 20, Integer.MAX_VALUE).putInt(face + 24, Integer.MIN_VALUE);
    assertEquals(9, BspReader.read(bytes).vertices().count());
    view(bytes).putInt(face + 16, 10);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    view(bytes).putInt(face + 16, 9).putInt(face + 96, 4);
    assertThrows(BspFormatException.class, () -> BspReader.read(bytes));

    byte[] clean = BspFixture.map(true);
    byte[] indexed = Arrays.copyOf(clean, clean.length + 12);
    int mesh = BspLump.MESH_VERTICES.headerOffset();
    view(indexed).putInt(mesh, clean.length).putInt(mesh + 4, 12);
    view(indexed).putInt(clean.length, 1098907648).putInt(clean.length + 4, -1);
    view(indexed).putInt(lump(indexed, BspLump.FACES) + 24, 3);
    assertEquals(1098907648, BspReader.read(indexed).meshVertices().get(0));
  }

  @Test
  void keepsActiveTriangleIndexBoundsStrictForBothIndexedSurfaceTypes() {
    for (SurfaceType type : new SurfaceType[] {SurfaceType.PLANAR, SurfaceType.TRIANGLE_SOUP}) {
      for (int[] mutation : new int[][] {{20, -1}, {20, Integer.MAX_VALUE}, {24, -1}, {24, 4}}) {
        byte[] bytes = BspFixture.map(false);
        int face = lump(bytes, BspLump.FACES);
        view(bytes).putInt(face + 8, type.id()).putInt(face + mutation[0], mutation[1]);
        assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
      }
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(lump(bytes, BspLump.FACES) + 8, type.id());
      view(bytes).putInt(lump(bytes, BspLump.MESH_VERTICES), 1098907648);
      assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    }
  }

  @Test
  void sanitisesNonFiniteRenderingAttributesButNotPositions() {
    for (int coordinate : new int[] {12, 16, 20, 24, 28, 32, 36}) {
      for (float bad : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
        byte[] bytes = BspFixture.map(false);
        view(bytes).putFloat(lump(bytes, BspLump.VERTICES) + 44 + coordinate, bad);
        BspMap map = BspReader.read(bytes);
        assertTrue(noted(map, "Non-finite vertex attributes replaced with 0 (first at 1)"));
        BspMap.Vertices v = map.vertices();
        float[] values = {
          v.texCoord(1, 0),
          v.texCoord(1, 1),
          v.lightmapCoord(1, 0),
          v.lightmapCoord(1, 1),
          v.normal(1, 0),
          v.normal(1, 1),
          v.normal(1, 2)
        };
        assertEquals(0, values[(coordinate - 12) / 4]);
        assertEquals(0.25f, coordinate == 12 ? 0.25f : v.texCoord(1, 0));
      }
    }
    for (int coordinate : new int[] {0, 4, 8}) {
      byte[] bytes = BspFixture.map(false);
      view(bytes).putFloat(lump(bytes, BspLump.VERTICES) + 44 + coordinate, Float.NaN);
      assertThrows(BspFormatException.class, () -> BspReader.read(bytes));
    }
    byte[] plane = BspFixture.map(false);
    view(plane).putFloat(lump(plane, BspLump.PLANES) + 12, Float.POSITIVE_INFINITY);
    assertThrows(BspFormatException.class, () -> BspReader.read(plane));
  }

  @Test
  void rejectsInvalidHeadersOffsetsAndPartialRecords() {
    assertThrows(BspFormatException.class, () -> BspReader.read(new byte[143]));
    assertThrows(BspFormatException.class, () -> BspReader.read(null));
    for (int[] mutation :
        new int[][] {
          {0, 0},
          {4, 45},
          {8, -1},
          {8, Integer.MAX_VALUE},
          {12, Integer.MAX_VALUE},
          {12, -1},
          {20, 71}
        }) {
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(mutation[0], mutation[1]);
      assertThrows(
          BspFormatException.class, () -> BspReader.read(bytes), Arrays.toString(mutation));
    }
  }

  @Test
  void ignoresPartialLightmapAndLightGridRecords() {
    byte[] bytes = BspFixture.map(false);
    view(bytes).putInt(BspLump.LIGHT_GRID.headerOffset() + 4, 5);
    view(bytes).putInt(BspLump.LIGHTMAPS.headerOffset() + 4, BspMap.LIGHTMAP_BYTES - 1);
    BspMap map = BspReader.read(bytes);
    assertEquals(0, map.lightGrid().count());
    assertEquals(0, map.lightmaps().count());
    assertTrue(noted(map, "trailing bytes of lump LIGHT_GRID"));
  }

  @Test
  void rejectsBadReferencesCyclesAndVisibility() {
    Object[][] mutations = {
      {BspLump.NODES, 4, 0},
      {BspLump.NODES, 4, Integer.MIN_VALUE},
      {BspLump.NODES, 0, 1},
      {BspLump.LEAF_FACES, 0, 1},
      {BspLump.BRUSHES, 4, Integer.MAX_VALUE},
      {BspLump.BRUSHES, 8, 1},
      {BspLump.BRUSH_SIDES, 4, 1},
      {BspLump.VERTICES, 0, 0x7fc00000},
      {BspLump.MESH_VERTICES, 0, -1},
      {BspLump.MESH_VERTICES, 8, 3},
      {BspLump.FACES, 0, 1},
      {BspLump.EFFECTS, 64, 1},
      {BspLump.VISIBILITY, 0, Integer.MAX_VALUE},
      {BspLump.VISIBILITY, 4, 0}
    };
    for (Object[] mutation : mutations) {
      byte[] bytes = BspFixture.map(false);
      view(bytes).putInt(lump(bytes, (BspLump) mutation[0]) + (int) mutation[1], (int) mutation[2]);
      assertThrows(
          BspFormatException.class, () -> BspReader.read(bytes), Arrays.toString(mutation));
    }
  }

  @Test
  void toleratesWhatTheOriginalEngineToleratesAndReportsItStrictly() {
    byte[] bytes = BspFixture.map(false);
    int faces = lump(bytes, BspLump.FACES);
    view(bytes).putInt(faces + 28, 1); // lightmap beyond the single page
    view(bytes).putInt(lump(bytes, BspLump.LEAVES), 5); // cluster beyond the visibility data
    BspMap map = BspReader.read(bytes);
    var issues = BspValidator.strictIssues(map);
    assertTrue(issues.stream().anyMatch(i -> i.contains("lightmap 1")), issues.toString());
    assertTrue(issues.stream().anyMatch(i -> i.contains("cluster 5")), issues.toString());
    view(bytes).putInt(faces + 8, 7); // unknown surface type: renderers skip it
    BspMap unknown = BspReader.read(bytes);
    assertEquals(SurfaceType.BAD, unknown.faces().surfaceType(0));
    assertTrue(BspValidator.strictIssues(unknown).stream().anyMatch(i -> i.contains("type 7")));
  }

  @Test
  void visibilityNeedsEnoughBytesButMayHaveMore() {
    byte[] bytes = BspFixture.map(false);
    view(bytes).putInt(BspLump.VISIBILITY.headerOffset() + 4, 0);
    assertTrue(BspReader.read(bytes).visibility().visible(0, 10));
    assertTrue(BspReader.read(bytes).visibility().absent());

    byte[] clean = BspFixture.map(false);
    byte[] padded = Arrays.copyOf(clean, clean.length + 3);
    view(padded).putInt(BspLump.VISIBILITY.headerOffset() + 4, 12);
    BspMap map = BspReader.read(padded);
    assertEquals(1, map.visibility().clusters());
    assertTrue(noted(map, "extra visibility bytes"));

    byte[] shortVis = BspFixture.map(false);
    view(shortVis)
        .putInt(lump(shortVis, BspLump.VISIBILITY), 2)
        .putInt(lump(shortVis, BspLump.VISIBILITY) + 4, 1);
    assertThrows(BspFormatException.class, () -> BspReader.read(shortVis));
  }

  @Test
  void deterministicMalformedHeaderFuzzHasOnlyDescriptiveFailures() {
    Random random = new Random(46);
    for (int i = 0; i < 2000; i++) {
      byte[] bytes = BspFixture.map(i % 2 == 0);
      int where = random.nextInt(BspLump.HEADER_BYTES);
      bytes[where] = (byte) random.nextInt(256);
      try {
        BspReader.read(bytes);
      } catch (BspFormatException expected) {
        assertNotNull(expected.getMessage());
      }
    }
  }
}

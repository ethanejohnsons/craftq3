package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code LightGridTest}. */
class LightGridTest {
  private static final double EPSILON = 1e-6;

  @Test
  void decodesQ3PolarAndAzimuthBytes() {
    assertVector(new Vec3f(0, 0, 1), LightGrid.decodeDirection(0, 0));
    assertVector(new Vec3f(1, 0, 0), LightGrid.decodeDirection(64, 0));
    assertVector(new Vec3f(0, 1, 0), LightGrid.decodeDirection(64, 64));
    assertVector(new Vec3f(0, 0, -1), LightGrid.decodeDirection(128, 0));
  }

  @Test
  void trilinearlyInterpolatesXFastestCellsAndRestoresMapBrightness() {
    ByteArrayOutputStream cells = new ByteArrayOutputStream();
    for (int i = 0; i < 8; i++) cell(cells, i % 2 == 0 ? 0x102030 : 0x304050, 0x081018, 64, 0);
    LightGrid grid = LightGrid.of(map(cells, "64 64 128", new float[] {0, 0, 0, 64, 64, 128}));
    assertTrue(grid.available());
    assertEquals(new LightGrid.Dimensions(2, 2, 2), grid.dimensions());
    assertEquals(new Vec3f(0, 0, 0), grid.origin());
    LightGrid.Sample middle = grid.sample(new Vec3f(32, 32, 64));
    // The right sample saturates with hue preservation before interpolation.
    assertEquals((64.0 / 255 + 0.6) / 2, middle.ambient().x(), EPSILON);
    assertEquals((128.0 / 255 + 0.8) / 2, middle.ambient().y(), EPSILON);
    assertEquals((192.0 / 255 + 1) / 2, middle.ambient().z(), EPSILON);
    assertVector(new Vec3f(32f / 255, 64f / 255, 96f / 255), middle.directed());
    assertVector(new Vec3f(1, 0, 0), middle.direction());
    assertEquals(0.6, grid.sample(new Vec3f(1000, 1000, 1000)).ambient().x(), EPSILON);
    assertEquals(64.0 / 255, grid.sample(new Vec3f(-1000, -1000, -1000)).ambient().x(), EPSILON);
    assertEquals(8 * LightGrid.CELL_FLOATS, grid.packed().length);
  }

  @Test
  void skipsWallSamplesAndNormalizesRemainingWeights() {
    ByteArrayOutputStream cells = new ByteArrayOutputStream();
    cell(cells, 0, 0, 0, 0);
    cell(cells, 0x202020, 0x101010, 64, 64);
    LightGrid grid = LightGrid.of(map(cells, "16 16 16", new float[] {3, 2, 1, 32, 16, 16}));
    assertEquals(new Vec3f(16, 16, 16), grid.origin());
    assertEquals(new LightGrid.Dimensions(2, 1, 1), grid.dimensions());
    LightGrid.Sample sample = grid.sample(new Vec3f(24, 16, 16));
    assertVector(new Vec3f(128f / 255, 128f / 255, 128f / 255), sample.ambient());
    assertVector(new Vec3f(0, 1, 0), sample.direction());
  }

  @Test
  void usesSafeFallbackForAbsentOrMismatchedGridData() {
    assertFalse(LightGrid.empty().available());
    ByteArrayOutputStream cells = new ByteArrayOutputStream();
    cell(cells, 1, 1, 0, 0);
    LightGrid grid = LightGrid.of(map(cells, "0 nan invalid", new float[] {0, 0, 0, 64, 64, 128}));
    assertEquals(new Vec3f(64, 64, 128), grid.cellSize());
    assertFalse(grid.available());
    assertVector(new Vec3f(1, 1, 1), grid.sample(new Vec3f(0, 0, 0)).ambient());
  }

  @Test
  void optionalSamplesDoNotTurnMissingOrSolidCellsIntoLight() {
    Vec3f point = new Vec3f(0, 0, 0);
    assertTrue(LightGrid.empty().sampleIfPresent(point).isEmpty());
    ByteArrayOutputStream solid = new ByteArrayOutputStream();
    cell(solid, 0, 0, 0, 0);
    LightGrid grid = LightGrid.of(map(solid, "64 64 128", new float[6]));
    assertTrue(grid.available());
    assertTrue(grid.sampleIfPresent(point).isEmpty());
    assertVector(new Vec3f(1, 1, 1), grid.sample(point).ambient());
    ByteArrayOutputStream lit = new ByteArrayOutputStream();
    cell(lit, 0x202020, 0x101010, 0, 0);
    LightGrid valid = LightGrid.of(map(lit, "64 64 128", new float[6]));
    assertEquals(valid.sample(point), valid.sampleIfPresent(point).orElseThrow());
  }

  private static void cell(ByteArrayOutputStream out, int ambient, int directed, int lat, int lng) {
    out.write(ambient >>> 16);
    out.write(ambient >>> 8);
    out.write(ambient);
    out.write(directed >>> 16);
    out.write(directed >>> 8);
    out.write(directed);
    out.write(lat);
    out.write(lng);
  }

  private static BspMap map(ByteArrayOutputStream cells, String size, float[] bounds) {
    TestBsp bsp =
        new TestBsp()
            .entities("{\n\"classname\" \"worldspawn\"\n\"gridsize\" \"" + size + "\"\n}\n");
    bsp.model(
        new float[] {bounds[0], bounds[1], bounds[2]},
        new float[] {bounds[3], bounds[4], bounds[5]},
        0,
        0,
        0,
        0);
    return bsp.lightGrid(cells.toByteArray()).read();
  }

  private static void assertVector(Vec3f expected, Vec3f actual) {
    assertEquals(expected.x(), actual.x(), EPSILON);
    assertEquals(expected.y(), actual.y(), EPSILON);
    assertEquals(expected.z(), actual.z(), EPSILON);
  }
}

package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LightGridTest {
  @Test
  void decodesQ3PolarAndAzimuthBytes() {
    assertVector(new Vec3(0, 0, 1), LightGrid.decodeDirection(0, 0));
    assertVector(new Vec3(1, 0, 0), LightGrid.decodeDirection(64, 0));
    assertVector(new Vec3(0, 1, 0), LightGrid.decodeDirection(64, 64));
    assertVector(new Vec3(0, 0, -1), LightGrid.decodeDirection(128, 0));
  }

  @Test
  void trilinearlyInterpolatesXFastestCellsAndRestoresMapBrightness() throws Exception {
    var cells = new ArrayList<BspMap.LightVolume>();
    for (int i = 0; i < 8; i++)
      cells.add(new BspMap.LightVolume(i % 2 == 0 ? 0x102030 : 0x304050, 0x081018, 64, 0));
    var grid = new LightGrid(map(cells, "64 64 128", RenderFixtures.box(0, 0, 0, 64, 64, 128)));
    assertTrue(grid.available());
    assertEquals(new LightGrid.Dimensions(2, 2, 2), grid.dimensions());
    assertEquals(new Vec3(0, 0, 0), grid.origin());
    var middle = grid.sample(new Vec3(32, 32, 64));
    // The right sample saturates with hue preservation before interpolation.
    assertEquals((64.0 / 255 + 0.6) / 2, middle.ambient().x(), 1e-8);
    assertEquals((128.0 / 255 + 0.8) / 2, middle.ambient().y(), 1e-8);
    assertEquals((192.0 / 255 + 1) / 2, middle.ambient().z(), 1e-8);
    assertVector(new Vec3(32.0 / 255, 64.0 / 255, 96.0 / 255), middle.directed());
    assertVector(new Vec3(1, 0, 0), middle.direction());
    assertEquals(0.6, grid.sample(new Vec3(1000, 1000, 1000)).ambient().x(), 1e-8);
    assertEquals(64.0 / 255, grid.sample(new Vec3(-1000, -1000, -1000)).ambient().x(), 1e-8);
  }

  @Test
  void skipsWallSamplesAndNormalizesRemainingWeights() throws Exception {
    List<BspMap.LightVolume> cells =
        List.of(
            new BspMap.LightVolume(0, 0, 0, 0), new BspMap.LightVolume(0x202020, 0x101010, 64, 64));
    var grid = new LightGrid(map(cells, "16 16 16", RenderFixtures.box(3, 2, 1, 32, 16, 16)));
    assertEquals(new Vec3(16, 16, 16), grid.origin());
    assertEquals(new LightGrid.Dimensions(2, 1, 1), grid.dimensions());
    var sample = grid.sample(new Vec3(24, 16, 16));
    assertVector(new Vec3(128.0 / 255, 128.0 / 255, 128.0 / 255), sample.ambient());
    assertVector(new Vec3(0, 1, 0), sample.direction());
  }

  @Test
  void usesSafeFallbackForAbsentOrMismatchedGridData() throws Exception {
    assertFalse(new LightGrid(null).available());
    var map =
        map(
            List.of(new BspMap.LightVolume(1, 1, 0, 0)),
            "0 nan invalid",
            RenderFixtures.box(0, 0, 0, 64, 64, 128));
    var grid = new LightGrid(map);
    assertEquals(new Vec3(64, 64, 128), grid.cellSize());
    assertFalse(grid.available());
    assertVector(new Vec3(1, 1, 1), grid.sample(new Vec3(0, 0, 0)).ambient());
  }

  @Test
  void optionalSamplesDoNotTurnMissingOrSolidCellsIntoLight() throws Exception {
    var point = new Vec3(0, 0, 0);
    assertTrue(new LightGrid(null).sampleIfPresent(point).isEmpty());
    var grid =
        new LightGrid(
            map(
                List.of(new BspMap.LightVolume(0, 0, 0, 0)),
                "64 64 128",
                RenderFixtures.box(0, 0, 0, 0, 0, 0)));
    assertTrue(grid.available());
    assertTrue(grid.sampleIfPresent(point).isEmpty());
    assertVector(new Vec3(1, 1, 1), grid.sample(point).ambient());
    var valid =
        new LightGrid(
            map(
                List.of(new BspMap.LightVolume(0x202020, 0x101010, 0, 0)),
                "64 64 128",
                RenderFixtures.box(0, 0, 0, 0, 0, 0)));
    assertEquals(valid.sample(point), valid.sampleIfPresent(point).orElseThrow());
  }

  private static BspMap map(List<BspMap.LightVolume> cells, String size, BspMap.Bounds bounds)
      throws Exception {
    BspMap base = BspReader.read(BspFixture.map(false));
    return new BspMap(
        List.of(Map.of("classname", "worldspawn", "gridsize", size)),
        base.textures(),
        base.planes(),
        base.nodes(),
        base.leaves(),
        base.leafFaces(),
        base.leafBrushes(),
        List.of(new BspMap.Model(bounds, 0, 1, 0, 1)),
        base.brushes(),
        base.brushSides(),
        base.vertices(),
        base.meshVertices(),
        base.effects(),
        base.faces(),
        base.lightmaps(),
        cells,
        base.visibility());
  }

  private static void assertVector(Vec3 expected, Vec3 actual) {
    assertEquals(expected.x(), actual.x(), 1e-8);
    assertEquals(expected.y(), actual.y(), 1e-8);
    assertEquals(expected.z(), actual.z(), 1e-8);
  }
}

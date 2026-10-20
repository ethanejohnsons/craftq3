package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspFixture;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.render.material.StageEvaluator;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code BspSceneBuilderTest}, plus static buffer and batch layout. */
class WorldGeometryTest {
  @Test
  void respectsRelativeIndicesAndSpawn() {
    BspMap map = BspReader.read(BspFixture.map(false));
    RenderScene scene = TestBsp.scene(map);
    WorldGeometry world = scene.world();
    assertEquals(1, world.triangleCount());
    assertEquals(3, world.vertexCount());
    assertEquals(map.vertices().position(1), position(world, world.indices()[0]));
    assertEquals(map.vertices().position(3), position(world, world.indices()[2]));
    assertEquals(new Vec3f(0, -200, 126), scene.spawnCamera().origin());
    assertEquals(90, scene.spawnCamera().yaw());
    assertEquals(map.vertices().normal(1), normal(world, 0));
    assertEquals(map.vertices().rgba(1), world.vertexArrays().rgba(0), "saturated red keeps hue");
    assertEquals(1, world.surfaceCount());
    assertEquals("textures/test", scene.material(world.surfaceMaterial(0)).name());
    assertEquals(0, world.surfaceLightmap(0));
    assertEquals(0, world.surfaceFog(0));
    assertEquals(0, world.surfaceFirstVertex(0));
    assertEquals(3, world.surfaceVertexCount(0));
    assertEquals(3, world.surfaceIndexCount(0));
    assertEquals(-128, world.surfaceBound(0, 0));
    assertEquals(-128, world.surfaceBound(0, 1));
    assertEquals(128, world.surfaceBound(0, 3));
    assertEquals(128, world.surfaceBound(0, 4));
    assertEquals(1, world.batches().size());
    DrawBatch batch = world.batches().getFirst();
    assertEquals(0, batch.model());
    assertEquals(0, batch.lightmap());
    assertEquals(0, batch.fog());
    assertEquals(3, batch.indexCount());
  }

  @Test
  void evaluatesCurvatureAndPreservesCorners() {
    BspMap map = BspReader.read(BspFixture.map(true));
    WorldGeometry world = TestBsp.scene(map, 2).world();
    assertEquals(8, world.triangleCount());
    assertEquals(9, world.vertexCount(), "blocks share one sample grid");
    int[] indices = world.indices();
    assertEquals(new Vec3f(-128, -128, 0), position(world, indices[0]));
    boolean center = false, corner = false;
    for (int v = 0; v < world.vertexCount(); v++) {
      center |= position(world, v).equals(new Vec3f(0, 0, 25));
      corner |= position(world, v).equals(new Vec3f(128, 128, 0));
    }
    assertTrue(center && corner);
    Vec3f a = position(world, indices[0]), b = position(world, indices[1]);
    Vec3f c = position(world, indices[2]);
    // Synthetic controls have +Z normals; Q3's indexed geometry winds clockwise from that side.
    assertTrue(b.subtract(a).cross(c.subtract(a)).z() < 0);
    assertThrows(IllegalArgumentException.class, () -> TestBsp.scene(map, 0));
    assertThrows(IllegalArgumentException.class, () -> TestBsp.scene(map, 17));
  }

  @Test
  void interpolatesPatchUvsColorsAndNormals() {
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture("textures/test", 0);
    for (int i = 0; i < 9; i++)
      bsp.vertex(
          (i % 3 - 1) * 128,
          (i / 3 - 1) * 128,
          i == 4 ? 100 : 0,
          (i % 3) / 2f,
          (i / 3) / 2f,
          (i % 3) / 4f,
          (i / 3) / 4f,
          0,
          0,
          2,
          i == 4 ? 0xff0000ff : 0x000000ff);
    bsp.patch(texture, 0, 0, 3, 3);
    bsp.model(new float[] {-256, -256, -256}, new float[] {256, 256, 256}, 0, 1, 0, 0);
    WorldGeometry world = TestBsp.scene(bsp.read(), 2).world();
    int center = -1;
    for (int v = 0; v < world.vertexCount(); v++)
      if (position(world, v).equals(new Vec3f(0, 0, 25))) center = v;
    VertexArrays arrays = world.vertexArrays();
    assertEquals(0.5f, arrays.texCoords()[center * 2]);
    assertEquals(0.5f, arrays.texCoords()[center * 2 + 1]);
    assertEquals(0.25f, arrays.lightmapCoords()[center * 2]);
    assertEquals(0.25f, arrays.lightmapCoords()[center * 2 + 1]);
    assertEquals(new Vec3f(0, 0, 1), normal(world, center));
    // The bilinear centre weight is 1/4: red 63.75 rounds to 0x40 before the overbright restore.
    assertEquals(StageEvaluator.restoreMapLightColor(0x400000ff, 2), arrays.rgba(center));
    assertEquals(9, world.vertexCount());
    assertEquals(24, world.surfaceIndexCount(0));
  }

  @Test
  void batchesGroupByMaterialLightmapAndFogAndKeepNodrawGeometryOutside() {
    TestBsp bsp = new TestBsp();
    int rock = bsp.texture("textures/rock", 0);
    int glass = bsp.texture("textures/glass", 0);
    int hidden = bsp.texture("textures/hidden", WorldGeometry.SURF_NODRAW);
    int sky = bsp.texture("textures/sky", 0);
    for (int i = 0; i < 3; i++) bsp.vertex(i, i == 1 ? 1 : 0, 0, 0, 0, 0, 0, 0, 0, 1, -1);
    float[] up = {0, 0, 1};
    int f0 = bsp.planar(rock, -1, 0, 0, 3, up, 0, 1, 2);
    int f1 = bsp.planar(glass, -1, 0, 0, 3, up, 0, 1, 2);
    int f2 = bsp.planar(rock, -1, 1, 0, 3, up, 0, 1, 2);
    int f3 = bsp.planar(rock, -1, 0, 0, 3, up, 0, 1, 2);
    int f4 = bsp.planar(hidden, -1, 0, 0, 3, up, 0, 1, 2);
    int f5 = bsp.planar(sky, -1, 0, 0, 3, up, 0, 1, 2);
    bsp.model(new float[] {-8, -8, -8}, new float[] {8, 8, 8}, 0, 5, 0, 0);
    bsp.model(new float[] {-8, -8, -8}, new float[] {8, 8, 8}, 5, 1, 0, 0);
    ShaderLibrary library =
        ShaderLibrary.of(
            List.of(
                new ShaderLibrary.Script(
                    "scripts/t.shader",
                    """
                    textures/glass
                    {
                      {
                        map textures/glass.tga
                        blendFunc add
                      }
                    }
                    textures/sky
                    {
                      surfaceparm sky
                      skyparms env/a 512 -
                    }
                    """)));
    RenderScene scene = RenderScene.build("batches", bsp.read(), library::resolve);
    WorldGeometry world = scene.world();
    assertEquals(4, world.batches().size(), "rock/lm0, rock/lm1, glass, then model 1 sky");
    assertEquals(world.surfaceBatch(f0), world.surfaceBatch(f3));
    assertTrue(world.surfaceBatch(f0) != world.surfaceBatch(f2));
    assertEquals(-1, world.surfaceBatch(f4));
    assertEquals(3, world.surfaceIndexCount(f4), "nodraw triangles kept for marks");
    assertTrue(world.surfaceFirstIndex(f4) >= world.unbatchedFirstIndex());
    assertEquals(3, world.modelBatchCount(0));
    assertEquals(1, world.modelBatchCount(1));
    DrawBatch skyBatch = world.batches().get(world.modelFirstBatch(1));
    assertTrue(skyBatch.sky());
    assertEquals(1, skyBatch.model());
    // Opaque batches sort before the additive glass; their indices are contiguous.
    DrawBatch last = world.batches().get(2);
    assertEquals(world.surfaceBatch(f1), last.index());
    DrawBatch first = world.batches().getFirst();
    assertEquals(2, first.surfaceCount());
    assertEquals(6, first.indexCount());
    assertEquals(f0, world.batchSurface(first.firstSurface()));
    assertEquals(f3, world.batchSurface(first.firstSurface() + 1));
    assertEquals(first.firstIndex() + 3, world.surfaceFirstIndex(f3));
    for (int b = 1; b < world.modelBatchCount(0); b++)
      assertTrue(world.batches().get(b - 1).sortKey() <= world.batches().get(b).sortKey());
    assertEquals(f5, world.batchSurface(skyBatch.firstSurface()));
    assertTrue(scene.material(world.surfaceMaterial(f5)).isSky());
    assertEquals(
        ShaderDefinition.canonicalName("textures/glass"), scene.material(last.material()).name());
  }

  @Test
  void materialRegistryKeepsWorldIndicesAndAppendsNewNames() {
    RenderScene scene = TestBsp.scene(BspReader.read(BspFixture.map(false)));
    var registry = scene.newMaterialRegistry();
    assertEquals(scene.materials().size(), registry.size());
    assertEquals(scene.material(0), registry.program(0));
    int added = registry.index("models/new", false);
    assertEquals(scene.materials().size(), added);
    assertEquals(added, registry.index("models/new", false));
  }

  static Vec3f position(WorldGeometry world, int v) {
    float[] p = world.vertexArrays().positions();
    return new Vec3f(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]);
  }

  static Vec3f normal(WorldGeometry world, int v) {
    float[] n = world.vertexArrays().normals();
    return new Vec3f(n[v * 3], n[v * 3 + 1], n[v * 3 + 2]);
  }
}

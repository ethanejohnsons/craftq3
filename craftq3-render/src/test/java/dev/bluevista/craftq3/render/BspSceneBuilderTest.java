package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class BspSceneBuilderTest {
  @Test
  void respectsRelativeIndicesAndSpawn() throws Exception {
    var map = BspReader.read(BspFixture.map(false));
    var scene = BspSceneBuilder.build("test", map, 8);
    assertEquals(1, scene.triangles().size());
    assertEquals(map.vertices().get(1).position(), scene.triangles().getFirst().a());
    assertEquals(map.vertices().get(3).position(), scene.triangles().getFirst().c());
    assertEquals(new Vec3(0, -200, 126), scene.camera().origin());
    assertEquals(90, scene.camera().yaw());
    assertEquals(3, scene.vertices().size());
    assertEquals(map.vertices().get(1), scene.vertices().getFirst());
    assertEquals(1, scene.surfaces().size());
    var surface = scene.surfaces().getFirst();
    assertEquals("textures/test", surface.shaderName());
    assertEquals(0, surface.lightmap());
    assertEquals(0, surface.firstVertex());
    assertEquals(3, surface.vertexCount());
    assertEquals(new Vec3(-128, -128, 0), surface.bounds().min());
    assertEquals(new Vec3(128, 128, 0), surface.bounds().max());
  }

  @Test
  void evaluatesCurvatureAndPreservesCorners() throws Exception {
    var map = BspReader.read(BspFixture.map(true));
    var scene = BspSceneBuilder.build("patch", map, 2);
    assertEquals(8, scene.triangles().size());
    assertEquals(new Vec3(-128, -128, 0), scene.triangles().getFirst().a());
    assertTrue(scene.triangles().stream().anyMatch(t -> t.b().equals(new Vec3(0, 0, 25))));
    assertTrue(scene.vertices().stream().anyMatch(v -> v.position().equals(new Vec3(128, 128, 0))));
    var first = scene.triangles().getFirst();
    Vec3 ab = first.b().add(first.a().scale(-1));
    Vec3 ac = first.c().add(first.a().scale(-1));
    // Synthetic controls have +Z normals; Q3's indexed geometry winds clockwise from that side.
    assertTrue(ab.x() * ac.y() - ab.y() * ac.x() < 0);
    assertThrows(IllegalArgumentException.class, () -> BspSceneBuilder.build("patch", map, 0));
    assertThrows(IllegalArgumentException.class, () -> BspSceneBuilder.build("patch", map, 17));
  }

  @Test
  void interpolatesPatchUvsColorsAndNormals() throws Exception {
    var source = BspReader.read(BspFixture.map(true));
    var controls = new ArrayList<BspMap.Vertex>();
    for (int i = 0; i < source.vertices().size(); i++) {
      var vertex = source.vertices().get(i);
      controls.add(
          new BspMap.Vertex(
              vertex.position(),
              new BspMap.Uv((i % 3) / 2f, (i / 3) / 2f),
              new BspMap.Uv((i % 3) / 4f, (i / 3) / 4f),
              new Vec3(0, 0, 2),
              i == 4 ? 0xff0000ff : 0x000000ff));
    }
    var map =
        new BspMap(
            source.entities(),
            source.textures(),
            source.planes(),
            source.nodes(),
            source.leaves(),
            source.leafFaces(),
            source.leafBrushes(),
            source.models(),
            source.brushes(),
            source.brushSides(),
            controls,
            source.meshVertices(),
            source.effects(),
            source.faces(),
            source.lightmaps(),
            source.lightVolumes(),
            source.visibility());
    var scene = BspSceneBuilder.build("patch", map, 2);
    var center =
        scene.vertices().stream()
            .filter(v -> v.position().equals(new Vec3(0, 0, 25)))
            .findFirst()
            .orElseThrow();
    assertEquals(new BspMap.Uv(0.5f, 0.5f), center.textureUv());
    assertEquals(new BspMap.Uv(0.25f, 0.25f), center.lightmapUv());
    assertEquals(new Vec3(0, 0, 1), center.normal());
    assertEquals(0x400000ff, center.rgba());
    assertEquals(24, scene.vertices().size());
    assertEquals(24, scene.surfaces().getFirst().vertexCount());
  }

  @Test
  void sceneCanShareImmutableGeometryAcrossFrames() throws Exception {
    var scene = BspSceneBuilder.build("test", BspReader.read(BspFixture.map(false)), 8);
    var next = scene.withCamera(new RenderScene.Camera(new Vec3(1, 2, 3), 0, 0, 100));
    assertSame(scene.triangles(), next.triangles());
    assertSame(scene.vertices(), next.vertices());
    assertSame(scene.surfaces(), next.surfaces());
    assertSame(scene.bsp(), next.bsp());
    assertNotEquals(scene.camera(), next.camera());
    assertThrows(UnsupportedOperationException.class, () -> scene.triangles().clear());
  }
}

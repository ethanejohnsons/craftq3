package dev.bluevista.craftq3.engine.formats.bsp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import dev.bluevista.craftq3.engine.formats.image.ImageLoader;
import dev.bluevista.craftq3.engine.formats.image.LoadedImage;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.AlphaFunc;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import org.junit.jupiter.api.Test;

class ShaderFixtureTest {
  @Test
  void generatedRegressionSceneLoadsWithoutExternalAssets() {
    MemoryAssets assets = ShaderFixture.assets();
    BspMap map = BspReader.read(assets.read("maps/craftq3_shaderlab.bsp").orElseThrow());
    assertEquals(12, map.faces().count());
    assertEquals(53, map.vertices().count());
    assertEquals(1, map.lightmaps().count());
    int patches = 0;
    for (int face = 0; face < map.faces().count(); face++)
      if (map.faces().surfaceType(face) == SurfaceType.PATCH) patches++;
    assertEquals(1, patches);
    assertEquals(6, map.brushes().sideCount(0));
    assertEquals("0 -480 164", map.entities().get(1).get("origin").orElseThrow());
    ShaderLibrary shaders = ShaderLibrary.load(assets);
    assertEquals(12, shaders.definitions().size());
    assertEquals(
        2, shaders.definitions().values().stream().filter(ShaderDefinition::skySurface).count());
    assertEquals(
        2, map.textures().stream().filter(texture -> (texture.surfaceFlags() & 4) != 0).count());
    var leftSky = shaders.find("textures/craftq3_shaderlab/sky_left").orElseThrow();
    var rightSky = shaders.find("textures/craftq3_shaderlab/sky_right").orElseThrow();
    assertNotEquals(
        leftSky.stages().getFirst().rgbGen().constant(),
        rightSky.stages().getFirst().rgbGen().constant());
    assertTrue(shaders.diagnostics().isEmpty(), shaders.diagnostics().toString());
    assertTrue(shaders.find("textures/craftq3_shaderlab/implicit").isEmpty());
    var mirror = shaders.find("textures/craftq3_shaderlab/mirror").orElseThrow();
    assertTrue(mirror.portal());
    assertTrue(mirror.stages().isEmpty());
    assertEquals(
        AlphaFunc.GE128,
        shaders
            .find("textures/craftq3_shaderlab/cutout")
            .orElseThrow()
            .stages()
            .getFirst()
            .alphaFunc());
    var animation =
        shaders
            .find("textures/craftq3_shaderlab/animation")
            .orElseThrow()
            .stages()
            .getFirst()
            .texture();
    assertNotEquals(animation.atTime(0), animation.atTime(1));
    for (ShaderDefinition shader : shaders.definitions().values()) {
      for (ShaderDefinition.Stage stage : shader.stages()) {
        for (String name : stage.texture().frames()) {
          if (name.startsWith("$")) continue;
          LoadedImage image = ImageLoader.load(assets, name);
          assertFalse(image.missing(), name);
          assertEquals(64, image.image().width());
          assertEquals(64, image.image().height());
        }
      }
    }
    assertFalse(ImageLoader.load(assets, "textures/craftq3_shaderlab/implicit").missing());
  }

  @Test
  void pixelsAndMapAreDeterministicAndIncludeRealAlphaHoles() {
    assertArrayEquals(ShaderFixture.map(), ShaderFixture.map());
    var first = ShaderFixture.images();
    var second = ShaderFixture.images();
    for (String name : first.keySet()) assertArrayEquals(first.get(name), second.get(name));
    byte[] rgba = ImageLoader.decodeTga(first.get("cutout")).rgba();
    int transparent = 0;
    int opaque = 0;
    for (int i = 3; i < rgba.length; i += 4) {
      if (rgba[i] == 0) transparent++;
      if (rgba[i] == (byte) 255) opaque++;
    }
    assertTrue(transparent > 1000);
    assertTrue(opaque > 1000);
  }

  @Test
  void indexedPanelWindingMatchesOriginalQ3Faces() {
    BspMap map = BspReader.read(ShaderFixture.map());
    BspMap.Faces faces = map.faces();
    for (int face = 0; face < faces.count(); face++) {
      if (faces.surfaceType(face) == SurfaceType.PATCH) continue;
      for (int t = 0; t < faces.meshVertexCount(face); t += 3) {
        Vec3f a = corner(map, face, t);
        Vec3f b = corner(map, face, t + 1);
        Vec3f c = corner(map, face, t + 2);
        float facing = b.subtract(a).cross(c.subtract(a)).dot(faces.normal(face));
        assertTrue(facing < 0, "Q3 indexed winding must oppose the surface normal");
      }
    }
  }

  private static Vec3f corner(BspMap map, int face, int index) {
    int relative = map.meshVertices().get(map.faces().firstMeshVertex(face) + index);
    return map.vertices().position(map.faces().firstVertex(face) + relative);
  }
}

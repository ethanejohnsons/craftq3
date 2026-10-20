package dev.bluevista.craftq3.assets.bsp;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.image.ImageLoader;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition.AlphaFunc;
import dev.bluevista.craftq3.assets.shader.ShaderLibrary;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShaderFixtureTest {
  @TempDir Path root;

  @Test
  void generatedRegressionSceneLoadsWithoutExternalAssets() throws Exception {
    ShaderFixture.write(root.resolve("baseq3"));
    try (var fs = Pk3FileSystem.mount(root, "baseq3")) {
      var map = BspReader.read(fs.read(new VirtualPath("maps/craftq3_shaderlab.bsp")));
      assertEquals(12, map.faces().size());
      assertEquals(53, map.vertices().size());
      assertEquals(1, map.lightmaps().size());
      assertEquals(1, map.faces().stream().filter(face -> face.type() == 2).count());
      assertEquals(6, map.brushes().getFirst().sideCount());
      assertEquals("0 -480 164", map.entities().get(1).get("origin"));
      var shaders = ShaderLibrary.load(fs);
      assertEquals(12, shaders.definitions().size());
      assertEquals(
          2, shaders.definitions().values().stream().filter(shader -> shader.skySurface()).count());
      assertEquals(
          2, map.textures().stream().filter(texture -> (texture.flags() & 4) != 0).count());
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
      for (var shader : shaders.definitions().values()) {
        for (var stage : shader.stages()) {
          for (String name : stage.texture().frames()) {
            if (name.startsWith("$")) continue;
            var image = ImageLoader.load(fs, name);
            assertTrue(image.diagnostic().isEmpty(), name);
            assertEquals(64, image.image().width());
            assertEquals(64, image.image().height());
          }
        }
      }
      assertTrue(
          ImageLoader.load(fs, "textures/craftq3_shaderlab/implicit").diagnostic().isEmpty());
    }
  }

  @Test
  void pixelsAndMapAreDeterministicAndIncludeRealAlphaHoles() throws Exception {
    assertArrayEquals(ShaderFixture.map(), ShaderFixture.map());
    var first = ShaderFixture.images();
    var second = ShaderFixture.images();
    for (String name : first.keySet()) assertArrayEquals(first.get(name), second.get(name));
    var cutout = ImageLoader.decodeTga(first.get("cutout"));
    byte[] rgba = cutout.rgba();
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
  void indexedPanelWindingMatchesOriginalQ3Faces() throws Exception {
    var map = BspReader.read(ShaderFixture.map());
    for (var face : map.faces()) {
      if (face.type() == 2) continue;
      for (int triangle = 0; triangle < face.meshVertexCount(); triangle += 3) {
        var a =
            map.vertices()
                .get(face.firstVertex() + map.meshVertices().get(face.firstMeshVertex() + triangle))
                .position();
        var b =
            map.vertices()
                .get(
                    face.firstVertex()
                        + map.meshVertices().get(face.firstMeshVertex() + triangle + 1))
                .position();
        var c =
            map.vertices()
                .get(
                    face.firstVertex()
                        + map.meshVertices().get(face.firstMeshVertex() + triangle + 2))
                .position();
        var ab = b.add(a.scale(-1));
        var ac = c.add(a.scale(-1));
        double crossX = ab.y() * ac.z() - ab.z() * ac.y();
        double crossY = ab.z() * ac.x() - ab.x() * ac.z();
        double crossZ = ab.x() * ac.y() - ab.y() * ac.x();
        double facing =
            crossX * face.normal().x() + crossY * face.normal().y() + crossZ * face.normal().z();
        assertTrue(facing < 0, "Q3 indexed winding must oppose the surface normal");
      }
    }
  }
}

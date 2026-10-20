package dev.bluevista.craftq3.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.bsp.BspFixture;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterialLibraryTest {
  @TempDir Path installation;

  @Test
  void restoresLightmapHeadroomWithHuePreservationAndOpaqueAlpha() throws Exception {
    Files.createDirectories(installation.resolve("baseq3"));
    var base = BspReader.read(BspFixture.map(false));
    byte[] pixels = new byte[128 * 128 * 3];
    pixels[0] = 63;
    pixels[1] = 20;
    pixels[2] = 10;
    pixels[3] = (byte) 255;
    pixels[4] = (byte) 128;
    pixels[5] = 64;
    var map =
        new BspMap(
            base.entities(),
            base.textures(),
            base.planes(),
            base.nodes(),
            base.leaves(),
            base.leafFaces(),
            base.leafBrushes(),
            base.models(),
            base.brushes(),
            base.brushSides(),
            base.vertices(),
            base.meshVertices(),
            base.effects(),
            base.faces(),
            List.of(new BspMap.Bytes(pixels)),
            base.lightVolumes(),
            base.visibility());
    try (var fs = Pk3FileSystem.mount(installation, "baseq3")) {
      var library = MaterialLibrary.load(fs, BspSceneBuilder.build("lightmap", map, 2));
      byte[] rgba = library.lightmaps().getFirst().rgba();
      assertArrayEquals(
          new byte[] {(byte) 252, 80, 40, (byte) 255, (byte) 255, (byte) 128, 64, (byte) 255},
          java.util.Arrays.copyOf(rgba, 8));
      assertEquals(255, Byte.toUnsignedInt(rgba[11]));
      assertEquals(1, library.surfaces().size());
      assertTrue(library.images().containsKey("$whiteimage"));
      assertTrue(library.images().containsKey("$missing"));
      assertThrows(UnsupportedOperationException.class, () -> library.lightmaps().clear());
      assertThrows(UnsupportedOperationException.class, () -> library.surfaces().clear());
    }
  }
}

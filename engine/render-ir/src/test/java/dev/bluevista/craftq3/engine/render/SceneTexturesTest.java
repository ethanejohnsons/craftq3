package dev.bluevista.craftq3.engine.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.formats.MemoryAssets;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Ported from the legacy {@code MaterialLibraryTest}. */
class SceneTexturesTest {
  @Test
  void restoresLightmapHeadroomWithHuePreservationAndOpaqueAlpha() {
    byte[] pixels = new byte[BspMap.LIGHTMAP_BYTES];
    pixels[0] = 63;
    pixels[1] = 20;
    pixels[2] = 10;
    pixels[3] = (byte) 255;
    pixels[4] = (byte) 128;
    pixels[5] = 64;
    TestBsp bsp = new TestBsp();
    int texture = bsp.texture("textures/test", 0);
    for (int i = 0; i < 3; i++) bsp.vertex(i, i == 2 ? 1 : 0, 0, 0, 0, 0, 0, 0, 0, 1, -1);
    bsp.planar(texture, -1, 0, 0, 3, new float[] {0, 0, 1}, 0, 1, 2);
    bsp.model(new float[] {-1, -1, -1}, new float[] {1, 1, 1}, 0, 1, 0, 0);
    RenderScene scene = TestBsp.scene(bsp.lightmaps(pixels).read());
    SceneTextures textures = SceneTextures.load(new MemoryAssets(), scene);
    byte[] rgba = textures.lightmaps().getFirst().rgba();
    assertArrayEquals(
        new byte[] {(byte) 252, 80, 40, (byte) 255, (byte) 255, (byte) 128, 64, (byte) 255},
        Arrays.copyOf(rgba, 8));
    assertEquals(255, Byte.toUnsignedInt(rgba[11]));
    assertTrue(textures.images().containsKey("$whiteimage"));
    assertTrue(textures.images().containsKey("$missing"));
    assertTrue(textures.images().containsKey("textures/test"), "missing files use the default");
    assertThrows(UnsupportedOperationException.class, () -> textures.lightmaps().clear());
  }
}

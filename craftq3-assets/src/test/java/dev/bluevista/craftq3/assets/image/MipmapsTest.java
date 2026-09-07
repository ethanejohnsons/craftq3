package dev.bluevista.craftq3.assets.image;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MipmapsTest {
  @Test
  void averagesAllChannelsAndIncludesBase() {
    Q3Image image =
        new Q3Image(
            2, 2, new byte[] {-1, 0, 0, 0, 0, -1, 0, 64, 0, 0, -1, (byte) 128, -1, -1, -1, -1});
    var chain = Mipmaps.generate(image);
    assertSame(image, chain.getFirst());
    assertEquals(2, chain.size());
    assertEquals(0x80808070, chain.getLast().rgbaAt(0, 0));
    assertThrows(UnsupportedOperationException.class, () -> chain.add(image));
  }

  @Test
  void oddDimensionsIncludeAllEdgePixels() {
    byte[] bytes = new byte[3 * 3 * 4];
    Arrays.fill(bytes, (byte) 0);
    // A white final row/column occupies five of the nine source pixels.
    for (int y = 0; y < 3; y++) {
      for (int x = 0; x < 3; x++) {
        if (x == 2 || y == 2) Arrays.fill(bytes, (y * 3 + x) * 4, (y * 3 + x + 1) * 4, (byte) 255);
      }
    }
    Q3Image pixel = Mipmaps.generate(new Q3Image(3, 3, bytes)).getLast();
    assertEquals(0x8e8e8e8e, pixel.rgbaAt(0, 0));
  }

  @Test
  void fractionalOddFiltersShareCentralPixelAcrossDestinations() {
    byte[] bytes = new byte[5 * 4];
    Arrays.fill(bytes, 2 * 4, 3 * 4, (byte) 255);
    var chain = Mipmaps.generate(new Q3Image(5, 1, bytes));
    assertEquals(3, chain.size());
    assertEquals(2, chain.get(1).width());
    assertEquals(1, chain.get(1).height());
    assertEquals(0x33333333, chain.get(1).rgbaAt(0, 0));
    assertEquals(0x33333333, chain.get(1).rgbaAt(1, 0));
    assertEquals(0x33333333, chain.get(2).rgbaAt(0, 0));
  }

  @Test
  void handlesSinglePixelAndRectangularChains() {
    Q3Image white = ImageLoader.whiteTexture();
    assertEquals(java.util.List.of(white), Mipmaps.generate(white));
    byte[] bytes = new byte[2 * 8 * 4];
    Arrays.fill(bytes, (byte) 127);
    var chain = Mipmaps.generate(new Q3Image(2, 8, bytes));
    assertEquals(java.util.List.of(2, 1, 1, 1), chain.stream().map(Q3Image::width).toList());
    assertEquals(java.util.List.of(8, 4, 2, 1), chain.stream().map(Q3Image::height).toList());
    assertEquals(0x7f7f7f7f, chain.getLast().rgbaAt(0, 0));
  }
}

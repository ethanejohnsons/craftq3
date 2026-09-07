package dev.bluevista.craftq3.assets.image;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageLoaderTest {
  @TempDir Path root;

  @Test
  void imageOwnsPixelsAndValidatesDimensions() {
    byte[] source = {1, 2, 3, 4};
    Q3Image image = new Q3Image(1, 1, source);
    source[0] = 99;
    image.rgba()[1] = 99;
    assertEquals(0x01020304, image.rgbaAt(0, 0));
    assertEquals(new Q3Image(1, 1, new byte[] {1, 2, 3, 4}), image);
    assertThrows(IllegalArgumentException.class, () -> new Q3Image(0, 1, new byte[0]));
    assertThrows(IllegalArgumentException.class, () -> new Q3Image(1, 1, new byte[3]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Q3Image(Integer.MAX_VALUE, Integer.MAX_VALUE, new byte[0]));
  }

  @Test
  void normalizesAllFourTgaOriginsAndSkipsImageId() throws Exception {
    // Physical file order is red, green, blue, white regardless of the declared origin.
    byte[] bgr = {0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1};
    for (int origin : new int[] {0, 16, 32, 48}) {
      byte[] source = tga(2, 2, 2, 24, origin, bgr);
      byte[] withId = new byte[source.length + 3];
      System.arraycopy(source, 0, withId, 0, 18);
      withId[0] = 3;
      withId[18] = 'I';
      withId[19] = 'D';
      withId[20] = '!';
      System.arraycopy(source, 18, withId, 21, source.length - 18);
      Q3Image decoded = ImageLoader.decodeTga(withId);
      int right = (origin & 16) == 0 ? 0 : 1;
      int top = (origin & 32) == 0 ? 1 : 0;
      assertEquals(0xff0000ff, decoded.rgbaAt(right, top));
      assertEquals(0x00ff00ff, decoded.rgbaAt(1 - right, top));
      assertEquals(0x0000ffff, decoded.rgbaAt(right, 1 - top));
      assertEquals(0xffffffff, decoded.rgbaAt(1 - right, 1 - top));
    }
  }

  @Test
  void preservesQ3AlphaEvenWhenDescriptorHasZeroAttributeBits() throws Exception {
    for (int descriptor : new int[] {0, 8}) {
      Q3Image image = ImageLoader.decodeTga(tga(1, 1, 2, 32, descriptor, new byte[] {3, 2, 1, 64}));
      assertEquals(0x01020340, image.rgbaAt(0, 0));
    }
  }

  @Test
  void decodesMixedRlePacketsIncludingPacketsAcrossRows() throws Exception {
    byte[] encoded = {
      (byte) 0x82,
      0,
      0,
      -1, // Three repeated red pixels.
      2,
      0,
      -1,
      0,
      -1,
      0,
      0,
      -1,
      -1,
      -1, // Three raw pixels: green, blue, white.
      (byte) 0x81,
      0,
      0,
      0 // Two repeated black pixels.
    };
    Q3Image image = ImageLoader.decodeTga(tga(4, 2, 10, 24, 32, encoded));
    assertEquals(0xff0000ff, image.rgbaAt(0, 0));
    assertEquals(0xff0000ff, image.rgbaAt(2, 0));
    assertEquals(0x00ff00ff, image.rgbaAt(3, 0));
    assertEquals(0x0000ffff, image.rgbaAt(0, 1));
    assertEquals(0xffffffff, image.rgbaAt(1, 1));
    assertEquals(0x000000ff, image.rgbaAt(3, 1));
  }

  @Test
  void decodesGrayscaleAndGrayscaleAlpha() throws Exception {
    Q3Image gray = ImageLoader.decodeTga(tga(1, 1, 3, 8, 32, new byte[] {70}));
    assertEquals(0x464646ff, gray.rgbaAt(0, 0));
    Q3Image alpha = ImageLoader.decodeTga(tga(2, 1, 11, 16, 40, new byte[] {(byte) 0x81, 70, 100}));
    assertEquals(0x46464664, alpha.rgbaAt(0, 0));
    assertEquals(alpha.rgbaAt(0, 0), alpha.rgbaAt(1, 0));
  }

  @Test
  void rejectsTruncatedHeadersPixelsAndImageIds() {
    assertThrows(ImageFormatException.class, () -> ImageLoader.decodeTga(new byte[17]));
    assertThrows(
        ImageFormatException.class, () -> ImageLoader.decodeTga(tga(1, 1, 2, 24, 0, new byte[2])));
    byte[] truncatedId = tga(1, 1, 2, 24, 0, new byte[3]);
    truncatedId[0] = 100;
    assertThrows(ImageFormatException.class, () -> ImageLoader.decodeTga(truncatedId));
  }

  @Test
  void rejectsUnsupportedAndUnboundedTgaBeforePixelAllocation() {
    for (byte[] invalid :
        new byte[][] {
          tga(0, 1, 2, 24, 0, new byte[0]),
          tga(65535, 65535, 2, 24, 0, new byte[0]),
          tga(1, 1, 1, 24, 0, new byte[3]),
          tga(1, 1, 2, 16, 0, new byte[3]),
          tga(1, 1, 2, 24, 64, new byte[3])
        }) {
      assertThrows(ImageFormatException.class, () -> ImageLoader.decodeTga(invalid));
    }
    byte[] palette = tga(1, 1, 2, 24, 0, new byte[3]);
    palette[1] = 1;
    assertThrows(ImageFormatException.class, () -> ImageLoader.decodeTga(palette));
  }

  @Test
  void rejectsRleOverrunAndTruncatedPackets() {
    assertThrows(
        ImageFormatException.class,
        () -> ImageLoader.decodeTga(tga(1, 1, 10, 24, 0, new byte[] {(byte) 0x81, 0, 0, 0})));
    assertThrows(
        ImageFormatException.class,
        () -> ImageLoader.decodeTga(tga(2, 1, 10, 24, 0, new byte[] {1, 0, 0, 0})));
    assertThrows(
        ImageFormatException.class,
        () -> ImageLoader.decodeTga(tga(128, 128, 10, 32, 0, new byte[3])));
  }

  @Test
  void decodesJpegToOpaqueTopDownRgba() throws Exception {
    BufferedImage image = new BufferedImage(8, 16, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        image.setRGB(x, y, y < 8 ? 0xd0d0d0 : 0x202020);
      }
    }
    var encoded = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "JPEG", encoded));
    Q3Image decoded = ImageLoader.decodeJpeg(encoded.toByteArray());
    assertEquals(8, decoded.width());
    assertEquals(16, decoded.height());
    assertTrue((decoded.rgbaAt(2, 2) >>> 24) > 195);
    assertTrue((decoded.rgbaAt(2, 13) >>> 24) < 45);
    assertEquals(255, decoded.rgbaAt(2, 2) & 255);
  }

  @Test
  void rejectsTruncatedJpegAndExcessiveDeclaredDimensions() throws Exception {
    byte[] image = jpeg();
    assertThrows(
        ImageFormatException.class,
        () -> ImageLoader.decodeJpeg(Arrays.copyOf(image, image.length / 2)));
    assertThrows(
        ImageFormatException.class,
        () -> ImageLoader.decodeJpeg(Arrays.copyOf(image, image.length - 2)));
    boolean changed = false;
    for (int i = 0; i < image.length - 9; i++) {
      if ((image[i] & 255) == 255 && (image[i + 1] & 255) == 192) {
        Arrays.fill(image, i + 5, i + 9, (byte) 255);
        changed = true;
        break;
      }
    }
    assertTrue(changed);
    assertThrows(ImageFormatException.class, () -> ImageLoader.decodeJpeg(image));
  }

  @Test
  void resolvesExtensionsAndReportsMissingImages() throws Exception {
    Path textures = Files.createDirectories(root.resolve("baseq3/textures"));
    Files.write(textures.resolve("both.tga"), tga(1, 1, 2, 24, 0, new byte[] {3, 2, 1}));
    Files.write(textures.resolve("both.jpg"), jpeg());
    Files.write(textures.resolve("only.jpg"), jpeg());
    try (var fs = Pk3FileSystem.mount(root, "baseq3")) {
      LoadedImage defaultOrder = ImageLoader.load(fs, "Textures/Both");
      assertEquals("textures/both.tga", defaultOrder.source().orElseThrow().value());
      assertEquals(0x010203ff, defaultOrder.image().rgbaAt(0, 0));
      assertEquals(
          "textures/both.jpg",
          ImageLoader.load(fs, "textures/both.jpg").source().orElseThrow().value());
      assertEquals(
          "textures/only.jpg",
          ImageLoader.load(fs, "textures/only.tga").source().orElseThrow().value());
      assertEquals(
          "textures/only.jpg",
          ImageLoader.load(fs, "textures/only.jpeg").source().orElseThrow().value());
      LoadedImage missing = ImageLoader.load(fs, "textures/absent");
      assertTrue(missing.missing());
      assertTrue(missing.diagnostic().orElseThrow().contains("textures/absent"));
      assertEquals(0xff00ffff, missing.image().rgbaAt(0, 0));
      assertEquals(0x000000ff, missing.image().rgbaAt(4, 0));
      assertEquals(0xffffffff, ImageLoader.whiteTexture().rgbaAt(0, 0));
    }
  }

  @Test
  void malformedPresentTextureDoesNotSilentlyFallbackToAnotherFile() throws Exception {
    Path textures = Files.createDirectories(root.resolve("baseq3/textures"));
    Files.write(textures.resolve("broken.tga"), new byte[5]);
    Files.write(textures.resolve("broken.jpg"), jpeg());
    try (var fs = Pk3FileSystem.mount(root, "baseq3")) {
      ImageFormatException failure =
          assertThrows(ImageFormatException.class, () -> ImageLoader.load(fs, "textures/broken"));
      assertTrue(failure.getMessage().contains("textures/broken.tga"));
      assertFalse(ImageLoader.load(fs, "textures/broken.jpg").missing());
    }
  }

  private static byte[] jpeg() throws Exception {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(image, "JPEG", output));
    return output.toByteArray();
  }

  private static byte[] tga(
      int width, int height, int type, int bits, int descriptor, byte[] pixels) {
    ByteBuffer result = ByteBuffer.allocate(18 + pixels.length).order(ByteOrder.LITTLE_ENDIAN);
    result.put(2, (byte) type);
    result.putShort(12, (short) width);
    result.putShort(14, (short) height);
    result.put(16, (byte) bits);
    result.put(17, (byte) descriptor);
    result.position(18).put(pixels);
    return result.array();
  }
}

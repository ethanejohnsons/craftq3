package dev.bluevista.craftq3.engine.formats.image;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * JPEG decoding through the JDK's (headless) baseline/progressive reader, after checking the
 * declared dimensions. Grayscale images are expanded to RGB; images are always opaque. Like libjpeg
 * in the original engine, recoverable damage (a missing end marker) yields an image with a warning;
 * CMYK/YCCK files fail with a clear message because the JDK reader cannot convert them.
 */
final class JpegDecoder {
  private JpegDecoder() {}

  record Decoded(Q3Image image, List<String> warnings) {}

  static Decoded decode(byte[] data) {
    if (data.length < 4 || (data[0] & 255) != 0xff || (data[1] & 255) != 0xd8)
      throw new ImageFormatException("Missing JPEG start-of-image marker");
    Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
    if (!readers.hasNext()) throw new ImageFormatException("JDK JPEG reader unavailable");
    ImageReader reader = readers.next();
    List<String> warnings = new ArrayList<>();
    reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
      reader.setInput(input, true, true);
      int width = reader.getWidth(0);
      int height = reader.getHeight(0);
      int pixels = ImageLoader.checkedPixels(width, height);
      BufferedImage image = reader.read(0);
      if (image == null || image.getWidth() != width || image.getHeight() != height)
        throw new ImageFormatException("JPEG reader returned inconsistent dimensions");
      return new Decoded(new Q3Image(width, height, rgba(image, pixels)), warnings);
    } catch (ImageFormatException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      String message = String.valueOf(failure.getMessage());
      if (message.contains("Unsupported Image Type") || message.contains("CMYK"))
        throw new ImageFormatException("CMYK/YCCK JPEG is not supported", failure);
      throw new ImageFormatException("Could not decode JPEG: " + message, failure);
    } finally {
      reader.dispose();
    }
  }

  private static byte[] rgba(BufferedImage image, int pixels) {
    byte[] rgba = new byte[pixels * 4];
    int width = image.getWidth();
    int type = image.getType();
    if ((type == BufferedImage.TYPE_3BYTE_BGR || type == BufferedImage.TYPE_BYTE_GRAY)
        && image.getRaster().getDataBuffer() instanceof DataBufferByte buffer
        && buffer.getNumBanks() == 1
        && buffer.getData().length == pixels * (type == BufferedImage.TYPE_BYTE_GRAY ? 1 : 3)) {
      byte[] source = buffer.getData();
      for (int i = 0; i < pixels; i++) {
        int o = i * 4;
        if (type == BufferedImage.TYPE_BYTE_GRAY) {
          rgba[o] = rgba[o + 1] = rgba[o + 2] = source[i];
        } else {
          rgba[o] = source[i * 3 + 2];
          rgba[o + 1] = source[i * 3 + 1];
          rgba[o + 2] = source[i * 3];
        }
        rgba[o + 3] = (byte) 255;
      }
      return rgba;
    }
    int[] row = new int[width];
    for (int y = 0; y < image.getHeight(); y++) {
      image.getRGB(0, y, width, 1, row, 0, width);
      for (int x = 0; x < width; x++) {
        int o = (y * width + x) * 4;
        rgba[o] = (byte) (row[x] >>> 16);
        rgba[o + 1] = (byte) (row[x] >>> 8);
        rgba[o + 2] = (byte) row[x];
        rgba[o + 3] = (byte) 255;
      }
    }
    return rgba;
  }
}

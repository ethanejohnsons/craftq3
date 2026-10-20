package dev.bluevista.craftq3.assets.image;

import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded Q3 texture decoding. This class never owns a GPU resource or retains the filesystem. */
public final class ImageLoader {
  private static final int MAX_ENCODED_BYTES = 64 * 1024 * 1024;
  private static final Q3Image WHITE = new Q3Image(1, 1, new byte[] {-1, -1, -1, -1});
  private static final Q3Image MISSING = checker();

  private ImageLoader() {}

  /**
   * Tries the requested TGA/JPEG extension first, then its alternative; extensionless names try TGA
   * before JPG. A malformed existing file is an error, not a reason to use a lower-priority image.
   */
  public static LoadedImage load(VirtualFileSystem filesystem, String name) throws IOException {
    Objects.requireNonNull(filesystem, "filesystem");
    for (VirtualPath candidate : candidates(name)) {
      if (filesystem.which(candidate).isEmpty()) continue;
      byte[] encoded = filesystem.read(candidate);
      try {
        Q3Image image =
            candidate.value().endsWith(".tga") ? decodeTga(encoded) : decodeJpeg(encoded);
        return new LoadedImage(image, Optional.of(candidate), Optional.empty());
      } catch (ImageFormatException failure) {
        throw new ImageFormatException(candidate.value() + ": " + failure.getMessage(), failure);
      }
    }
    return new LoadedImage(
        MISSING,
        Optional.empty(),
        Optional.of("Missing texture: " + new VirtualPath(name).value()));
  }

  public static Q3Image missingTexture() {
    return MISSING;
  }

  public static Q3Image whiteTexture() {
    return WHITE;
  }

  private static List<VirtualPath> candidates(String name) {
    String path = new VirtualPath(name).value();
    if (path.endsWith(".tga")) {
      return List.of(
          new VirtualPath(path), new VirtualPath(path.substring(0, path.length() - 4) + ".jpg"));
    }
    if (path.endsWith(".jpeg")) {
      String stem = path.substring(0, path.lastIndexOf('.'));
      return List.of(
          new VirtualPath(path), new VirtualPath(stem + ".tga"), new VirtualPath(stem + ".jpg"));
    }
    if (path.endsWith(".jpg")) {
      return List.of(
          new VirtualPath(path),
          new VirtualPath(path.substring(0, path.lastIndexOf('.')) + ".tga"));
    }
    return List.of(new VirtualPath(path + ".tga"), new VirtualPath(path + ".jpg"));
  }

  /**
   * Decodes true-color 24/32-bit and grayscale 8/16-bit original or RLE TGA data. Descriptor origin
   * bits are normalized to a top-left origin. The fourth byte of 32-bit Q3 images always supplies
   * alpha, including older exporters that leave the descriptor's attribute count at zero.
   *
   * @see <a href="https://www.ludorg.net/amnesia/TGA_File_Format_Spec.html">Truevision TGA 2.0
   *     specification</a>
   */
  public static Q3Image decodeTga(byte[] data) throws ImageFormatException {
    checkEncoded(data);
    if (data.length < 18) throw new ImageFormatException("Truncated TGA header");
    int idLength = data[0] & 255;
    int type = data[2] & 255;
    boolean grayscale = type == 3 || type == 11;
    boolean rle = type == 10 || type == 11;
    if (type != 2 && type != 3 && type != 10 && type != 11) {
      throw new ImageFormatException("Unsupported TGA image type: " + type);
    }
    if (data[1] != 0) throw new ImageFormatException("Color-mapped TGA images are unsupported");
    int width = unsignedShort(data, 12);
    int height = unsignedShort(data, 14);
    int pixels = checkedPixels(width, height);
    int bits = data[16] & 255;
    if (grayscale ? bits != 8 && bits != 16 : bits != 24 && bits != 32) {
      throw new ImageFormatException("Unsupported TGA pixel depth: " + bits);
    }
    int descriptor = data[17] & 255;
    if ((descriptor & 192) != 0) {
      throw new ImageFormatException("Interleaved TGA rows are unsupported");
    }
    int bytesPerPixel = bits / 8;
    int input = 18 + idLength;
    if (input > data.length) throw new ImageFormatException("Truncated TGA image ID");
    if (!rle && (long) input + (long) pixels * bytesPerPixel > data.length) {
      throw new ImageFormatException("Truncated TGA pixels");
    }
    // Reject a short compressed payload before allocating a potentially large output array.
    if (rle && (long) ((pixels + 127) / 128) * (1 + bytesPerPixel) > data.length - input) {
      throw new ImageFormatException("Truncated TGA RLE payload");
    }
    byte[] rgba = new byte[pixels * 4];
    int output = 0;
    while (output < pixels) {
      int count = 1;
      boolean repeated = false;
      if (rle) {
        if (input == data.length) throw new ImageFormatException("Truncated TGA RLE packet");
        int packet = data[input++] & 255;
        count = (packet & 127) + 1;
        repeated = (packet & 128) != 0;
      }
      if (count > pixels - output) throw new ImageFormatException("TGA RLE packet exceeds image");
      int inputBytes = (repeated ? 1 : count) * bytesPerPixel;
      if (inputBytes > data.length - input) throw new ImageFormatException("Truncated TGA pixels");
      for (int i = 0; i < count; i++) {
        int pixel = input + (repeated ? 0 : i * bytesPerPixel);
        int x = (output + i) % width;
        int y = (output + i) / width;
        if ((descriptor & 16) != 0) x = width - 1 - x;
        if ((descriptor & 32) == 0) y = height - 1 - y;
        int destination = (y * width + x) * 4;
        rgba[destination] = data[pixel + (grayscale ? 0 : 2)];
        rgba[destination + 1] = data[pixel + (grayscale ? 0 : 1)];
        rgba[destination + 2] = data[pixel];
        rgba[destination + 3] =
            grayscale
                ? (bits == 16 ? data[pixel + 1] : (byte) 255)
                : (bits == 32 ? data[pixel + 3] : (byte) 255);
      }
      input += inputBytes;
      output += count;
    }
    return new Q3Image(width, height, rgba);
  }

  /**
   * Uses the JDK JPEG reader after checking declared dimensions, avoiding unbounded ImageIO.read.
   */
  public static Q3Image decodeJpeg(byte[] data) throws ImageFormatException {
    checkEncoded(data);
    if (data.length < 4 || (data[0] & 255) != 255 || (data[1] & 255) != 216) {
      throw new ImageFormatException("Missing JPEG start-of-image marker");
    }
    var readers = ImageIO.getImageReadersByFormatName("JPEG");
    if (!readers.hasNext()) throw new ImageFormatException("JDK JPEG reader unavailable");
    ImageReader reader = readers.next();
    List<String> warnings = new ArrayList<>();
    reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
      reader.setInput(input, true, true);
      int width = reader.getWidth(0);
      int height = reader.getHeight(0);
      int pixels = checkedPixels(width, height);
      BufferedImage image = reader.read(0);
      if (!warnings.isEmpty())
        throw new ImageFormatException("Damaged JPEG: " + warnings.getFirst());
      if (image == null || image.getWidth() != width || image.getHeight() != height) {
        throw new ImageFormatException("JPEG reader returned inconsistent dimensions");
      }
      byte[] rgba = new byte[pixels * 4];
      int[] row = new int[width];
      for (int y = 0; y < height; y++) {
        image.getRGB(0, y, width, 1, row, 0, width);
        for (int x = 0; x < width; x++) {
          int target = (y * width + x) * 4;
          rgba[target] = (byte) (row[x] >>> 16);
          rgba[target + 1] = (byte) (row[x] >>> 8);
          rgba[target + 2] = (byte) row[x];
          rgba[target + 3] = (byte) 255;
        }
      }
      return new Q3Image(width, height, rgba);
    } catch (ImageFormatException failure) {
      throw failure;
    } catch (IOException | RuntimeException failure) {
      throw new ImageFormatException("Could not decode JPEG: " + failure.getMessage(), failure);
    } finally {
      reader.dispose();
    }
  }

  private static void checkEncoded(byte[] data) throws ImageFormatException {
    if (data == null || data.length > MAX_ENCODED_BYTES) {
      throw new ImageFormatException("Encoded image must fit the 64MiB limit");
    }
  }

  private static int checkedPixels(int width, int height) throws ImageFormatException {
    if (width < 1 || height < 1 || (long) width * height > Q3Image.MAX_PIXELS) {
      throw new ImageFormatException(
          "Image dimensions exceed the 16M-pixel limit: " + width + "x" + height);
    }
    return width * height;
  }

  private static int unsignedShort(byte[] data, int offset) {
    return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
  }

  private static Q3Image checker() {
    byte[] pixels = new byte[16 * 16 * 4];
    for (int y = 0; y < 16; y++) {
      for (int x = 0; x < 16; x++) {
        int offset = (y * 16 + x) * 4;
        boolean magenta = ((x / 4) + (y / 4)) % 2 == 0;
        pixels[offset] = pixels[offset + 2] = (byte) (magenta ? 255 : 0);
        pixels[offset + 3] = (byte) 255;
      }
    }
    return new Q3Image(16, 16, pixels);
  }
}

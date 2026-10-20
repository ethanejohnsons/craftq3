package dev.bluevista.craftq3.engine.formats.image;

import dev.bluevista.craftq3.engine.formats.AssetLookup;
import dev.bluevista.craftq3.engine.formats.QPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded texture decoding and Quake III image-name resolution. This class never owns a GPU
 * resource or retains the lookup.
 *
 * <p><b>Resolution.</b> The exact name is tried first; a missing {@code .tga} falls back to the
 * {@code .jpg} of the same base name and vice versa, and a name without an extension tries {@code
 * .tga} then {@code .jpg} (the implicit-shader default). TGA and JPEG are the only decoded formats:
 * a reference to another format ({@code .dds}, {@code .png}, ...) is resolved to the TGA or JPEG
 * file with the same base name, which is how packs such as a {@code *_tga.pk3} companion supply
 * equivalents. A candidate that exists but fails to decode is reported and the next candidate is
 * tried; with none left the missing-texture checker is returned.
 */
public final class ImageLoader {
  public static final int MAX_ENCODED_BYTES = 64 * 1024 * 1024;
  private static final Q3Image WHITE = new Q3Image(1, 1, new byte[] {-1, -1, -1, -1});
  private static final Q3Image MISSING = checker();

  private ImageLoader() {}

  /** Resolves and decodes {@code name}; never throws for absent or damaged files. */
  public static LoadedImage load(AssetLookup lookup, String name) {
    Objects.requireNonNull(lookup, "lookup");
    List<String> diagnostics = new ArrayList<>();
    for (String candidate : candidates(name)) {
      Optional<byte[]> encoded = lookup.read(candidate);
      if (encoded.isEmpty()) continue;
      try {
        if (QPath.extension(candidate).equals("tga"))
          return new LoadedImage(decodeTga(encoded.get()), Optional.of(candidate), diagnostics);
        JpegDecoder.Decoded jpeg = decodeJpegWithWarnings(encoded.get());
        jpeg.warnings().forEach(warning -> diagnostics.add(candidate + ": " + warning));
        return new LoadedImage(jpeg.image(), Optional.of(candidate), diagnostics);
      } catch (ImageFormatException failure) {
        diagnostics.add(candidate + ": " + failure.getMessage());
      }
    }
    diagnostics.add("Missing texture: " + QPath.normalize(name));
    return new LoadedImage(MISSING, Optional.empty(), diagnostics);
  }

  /** The first existing candidate for {@code name}, without decoding it. */
  public static Optional<String> find(AssetLookup lookup, String name) {
    for (String candidate : candidates(name))
      if (lookup.exists(candidate)) return Optional.of(candidate);
    return Optional.empty();
  }

  /** The decodable files tried for {@code name}, in order. */
  public static List<String> candidates(String name) {
    String path = QPath.normalize(name);
    String stem = QPath.stripExtension(path);
    return switch (QPath.extension(path)) {
      case "tga" -> List.of(path, stem + ".jpg");
      case "jpg" -> List.of(path, stem + ".tga");
      case "jpeg" -> List.of(path, stem + ".tga", stem + ".jpg");
      case "" -> List.of(path + ".tga", path + ".jpg");
      // Not a native format (or a dot inside the name): look for TGA/JPEG equivalents.
      default -> List.of(stem + ".tga", stem + ".jpg", path + ".tga", path + ".jpg");
    };
  }

  public static Q3Image missingTexture() {
    return MISSING;
  }

  public static Q3Image whiteTexture() {
    return WHITE;
  }

  /** Decodes a TGA file (see {@link TgaDecoder} for the supported types). */
  public static Q3Image decodeTga(byte[] data) {
    checkEncoded(data);
    return TgaDecoder.decode(data);
  }

  /** Decodes a JPEG file, accepting recoverable damage. */
  public static Q3Image decodeJpeg(byte[] data) {
    return decodeJpegWithWarnings(data).image();
  }

  private static JpegDecoder.Decoded decodeJpegWithWarnings(byte[] data) {
    checkEncoded(data);
    return JpegDecoder.decode(data);
  }

  private static void checkEncoded(byte[] data) {
    if (data == null || data.length > MAX_ENCODED_BYTES)
      throw new ImageFormatException("Encoded image must fit the 64 MiB limit");
  }

  static int checkedPixels(int width, int height) {
    if (width < 1 || height < 1 || (long) width * height > Q3Image.MAX_PIXELS)
      throw new ImageFormatException(
          "Image dimensions exceed the 16M-pixel limit: " + width + "x" + height);
    return width * height;
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

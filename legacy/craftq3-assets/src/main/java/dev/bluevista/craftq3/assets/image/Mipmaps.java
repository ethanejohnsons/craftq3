package dev.bluevista.craftq3.assets.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Complete GPU mip chains, filtered in Q3's encoded RGBA space without gamma conversion. */
public final class Mipmaps {
  private Mipmaps() {}

  /**
   * Includes the supplied base image; each subsequent dimension halves, rounded down to at least 1.
   */
  public static List<Q3Image> generate(Q3Image base) {
    Objects.requireNonNull(base, "base");
    List<Q3Image> levels = new ArrayList<>();
    levels.add(base);
    Q3Image current = base;
    while (current.width() > 1 || current.height() > 1) {
      current = downsample(current);
      levels.add(current);
    }
    return List.copyOf(levels);
  }

  private static Q3Image downsample(Q3Image image) {
    int width = Math.max(1, image.width() / 2);
    int height = Math.max(1, image.height() / 2);
    byte[] output = new byte[width * height * 4];
    double scaleX = (double) image.width() / width;
    double scaleY = (double) image.height() / height;
    // Area filtering includes the final source row/column for odd source dimensions, rather than
    // dropping it or assuming power-of-two textures. At most 3x3 source pixels cover a destination.
    for (int y = 0; y < height; y++) {
      double y0 = y * scaleY;
      double y1 = Math.min(image.height(), (y + 1) * scaleY);
      for (int x = 0; x < width; x++) {
        double x0 = x * scaleX;
        double x1 = Math.min(image.width(), (x + 1) * scaleX);
        double red = 0;
        double green = 0;
        double blue = 0;
        double alpha = 0;
        for (int sourceY = (int) y0; sourceY < Math.ceil(y1); sourceY++) {
          double wy = Math.min(y1, sourceY + 1) - Math.max(y0, sourceY);
          for (int sourceX = (int) x0; sourceX < Math.ceil(x1); sourceX++) {
            double weight = wy * (Math.min(x1, sourceX + 1) - Math.max(x0, sourceX));
            int rgba = image.rgbaAt(sourceX, sourceY);
            red += (rgba >>> 24) * weight;
            green += (rgba >>> 16 & 255) * weight;
            blue += (rgba >>> 8 & 255) * weight;
            alpha += (rgba & 255) * weight;
          }
        }
        int offset = (y * width + x) * 4;
        double area = scaleX * scaleY;
        output[offset] = (byte) Math.round(red / area);
        output[offset + 1] = (byte) Math.round(green / area);
        output[offset + 2] = (byte) Math.round(blue / area);
        output[offset + 3] = (byte) Math.round(alpha / area);
      }
    }
    return new Q3Image(width, height, output);
  }
}

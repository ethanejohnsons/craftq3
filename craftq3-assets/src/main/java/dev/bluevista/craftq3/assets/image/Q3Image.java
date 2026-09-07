package dev.bluevista.craftq3.assets.image;

import java.util.Arrays;
import java.util.Objects;

/** Owned, immutable RGBA8 pixels, with the top row first and no color-space conversion. */
public record Q3Image(int width, int height, byte[] rgba) {
  public static final int MAX_PIXELS = 16 * 1024 * 1024;

  public Q3Image {
    Objects.requireNonNull(rgba, "rgba");
    if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
      throw new IllegalArgumentException("Image dimensions must fit the 16M-pixel limit");
    }
    if (rgba.length != (long) width * height * 4) {
      throw new IllegalArgumentException("Expected exactly four bytes per image pixel");
    }
    rgba = rgba.clone();
  }

  @Override
  public byte[] rgba() {
    return rgba.clone();
  }

  /** Returns a pixel as 0xRRGGBBAA. */
  public int rgbaAt(int x, int y) {
    Objects.checkIndex(x, width);
    Objects.checkIndex(y, height);
    int offset = (y * width + x) * 4;
    return (rgba[offset] & 255) << 24
        | (rgba[offset + 1] & 255) << 16
        | (rgba[offset + 2] & 255) << 8
        | (rgba[offset + 3] & 255);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Q3Image image
        && width == image.width
        && height == image.height
        && Arrays.equals(rgba, image.rgba);
  }

  @Override
  public int hashCode() {
    return 31 * (31 * width + height) + Arrays.hashCode(rgba);
  }

  @Override
  public String toString() {
    return "Q3Image[" + width + "x" + height + ", RGBA8]";
  }
}

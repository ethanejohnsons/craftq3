package dev.bluevista.craftq3.engine.formats.image;

/**
 * Truevision TGA decoding for the types the original renderer loads: uncompressed and RLE
 * true-colour (types 2 and 10, 24 or 32 bits) and grayscale (types 3 and 11, 8 bits, or 16 bits
 * with alpha). Colour-mapped images (types 1 and 9) are refused as in the original; a colour map
 * attached to a true-colour image is skipped. Both origin bits are honoured. The fourth byte of a
 * 32-bit pixel is always alpha, whatever the descriptor's attribute count says.
 *
 * @see <a href="https://www.ludorg.net/amnesia/TGA_File_Format_Spec.html">TGA 2.0 specification</a>
 */
final class TgaDecoder {
  static final int HEADER_BYTES = 18;
  private static final int RIGHT_TO_LEFT = 0x10;
  private static final int TOP_DOWN = 0x20;

  private TgaDecoder() {}

  static Q3Image decode(byte[] data) {
    if (data.length < HEADER_BYTES) throw new ImageFormatException("Truncated TGA header");
    int idLength = data[0] & 255;
    int colorMapType = data[1] & 255;
    int type = data[2] & 255;
    if (type == 1 || type == 9)
      throw new ImageFormatException("Colour-mapped TGA images are not supported");
    if (type != 2 && type != 3 && type != 10 && type != 11)
      throw new ImageFormatException("Unsupported TGA image type " + type);
    boolean grayscale = type == 3 || type == 11;
    boolean rle = type == 10 || type == 11;
    int colorMapBytes =
        colorMapType == 0 ? 0 : unsignedShort(data, 5) * (((data[7] & 255) + 7) / 8);
    int width = unsignedShort(data, 12);
    int height = unsignedShort(data, 14);
    int pixels = ImageLoader.checkedPixels(width, height);
    int bits = data[16] & 255;
    if (grayscale ? bits != 8 && bits != 16 : bits != 24 && bits != 32)
      throw new ImageFormatException("Unsupported TGA pixel depth " + bits + " for type " + type);
    int descriptor = data[17] & 255;
    int bytesPerPixel = bits / 8;
    int input = HEADER_BYTES + idLength + colorMapBytes;
    if (input > data.length) throw new ImageFormatException("Truncated TGA image ID or colour map");
    if (!rle && (long) input + (long) pixels * bytesPerPixel > data.length)
      throw new ImageFormatException("Truncated TGA pixels");
    // Reject a short compressed payload before allocating a potentially large output array.
    if (rle && (long) ((pixels + 127) / 128) * (1 + bytesPerPixel) > data.length - input)
      throw new ImageFormatException("Truncated TGA RLE payload");
    byte[] rgba = new byte[pixels * 4];
    Layout layout = new Layout(width, height, descriptor, grayscale, bits);
    if (rle) decodeRle(data, input, pixels, bytesPerPixel, layout, rgba);
    else for (int i = 0; i < pixels; i++) layout.store(data, input + i * bytesPerPixel, i, rgba);
    return new Q3Image(width, height, rgba);
  }

  private static void decodeRle(
      byte[] data, int input, int pixels, int bytesPerPixel, Layout layout, byte[] rgba) {
    int output = 0;
    while (output < pixels) {
      if (input == data.length) throw new ImageFormatException("Truncated TGA RLE packet");
      int packet = data[input++] & 255;
      int count = (packet & 127) + 1;
      boolean repeated = (packet & 128) != 0;
      if (count > pixels - output) throw new ImageFormatException("TGA RLE packet exceeds image");
      int inputBytes = (repeated ? 1 : count) * bytesPerPixel;
      if (inputBytes > data.length - input) throw new ImageFormatException("Truncated TGA pixels");
      for (int i = 0; i < count; i++)
        layout.store(data, input + (repeated ? 0 : i * bytesPerPixel), output + i, rgba);
      input += inputBytes;
      output += count;
    }
  }

  /** Maps file pixel order to a top-left-origin RGBA destination. */
  private record Layout(int width, int height, int descriptor, boolean grayscale, int bits) {
    void store(byte[] data, int pixel, int index, byte[] rgba) {
      int x = index % width;
      int y = index / width;
      if ((descriptor & RIGHT_TO_LEFT) != 0) x = width - 1 - x;
      if ((descriptor & TOP_DOWN) == 0) y = height - 1 - y;
      int destination = (y * width + x) * 4;
      if (grayscale) {
        rgba[destination] = rgba[destination + 1] = rgba[destination + 2] = data[pixel];
        rgba[destination + 3] = bits == 16 ? data[pixel + 1] : (byte) 255;
      } else {
        rgba[destination] = data[pixel + 2];
        rgba[destination + 1] = data[pixel + 1];
        rgba[destination + 2] = data[pixel];
        rgba[destination + 3] = bits == 32 ? data[pixel + 3] : (byte) 255;
      }
    }
  }

  private static int unsignedShort(byte[] data, int offset) {
    return (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
  }
}

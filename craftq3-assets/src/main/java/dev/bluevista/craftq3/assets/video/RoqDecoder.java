package dev.bluevista.craftq3.assets.video;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.image.Q3Image;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Streaming Q3 RoQ decoder, independently implemented from Tim Ferguson's format description. */
public final class RoqDecoder implements AutoCloseable {
  public sealed interface Event permits Video, Audio {}

  /** Full-range planar Y, Cb, Cr, each at full pixel resolution; snapshots own their bytes. */
  public record Video(int number, int rate, int width, int height, byte[] yuv) implements Event {
    public Video {
      if (number < 0
          || rate < 1
          || rate > 120
          || width < 1
          || height < 1
          || (long) width * height > 4_194_304
          || yuv.length != (long) width * height * 3)
        throw new IllegalArgumentException("Invalid RoQ frame");
      yuv = yuv.clone();
    }

    @Override
    public byte[] yuv() {
      return yuv.clone();
    }

    public long milliseconds() {
      return number * 1000L / rate;
    }

    public Q3Image image() {
      int pixels = width * height;
      byte[] rgba = new byte[pixels * 4];
      for (int i = 0; i < pixels; i++) {
        int y = yuv[i] & 255,
            cb = (yuv[pixels + i] & 255) - 128,
            cr = (yuv[pixels * 2 + i] & 255) - 128;
        rgba[i * 4] = (byte) clamp((int) Math.round(y + 1.402 * cr));
        rgba[i * 4 + 1] = (byte) clamp((int) Math.round(y - .34414 * cb - .71414 * cr));
        rgba[i * 4 + 2] = (byte) clamp((int) Math.round(y + 1.772 * cb));
        rgba[i * 4 + 3] = (byte) 255;
      }
      return new Q3Image(width, height, rgba);
    }

    private static int clamp(int value) {
      return Math.max(0, Math.min(255, value));
    }
  }

  /** Audio position is measured in 22050 Hz sample frames, independent of video chunks. */
  public record Audio(long firstSample, PcmSound sound) implements Event {}

  private static final long MAX_BYTES = 512L * 1024 * 1024;
  private static final int MAX_CHUNK = 8 * 1024 * 1024;
  private final InputStream input;
  private final int rate;
  private final byte[][] small = new byte[256][12], large = new byte[256][48];
  private final boolean[] smallDefined = new boolean[256], largeDefined = new boolean[256];
  private byte[] current, previous;
  private int width, height, frame, channels;
  private long bytes, samples;
  private boolean closed, ended;

  public RoqDecoder(InputStream input) throws IOException {
    this.input = Objects.requireNonNull(input);
    try {
      var header = buffer(readExact(8));
      if (u16(header) != 0x1084 || header.getInt() != -1)
        throw new IOException("Invalid RoQ signature");
      rate = u16(header);
      if (rate < 1 || rate > 120) throw new IOException("Invalid RoQ frame rate");
    } catch (IOException | RuntimeException failure) {
      try {
        input.close();
      } catch (IOException close) {
        failure.addSuppressed(close);
      }
      throw failure;
    }
  }

  public int frameRate() {
    return rate;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public Optional<Event> next() throws IOException {
    if (closed) throw new IOException("RoQ decoder is closed");
    if (ended) return Optional.empty();
    try {
      for (; ; ) {
        int first = input.read();
        if (first < 0) {
          ended = true;
          return Optional.empty();
        }
        bytes++;
        byte[] rest = readExact(7);
        var header = buffer(rest);
        int id = first | ((header.get() & 255) << 8);
        long length = Integer.toUnsignedLong(header.getInt());
        int argument = u16(header);
        if (length > MAX_CHUNK) throw new IOException("RoQ chunk exceeds budget");
        var data = buffer(readExact((int) length));
        switch (id) {
          case 0x1001 -> info(data);
          case 0x1002 -> codebook(data, argument);
          case 0x1011 -> {
            return Optional.of(video(data, argument));
          }
          case 0x1020, 0x1021 -> {
            if (data.hasRemaining())
              return Optional.of(audio(data, argument, id == 0x1020 ? 1 : 2));
          }
          default -> throw new IOException("Unsupported RoQ chunk 0x" + Integer.toHexString(id));
        }
      }
    } catch (IOException | RuntimeException failure) {
      try {
        close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      if (failure instanceof IOException io) throw io;
      throw new IOException("Malformed RoQ data", failure);
    }
  }

  private void info(ByteBuffer data) throws IOException {
    if (data.remaining() != 8) throw new IOException("Invalid RoQ info length");
    int w = u16(data), h = u16(data), block = u16(data), sub = u16(data);
    if (w < 16
        || h < 16
        || w % 16 != 0
        || h % 16 != 0
        || (long) w * h > 4_194_304
        || block != 8
        || sub != 4) throw new IOException("Unsupported RoQ dimensions/block geometry");
    if (width != 0) {
      if (w != width || h != height) throw new IOException("RoQ dimensions changed");
      return;
    }
    width = w;
    height = h;
    current = new byte[w * h * 3];
    previous = new byte[current.length];
    Arrays.fill(current, w * h, current.length, (byte) 128);
    Arrays.fill(previous, w * h, previous.length, (byte) 128);
  }

  private void codebook(ByteBuffer data, int argument) throws IOException {
    int cells = argument >>> 8;
    if (cells == 0) cells = 256;
    int quads = argument & 255;
    if (quads == 0 && data.remaining() > cells * 6) quads = 256;
    if (data.remaining() != cells * 6 + quads * 4)
      throw new IOException("Invalid RoQ codebook length");
    for (int index = 0; index < cells; index++) {
      byte[] cell = small[index];
      data.get(cell, 0, 4);
      Arrays.fill(cell, 4, 8, data.get());
      Arrays.fill(cell, 8, 12, data.get());
      smallDefined[index] = true;
    }
    for (int index = 0; index < quads; index++) {
      for (int part = 0; part < 4; part++) {
        int cell = data.get() & 255;
        if (!smallDefined[cell]) throw new IOException("Undefined RoQ cell");
        for (int plane = 0; plane < 3; plane++)
          for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++)
              large[index][plane * 16 + (part / 2 * 2 + y) * 4 + part % 2 * 2 + x] =
                  small[cell][plane * 4 + y * 2 + x];
      }
      largeDefined[index] = true;
    }
  }

  private Video video(ByteBuffer data, int argument) throws IOException {
    if (width == 0 || frame >= 216_000)
      throw new IOException("RoQ frame missing geometry or exceeds duration budget");
    var codes = new Codes(data);
    int mx = (byte) (argument >>> 8), my = (byte) argument;
    for (int y = 0; y < height; y += 16)
      for (int x = 0; x < width; x += 16)
        for (int part = 0; part < 4; part++)
          block(codes, x + part % 2 * 8, y + part / 2 * 8, 8, mx, my);
    // Original encoders reserve an unused next codeword when the last word is exhausted.
    if (codes.left == 0 && data.remaining() == 2) data.position(data.limit());
    if (data.hasRemaining()) throw new IOException("Trailing RoQ frame data");
    var result = new Video(frame++, rate, width, height, current);
    if (frame == 1) System.arraycopy(current, 0, previous, 0, current.length);
    byte[] next = previous;
    previous = current;
    current = next;
    return result;
  }

  private void block(Codes codes, int x, int y, int size, int mx, int my) throws IOException {
    switch (codes.next()) {
      case 0 -> {} // Retain this buffer's previous encoding, two frames back after startup.
      case 1 -> {
        int vector = codes.byteValue(),
            sx = x + 8 - (vector >>> 4) - mx,
            sy = y + 8 - (vector & 15) - my;
        if (sx < 0 || sy < 0 || sx + size > width || sy + size > height)
          throw new IOException("RoQ motion vector outside frame");
        for (int plane = 0; plane < 3; plane++)
          for (int row = 0; row < size; row++)
            System.arraycopy(
                previous,
                plane * width * height + (sy + row) * width + sx,
                current,
                plane * width * height + (y + row) * width + x,
                size);
      }
      case 2 -> {
        int index = codes.byteValue();
        if (!largeDefined[index]) throw new IOException("Undefined RoQ quad");
        paint(large[index], 4, x, y, size);
      }
      case 3 -> {
        for (int part = 0; part < 4; part++) {
          int px = x + part % 2 * (size / 2), py = y + part / 2 * (size / 2);
          if (size == 8) block(codes, px, py, 4, mx, my);
          else {
            int index = codes.byteValue();
            if (!smallDefined[index]) throw new IOException("Undefined RoQ cell");
            paint(small[index], 2, px, py, 2);
          }
        }
      }
      default -> throw new AssertionError();
    }
  }

  private void paint(byte[] cell, int edge, int x, int y, int size) {
    for (int plane = 0; plane < 3; plane++)
      for (int row = 0; row < size; row++)
        for (int col = 0; col < size; col++)
          current[plane * width * height + (y + row) * width + x + col] =
              cell[plane * edge * edge + (row * edge / size) * edge + col * edge / size];
  }

  private Audio audio(ByteBuffer data, int argument, int count) throws IOException {
    if (data.remaining() % count != 0 || channels != 0 && channels != count)
      throw new IOException("Invalid RoQ audio channel layout");
    channels = count;
    int[] prediction =
        count == 1
            ? new int[] {(short) argument}
            : new int[] {(short) (argument & 0xff00), (short) (argument << 8)};
    byte[] pcm = new byte[data.remaining() * 2];
    for (int i = 0; i < pcm.length / 2; i++) {
      int value = data.get() & 255, magnitude = value & 127;
      int delta = magnitude * magnitude * (value < 128 ? 1 : -1);
      int sample = prediction[i % count] = (short) (prediction[i % count] + delta);
      pcm[i * 2] = (byte) sample;
      pcm[i * 2 + 1] = (byte) (sample >>> 8);
    }
    var sound = new PcmSound(22050, count, 16, pcm);
    var result = new Audio(samples, sound);
    samples += sound.frames();
    return result;
  }

  private static final class Codes {
    final ByteBuffer data;
    int bits, left;

    Codes(ByteBuffer data) {
      this.data = data;
    }

    int next() {
      if (left == 0) {
        bits = u16(data);
        left = 8;
      }
      int code = (bits >>> 14) & 3;
      bits <<= 2;
      left--;
      return code;
    }

    int byteValue() {
      return data.get() & 255;
    }
  }

  private byte[] readExact(int count) throws IOException {
    if (count > MAX_BYTES - bytes) throw new IOException("RoQ stream exceeds byte budget");
    byte[] result = input.readNBytes(count);
    bytes += result.length;
    if (result.length != count) throw new IOException("Truncated RoQ stream");
    return result;
  }

  private static ByteBuffer buffer(byte[] bytes) {
    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  }

  private static int u16(ByteBuffer data) {
    return data.getShort() & 65535;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    input.close();
  }
}

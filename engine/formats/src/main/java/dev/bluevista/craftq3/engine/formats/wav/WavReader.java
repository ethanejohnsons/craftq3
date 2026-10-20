package dev.bluevista.craftq3.engine.formats.wav;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.Binary;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * RIFF/WAVE PCM reader following the original sound loader: chunks are scanned word-aligned from
 * the end of the RIFF header to the end of the file (the RIFF length field is not trusted), the
 * first {@code fmt } and first {@code data} chunk are used wherever they appear, and only format
 * tag 1 (uncompressed PCM, or the extensible header with a PCM sub-format) is accepted. Byte-rate
 * and block-align fields are ignored. A data chunk running past the end of the file, or ending in a
 * partial sample frame, is truncated to the complete frames present.
 */
public final class WavReader {
  public static final int MAX_BYTES = 64 * 1024 * 1024;
  private static final int RIFF = 0x46464952;
  private static final int WAVE = 0x45564157;
  private static final int FORMAT = 0x20746d66;
  private static final int DATA = 0x61746164;
  private static final int FORMAT_PCM = 1;
  private static final int FORMAT_EXTENSIBLE = 0xfffe;
  private static final int MAX_CHUNKS = 4096;
  private static final Log LOG = Log.get(WavReader.class);

  private WavReader() {}

  public static PcmSound read(byte[] bytes) {
    if (bytes == null || bytes.length < 12 || bytes.length > MAX_BYTES)
      throw new WavFormatException("Invalid WAV file size");
    ByteBuffer input = Binary.littleEndian(bytes);
    if (input.getInt(0) != RIFF || input.getInt(8) != WAVE)
      throw new WavFormatException("Missing RIFF/WAVE chunks");
    int format = findChunk(input, FORMAT);
    if (format < 0) throw new WavFormatException("Missing fmt chunk");
    if (chunkLength(input, format) < 16 || format + 8 + 16 > bytes.length)
      throw new WavFormatException("Truncated fmt chunk");
    int tag = Short.toUnsignedInt(input.getShort(format + 8));
    if (tag == FORMAT_EXTENSIBLE && isExtensiblePcm(input, format)) tag = FORMAT_PCM;
    if (tag != FORMAT_PCM)
      throw new WavFormatException(
          "Only uncompressed PCM WAV is supported (format tag 0x" + Integer.toHexString(tag) + ")");
    int channels = Short.toUnsignedInt(input.getShort(format + 10));
    int rate = input.getInt(format + 12);
    int bits = Short.toUnsignedInt(input.getShort(format + 22));
    if (channels < 1 || channels > 2)
      throw new WavFormatException("Unsupported WAV channel count " + channels);
    if (bits != 8 && bits != 16)
      throw new WavFormatException("Unsupported WAV sample depth " + bits);
    if (rate < 1 || rate > PcmSound.MAX_RATE)
      throw new WavFormatException("Unsupported WAV sample rate " + rate);
    int data = findChunk(input, DATA);
    if (data < 0) throw new WavFormatException("Missing data chunk");
    return new PcmSound(
        rate, channels, bits, samples(bytes, data, chunkLength(input, data), channels * bits / 8));
  }

  private static byte[] samples(byte[] bytes, int chunk, long declared, int frameBytes) {
    int start = chunk + 8;
    long available = Math.min(declared, bytes.length - (long) start);
    if (available < declared)
      LOG.warnOnce("wav-truncated", "WAV data chunk runs past the end of the file; truncated");
    int length = (int) (available - available % frameBytes);
    return Arrays.copyOfRange(bytes, start, start + length);
  }

  /** The offset of the first chunk with {@code tag}, or -1. */
  private static int findChunk(ByteBuffer input, int tag) {
    long offset = 12;
    for (int chunks = 0; offset + 8 <= input.limit() && chunks < MAX_CHUNKS; chunks++) {
      long length = chunkLength(input, (int) offset);
      if (length < 0) return -1; // the original loader stops at a negative length
      if (input.getInt((int) offset) == tag) return (int) offset;
      offset += 8 + ((length + 1) & ~1L);
    }
    return -1;
  }

  private static long chunkLength(ByteBuffer input, int chunk) {
    return input.getInt(chunk + 4);
  }

  /** WAVE_FORMAT_EXTENSIBLE whose sub-format GUID starts with the PCM tag. */
  private static boolean isExtensiblePcm(ByteBuffer input, int format) {
    return chunkLength(input, format) >= 40
        && format + 8 + 26 <= input.limit()
        && Short.toUnsignedInt(input.getShort(format + 8 + 24)) == FORMAT_PCM;
  }
}

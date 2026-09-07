package dev.bluevista.craftq3.assets.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Bounded RIFF/WAVE PCM reader. Metadata chunks are skipped using checked, word-aligned lengths.
 */
public final class WavReader {
  private static final int RIFF = 0x46464952,
      WAVE = 0x45564157,
      FORMAT = 0x20746d66,
      DATA = 0x61746164;

  private WavReader() {}

  public static PcmSound read(byte[] bytes) throws WavFormatException {
    if (bytes == null || bytes.length < 12 || bytes.length > 64 * 1024 * 1024)
      throw bad("Invalid WAV file size");
    var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (input.getInt(0) != RIFF || input.getInt(8) != WAVE)
      throw bad("Expected RIFF/WAVE PCM sound");
    long end = 8 + Integer.toUnsignedLong(input.getInt(4));
    if (end < 12 || end > bytes.length) throw bad("WAV RIFF length exceeds file");
    int channels = 0, rate = 0, bits = 0, align = 0, dataOffset = -1, dataLength = 0;
    boolean formatSeen = false;
    for (int offset = 12, chunks = 0; offset < end; ) {
      if (++chunks > 4096 || offset > end - 8)
        throw bad("WAV chunk count or header exceeds bounds");
      int tag = input.getInt(offset);
      long length = Integer.toUnsignedLong(input.getInt(offset + 4));
      long payloadEnd = offset + 8L + length;
      long next = payloadEnd + (length & 1);
      // Four original pak0 8-bit sounds omit only the final data chunk's RIFF pad byte.
      if (payloadEnd > end || (next > end && (tag != DATA || payloadEnd != end)))
        throw bad("WAV chunk exceeds RIFF boundary");
      int payload = offset + 8;
      if (tag == FORMAT) {
        if (formatSeen || length < 16) throw bad("Duplicate or truncated WAV format");
        formatSeen = true;
        if (Short.toUnsignedInt(input.getShort(payload)) != 1)
          throw bad("Only uncompressed PCM WAV is supported");
        channels = Short.toUnsignedInt(input.getShort(payload + 2));
        rate = input.getInt(payload + 4);
        long byteRate = Integer.toUnsignedLong(input.getInt(payload + 8));
        align = Short.toUnsignedInt(input.getShort(payload + 12));
        bits = Short.toUnsignedInt(input.getShort(payload + 14));
        if (channels < 1
            || channels > 2
            || rate < 1000
            || rate > 192000
            || (bits != 8 && bits != 16)
            || align != channels * bits / 8
            || byteRate != (long) rate * align)
          throw bad("Invalid PCM channels/rate/depth/alignment");
      } else if (tag == DATA) {
        if (dataOffset >= 0) throw bad("Multiple PCM data chunks are not supported");
        dataOffset = payload;
        dataLength = (int) length;
      }
      offset = (int) Math.min(next, end);
    }
    if (!formatSeen || dataOffset < 0 || dataLength == 0 || dataLength % align != 0)
      throw bad("Missing or incomplete PCM samples");
    return new PcmSound(
        rate, channels, bits, Arrays.copyOfRange(bytes, dataOffset, dataOffset + dataLength));
  }

  private static WavFormatException bad(String message) {
    return new WavFormatException(message);
  }
}

package dev.bluevista.craftq3.core.net;

import java.util.Arrays;

/** A native-compatible raw OOB command line and the untouched bytes after that line. */
public final class ConnectionlessMessage {
  private final String line;
  private final byte[] body;
  private final int readCount;
  private final int bitPosition;

  private ConnectionlessMessage(String line, byte[] body, int readCount, int bitPosition) {
    this.line = line;
    this.body = body;
    this.readCount = readCount;
    this.bitPosition = bitPosition;
  }

  /**
   * Reads one raw MSG line after the four-byte marker. Newline/NUL are consumed; EOF is also a
   * valid end. The 1023-character limit consumes one additional byte, as the native reader does.
   * This is an envelope parser, not a command dispatcher or a compressed-connect decoder.
   */
  public static ConnectionlessMessage parse(byte[] packet) {
    if (Protocol68Datagram.classify(packet) != Protocol68Datagram.Kind.CONNECTIONLESS)
      throw new IllegalArgumentException("Invalid connectionless datagram");
    StringBuilder line =
        new StringBuilder(Math.min(packet.length - 4, MessageStrings.NORMAL_CAPACITY - 1));
    int cursor = 4;
    int readCount;
    while (true) {
      // An unsuccessful raw MSG_ReadByte advances readcount, but does not advance the bit cursor.
      readCount = cursor + 1;
      if (cursor == packet.length) break;
      int symbol = packet[cursor++] & 255;
      if (symbol == 0 || symbol == '\n' || line.length() == MessageStrings.NORMAL_CAPACITY - 1)
        break;
      line.append(MessageStrings.sanitize(symbol));
    }
    return new ConnectionlessMessage(
        line.toString(), Arrays.copyOfRange(packet, cursor, packet.length), readCount, cursor * 8);
  }

  public String line() {
    return line;
  }

  /** Remaining raw bytes, including any later NULs, high bytes, or newlines, in an owned copy. */
  public byte[] body() {
    return body.clone();
  }

  /**
   * Native byte readcount, including the marker; an EOF read leaves this one past packet length.
   */
  public int readCount() {
    return readCount;
  }

  /**
   * Native bit cursor, including the marker; unlike readcount it excludes an unsuccessful EOF read.
   */
  public int bitPosition() {
    return bitPosition;
  }
}

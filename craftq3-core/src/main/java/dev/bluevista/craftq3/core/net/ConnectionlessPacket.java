package dev.bluevista.craftq3.core.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The -1 datagram marker and bounded payload; connect compression belongs above this framing. */
public final class ConnectionlessPacket {
  public static final int MAX_PAYLOAD = Protocol68Channel.MAX_MESSAGE - 4;

  private ConnectionlessPacket() {}

  public static byte[] encode(byte[] payload) {
    if (payload.length > MAX_PAYLOAD)
      throw new IllegalArgumentException("Connectionless packet too long");
    byte[] packet = new byte[payload.length + 4];
    Arrays.fill(packet, 0, 4, (byte) 0xff);
    System.arraycopy(payload, 0, packet, 4, payload.length);
    return packet;
  }

  /** Latin-1 command bytes without an implicit terminator; callers supply protocol newlines. */
  public static byte[] text(String text) {
    if (text.length() > MAX_PAYLOAD || text.chars().anyMatch(c -> c == 0 || c > 255))
      throw new IllegalArgumentException("Connectionless text must be bounded non-NUL Latin-1");
    return encode(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  public static boolean isConnectionless(byte[] packet) {
    return packet.length >= 4
        && packet[0] == -1
        && packet[1] == -1
        && packet[2] == -1
        && packet[3] == -1;
  }

  public static byte[] payload(byte[] packet) {
    if (!isConnectionless(packet) || packet.length > MAX_PAYLOAD + 4)
      throw new IllegalArgumentException("Invalid connectionless datagram");
    return Arrays.copyOfRange(packet, 4, packet.length);
  }
}

package dev.bluevista.craftq3.core.net;

import java.util.Objects;

/** Stateless framing classification before any datagram reaches a mutable channel. */
public final class Protocol68Datagram {
  public enum Kind {
    MALFORMED,
    CONNECTIONLESS,
    SEQUENCED
  }

  private Protocol68Datagram() {}

  /**
   * Checks the marker and applicable wire-size bound only. A sequenced candidate still needs the
   * receiving channel's endpoint, qport, fragment and sequence validation. In particular, a
   * four-byte sequence prefix is a candidate rather than proof of a complete channel packet.
   */
  public static Kind classify(byte[] packet) {
    Objects.requireNonNull(packet, "packet");
    if (packet.length < 4 || packet.length > Protocol68Channel.MAX_MESSAGE) return Kind.MALFORMED;
    if (ConnectionlessPacket.isConnectionless(packet)) return Kind.CONNECTIONLESS;
    return packet.length <= Protocol68Channel.MAX_PACKET ? Kind.SEQUENCED : Kind.MALFORMED;
  }
}

package dev.bluevista.craftq3.core.net;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Owned, inert addresses from a bounded connectionless master-server response. */
public record MasterServerResponse(
    boolean extended, List<MasterServerResponse.Endpoint> endpoints) {
  public static final int MAX_ENDPOINTS = 256;
  private static final byte[] CLASSIC = "getserversResponse".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] EXTENDED =
      "getserversExtResponse".getBytes(StandardCharsets.US_ASCII);

  public MasterServerResponse {
    endpoints = List.copyOf(endpoints);
    if (endpoints.size() > MAX_ENDPOINTS)
      throw new IllegalArgumentException("Too many master response endpoints");
  }

  /** Preserves address family, including IPv4-mapped IPv6, without resolving names. */
  public record Endpoint(byte[] addressBytes, int port, int scopeId) {
    public Endpoint {
      if (addressBytes == null
          || (addressBytes.length != 4 && addressBytes.length != 16)
          || port < 0
          || port > 65535
          || scopeId < 0
          || addressBytes.length == 4 && scopeId != 0)
        throw new IllegalArgumentException("Invalid master endpoint");
      addressBytes = addressBytes.clone();
    }

    @Override
    public byte[] addressBytes() {
      return addressBytes.clone();
    }

    public InetSocketAddress socketAddress() {
      try {
        InetAddress address =
            addressBytes.length == 16
                ? Inet6Address.getByAddress(null, addressBytes, scopeId)
                : InetAddress.getByAddress(addressBytes);
        return new InetSocketAddress(address, port);
      } catch (UnknownHostException impossible) {
        throw new AssertionError(impossible);
      }
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Endpoint endpoint
          && port == endpoint.port
          && scopeId == endpoint.scopeId
          && Arrays.equals(addressBytes, endpoint.addressBytes);
    }

    @Override
    public int hashCode() {
      return 31 * (31 * Arrays.hashCode(addressBytes) + port) + scopeId;
    }
  }

  /**
   * Accepts a recognized response envelope. The native record parser retains at most 256 complete
   * records, including duplicates. A record requires a following '\\' or '/' delimiter; a malformed
   * final record is discarded. List insertion, peer authentication, and querying are caller-owned.
   */
  public static MasterServerResponse parse(byte[] packet, int senderScopeId) {
    if (senderScopeId < 0) throw new IllegalArgumentException("Invalid master sender scope");
    byte[] data = ConnectionlessPacket.payload(packet);
    boolean extended;
    int cursor;
    if (startsWith(data, EXTENDED)) {
      extended = true;
      cursor = EXTENDED.length;
    } else if (startsWith(data, CLASSIC)) {
      extended = false;
      cursor = CLASSIC.length;
    } else {
      throw new IllegalArgumentException("Unrecognized master response");
    }
    // Native parsing seeks the first eligible delimiter after the textual prefix.
    while (cursor < data.length && data[cursor] != '\\' && (!extended || data[cursor] != '/'))
      cursor++;
    var endpoints = new ArrayList<Endpoint>();
    while (cursor < data.length && endpoints.size() < MAX_ENDPOINTS) {
      int addressLength;
      if (data[cursor] == '\\') addressLength = 4;
      else if (extended && data[cursor] == '/') addressLength = 16;
      else break;
      int end = cursor + 1 + addressLength + 2;
      if (end >= data.length || data[end] != '\\' && data[end] != '/') break;
      int port = (data[end - 2] & 255) << 8 | data[end - 1] & 255;
      endpoints.add(
          new Endpoint(
              Arrays.copyOfRange(data, cursor + 1, cursor + 1 + addressLength),
              port,
              addressLength == 16 ? senderScopeId : 0));
      cursor = end;
    }
    return new MasterServerResponse(extended, endpoints);
  }

  private static boolean startsWith(byte[] text, byte[] prefix) {
    if (text.length < prefix.length) return false;
    for (int i = 0; i < prefix.length; i++) if (text[i] != prefix[i]) return false;
    return true;
  }
}

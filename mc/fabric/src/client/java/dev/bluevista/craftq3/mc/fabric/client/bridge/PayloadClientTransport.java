package dev.bluevista.craftq3.mc.fabric.client.bridge;

import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import dev.bluevista.craftq3.engine.session.TransportFactory;
import dev.bluevista.craftq3.mc.fabric.net.Q3DatagramPayload;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The client's end of the bridge transport: the Quake session "connects" to {@link #SERVER}, a
 * synthetic address that is never resolved or dialled; datagrams sent there travel to the Minecraft
 * server as {@link Q3DatagramPayload}s, and the payloads it sends back are polled here as datagrams
 * from that address. The same path serves the integrated and a dedicated server.
 *
 * <p>Render thread only (the payload receiver and the session both run there).
 */
public final class PayloadClientTransport implements PacketTransport {
  /** The bridge server's address as the Quake session sees it ({@code connect 127.77.255.255}). */
  public static final InetSocketAddress SERVER =
      address(new byte[] {127, 77, (byte) 255, (byte) 255});

  /** The connect command's address text. */
  public static final String SERVER_TEXT = "127.77.255.255:27960";

  /** Queued datagrams before new ones are dropped. */
  public static final int MAX_QUEUED = 4096;

  private static final InetSocketAddress LOCAL =
      address(new byte[] {127, 77, (byte) 255, (byte) 254});

  private final ArrayDeque<byte[]> incoming = new ArrayDeque<>();
  private SocketAddress lastSource;
  private boolean open = true;
  private long sent;
  private long received;

  /** A datagram the server sent (payload receiver). */
  public void receive(byte[] data) {
    if (!open || incoming.size() >= MAX_QUEUED) return;
    incoming.add(data);
    received++;
  }

  @Override
  public boolean send(SocketAddress peer, byte[] data, int offset, int length) {
    if (!open) return false;
    if (length <= 0 || length > Q3DatagramPayload.MAX_BYTES)
      throw new IllegalArgumentException("Datagram of " + length + " bytes");
    if (!SERVER.equals(peer)) return true; // only the bridge server is reachable here
    if (!ClientPlayNetworking.canSend(Q3DatagramPayload.TYPE)) return false;
    ClientPlayNetworking.send(
        new Q3DatagramPayload(Arrays.copyOfRange(data, offset, offset + length)));
    sent++;
    return true;
  }

  @Override
  public int poll(byte[] buffer) {
    byte[] next = incoming.poll();
    if (next == null) return NONE;
    lastSource = SERVER;
    if (next.length > buffer.length) return OVERSIZE;
    System.arraycopy(next, 0, buffer, 0, next.length);
    return next.length;
  }

  @Override
  public SocketAddress lastSource() {
    return lastSource;
  }

  @Override
  public SocketAddress localAddress() {
    return LOCAL;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
    incoming.clear();
  }

  public long datagramsSent() {
    return sent;
  }

  public long datagramsReceived() {
    return received;
  }

  /**
   * Sockets for a bridge session: {@link #SERVER} is this transport (one per connection, the last
   * one opened receives), everything else real UDP.
   */
  public static final class Factory implements TransportFactory {
    private PayloadClientTransport current;

    /** The transport of the latest connection to the bridge server, or null. */
    public PayloadClientTransport current() {
      return current;
    }

    @Override
    public PacketTransport listen(InetSocketAddress local) throws IOException {
      return TransportFactory.UDP.listen(local);
    }

    @Override
    public PacketTransport connect(InetSocketAddress server) throws IOException {
      if (!SERVER.equals(server)) return TransportFactory.UDP.connect(server);
      if (current != null) current.close();
      current = new PayloadClientTransport();
      return current;
    }

    @Override
    public PacketTransport ephemeral(boolean ipv6) throws IOException {
      return TransportFactory.UDP.ephemeral(ipv6);
    }
  }

  private static InetSocketAddress address(byte[] ip) {
    try {
      return new InetSocketAddress(InetAddress.getByAddress(ip), 27960);
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }
}

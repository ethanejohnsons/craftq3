package dev.bluevista.craftq3.mc.fabric.net;

import dev.bluevista.craftq3.engine.net.transport.PacketTransport;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * The bridge game's end of the payload transport: datagrams Minecraft players send as {@link
 * Q3DatagramPayload}s arrive here from a synthetic per-player address, and datagrams the game sends
 * to such an address go back to that player's connection. Each player gets its own address while it
 * is bridged ({@link #open}), so the protocol-68 host keeps one connection per player exactly as it
 * would for UDP clients.
 *
 * <p>Owned by the Minecraft server thread (the payload receivers and the bridge tick both run
 * there). The incoming queue is bounded; datagrams beyond it are dropped like a full socket buffer.
 */
public final class PayloadServerTransport implements PacketTransport {
  /** Queued datagrams per transport before new ones are dropped. */
  public static final int MAX_QUEUED = 4096;

  /** The port every synthetic address uses. */
  public static final int PORT = 27960;

  private record Datagram(SocketAddress from, byte[] data) {}

  private final ArrayDeque<Datagram> incoming = new ArrayDeque<>();
  private final Map<SocketAddress, Object> peers = new HashMap<>();
  private final Map<Object, SocketAddress> addresses = new HashMap<>();
  private final BiConsumer<Object, byte[]> sender;
  private final SocketAddress local;
  private int nextAddress = 1;
  private SocketAddress lastSource;
  private boolean open = true;

  /**
   * @param sender delivers {@code data} to the player key passed to {@link #open}
   */
  public PayloadServerTransport(BiConsumer<Object, byte[]> sender) {
    this.sender = Objects.requireNonNull(sender, "sender");
    this.local = address(0);
  }

  /** Gives {@code player} (any stable key) its own address; returns the existing one if open. */
  public SocketAddress open(Object player) {
    SocketAddress existing = addresses.get(player);
    if (existing != null) return existing;
    SocketAddress address = address(nextAddress++);
    peers.put(address, player);
    addresses.put(player, address);
    return address;
  }

  /** Forgets {@code player}'s address; later datagrams to it are discarded. */
  public void close(Object player) {
    SocketAddress address = addresses.remove(player);
    if (address != null) peers.remove(address);
  }

  /** The address of {@code player}, or null. */
  public SocketAddress addressOf(Object player) {
    return addresses.get(player);
  }

  /** The address text the game server records for {@code player} ({@code ip:port}), or null. */
  public String addressText(Object player) {
    SocketAddress address = addresses.get(player);
    return address instanceof InetSocketAddress inet
        ? inet.getAddress().getHostAddress() + ":" + inet.getPort()
        : null;
  }

  /** A datagram {@code player} sent (payload receiver). */
  public void receive(Object player, byte[] data) {
    SocketAddress from = addresses.get(player);
    if (from == null || !open || incoming.size() >= MAX_QUEUED) return;
    incoming.add(new Datagram(from, data));
  }

  @Override
  public boolean send(SocketAddress peer, byte[] data, int offset, int length) {
    if (!open) return false;
    if (length <= 0 || length > Q3DatagramPayload.MAX_BYTES)
      throw new IllegalArgumentException("Datagram of " + length + " bytes");
    Object player = peers.get(peer);
    if (player == null) return true; // gone: dropped like a datagram to a closed port
    sender.accept(player, Arrays.copyOfRange(data, offset, offset + length));
    return true;
  }

  @Override
  public int poll(byte[] buffer) {
    Datagram next = incoming.poll();
    if (next == null) return NONE;
    lastSource = next.from();
    if (next.data().length > buffer.length) return OVERSIZE;
    System.arraycopy(next.data(), 0, buffer, 0, next.data().length);
    return next.data().length;
  }

  @Override
  public SocketAddress lastSource() {
    return lastSource;
  }

  @Override
  public SocketAddress localAddress() {
    return local;
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public void close() {
    open = false;
    incoming.clear();
    peers.clear();
    addresses.clear();
  }

  /** {@code 127.77.hi.lo}: never a real host, never resolved. */
  private static SocketAddress address(int n) {
    try {
      return new InetSocketAddress(
          InetAddress.getByAddress(new byte[] {127, 77, (byte) (n >>> 8), (byte) n}), PORT);
    } catch (UnknownHostException impossible) {
      throw new AssertionError(impossible);
    }
  }
}

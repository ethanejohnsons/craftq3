package dev.bluevista.craftq3.client.net;

import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Bounded native-observed LAN status requests; packets and returned text are never executed. */
public final class BrowserStatus {
  public static final int MAX_REQUESTS = 16;
  public static final int TEXT_CAPACITY = 8192;
  private final BiConsumer<InetSocketAddress, byte[]> outbound;
  private final Slot[] slots = new Slot[MAX_REQUESTS];

  private static final class Slot {
    InetSocketAddress address;
    String text = "";
    int started;
    boolean pending;
    boolean released = true;
  }

  /** Starts in the native reset-all state; the owner supplies its logical clock and resend cvar. */
  public BrowserStatus(BiConsumer<InetSocketAddress, byte[]> outbound) {
    this.outbound = Objects.requireNonNull(outbound);
    for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
  }

  /**
   * Empty means pending/unavailable and leaves a guest destination untouched. A completed result is
   * capped for the caller's NUL-terminated capacity and remains cached until its slot is reused.
   */
  public Optional<String> query(
      InetSocketAddress address, int capacity, int nowMillis, int resendMillis) {
    requireAddress(address);
    if (capacity < 1 || capacity > TEXT_CAPACITY || resendMillis < 0)
      throw new IllegalArgumentException("Invalid status query bounds");
    Slot slot = select(address);
    if (address.equals(slot.address)) {
      if (!slot.pending) {
        slot.released = true;
        slot.started = 0;
        return Optional.of(slot.text.substring(0, Math.min(slot.text.length(), capacity - 1)));
      }
      // The native comparison subtracts before comparing; signed timer wrap is observable.
      if (slot.started < nowMillis - resendMillis) {
        slot.started = nowMillis;
        slot.released = false;
        send(address);
      }
    } else if (slot.released) {
      slot.address = address;
      slot.text = "";
      slot.started = nowMillis;
      slot.pending = true;
      slot.released = false;
      send(address);
    }
    return Optional.empty();
  }

  /**
   * Releases a matching slot without erasing its cached result or rejecting an already pending
   * reply. An unknown endpoint releases the selected unused/oldest slot, as the native API does.
   */
  public void reset(InetSocketAddress address) {
    requireAddress(address);
    select(address).released = true;
  }

  public void resetAll() {
    for (Slot slot : slots) {
      slot.address = null;
      slot.released = true;
    }
  }

  /**
   * Ingests the raw bytes after the statusResponse command line. The caller has already checked the
   * connectionless envelope. Only a retained matching endpoint accepts the response.
   */
  public boolean receive(InetSocketAddress address, byte[] body) {
    requireAddress(address);
    Objects.requireNonNull(body);
    if (body.length > ConnectionlessPacket.MAX_PAYLOAD)
      throw new IllegalArgumentException("Status response too long");
    for (Slot slot : slots) {
      if (!address.equals(slot.address)) continue;
      var reader = new Lines(body);
      var text = new StringBuilder();
      append(text, reader.line());
      append(text, "\\\\");
      for (String line; !(line = reader.line()).isEmpty(); ) {
        append(text, line);
        append(text, "\\");
      }
      slot.text = text.toString();
      slot.pending = false;
      return true;
    }
    return false;
  }

  private Slot select(InetSocketAddress address) {
    for (Slot slot : slots) if (address.equals(slot.address)) return slot;
    for (Slot slot : slots) if (slot.released) return slot;
    Slot oldest = slots[0];
    for (Slot slot : slots) if (slot.started < oldest.started) oldest = slot;
    return oldest;
  }

  private void send(InetSocketAddress address) {
    outbound.accept(address, ConnectionlessPacket.text("getstatus"));
  }

  private static void requireAddress(InetSocketAddress address) {
    if (address == null || address.isUnresolved() || address.getPort() < 1)
      throw new IllegalArgumentException("Resolved status endpoint required");
  }

  private static void append(StringBuilder target, String text) {
    int count = Math.min(text.length(), TEXT_CAPACITY - 1 - target.length());
    target.append(text, 0, count);
  }

  private static final class Lines {
    private final byte[] bytes;
    private int cursor;

    Lines(byte[] bytes) {
      this.bytes = bytes;
    }

    String line() {
      var line = new StringBuilder();
      while (cursor < bytes.length) {
        int symbol = bytes[cursor++] & 255;
        if (symbol == 0 || symbol == '\n' || line.length() == 1023) break;
        line.append(symbol == '%' || symbol > 127 ? '.' : (char) symbol);
      }
      return line.toString();
    }
  }
}

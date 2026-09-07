package dev.bluevista.craftq3.client.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Portable host-owned browser cache, unrelated to the native server-cache layout. This codec does
 * no file I/O, address resolution, or networking. Its caller owns atomic file replacement.
 *
 * <p>Version 1 is big endian: magic CQBC, unsigned-short
 * version/reserved/global-count/favorite-count, global records, favorite records, then CRC32 of all
 * preceding bytes. A record contains a family byte (4 or 6), its 4/16 address bytes, signed
 * nonnegative scope ID (zero for IPv4), unsigned-short port, three unsigned-byte-length Latin-1
 * strings (hostname/map/game), then the eleven signed integer fields in {@link LanServerList.Entry}
 * order. Text excludes NUL and is bounded to 79/31/31 bytes. Extra bytes, unknown versions and
 * invalid records are rejected.
 *
 * <p>Only active global/favorite records with resolved addresses are saved. A pending global count
 * of -1 saves an empty global list; null-address placeholders and inactive retained slots are
 * omitted. A resolved record's pending ping of -1 is preserved. Restore replaces both lists and
 * clears their inactive slots, leaving local discovery untouched. Scope IDs are retained as numeric
 * metadata; the caller decides whether they remain usable on another machine.
 */
public final class BrowserCache {
  public static final String FILE_NAME = "craftq3-browser-v1.bin";
  public static final int MAX_BYTES =
      16 + (LanServerList.GLOBAL_CAPACITY + LanServerList.OTHER_CAPACITY) * 211;

  private static final int MAGIC = 0x43514243;
  private static final int VERSION = 1;

  private BrowserCache() {}

  /** Detached immutable records, validated completely before either destination list is changed. */
  public record Snapshot(List<LanServerList.Entry> global, List<LanServerList.Entry> favorites) {
    public Snapshot {
      global = validate(global, LanServerList.GLOBAL_CAPACITY);
      favorites = validate(favorites, LanServerList.OTHER_CAPACITY);
    }

    /** Host-thread operation; no callbacks or fallible parsing occur during application. */
    public void applyTo(LanServerList destination) {
      Objects.requireNonNull(destination);
      replace(destination, LanServerList.GLOBAL, global);
      replace(destination, LanServerList.FAVORITES, favorites);
    }
  }

  public static byte[] encode(LanServerList source) {
    Objects.requireNonNull(source);
    Snapshot snapshot =
        new Snapshot(active(source, LanServerList.GLOBAL), active(source, LanServerList.FAVORITES));
    ByteBuffer out = ByteBuffer.allocate(MAX_BYTES).order(ByteOrder.BIG_ENDIAN);
    out.putInt(MAGIC).putShort((short) VERSION).putShort((short) 0);
    out.putShort((short) snapshot.global.size()).putShort((short) snapshot.favorites.size());
    for (LanServerList.Entry entry : snapshot.global) write(out, entry);
    for (LanServerList.Entry entry : snapshot.favorites) write(out, entry);
    CRC32 crc = new CRC32();
    crc.update(out.array(), 0, out.position());
    out.putInt((int) crc.getValue());
    return java.util.Arrays.copyOf(out.array(), out.position());
  }

  /**
   * Invalid or truncated input throws IllegalArgumentException without changing any server list.
   */
  public static Snapshot decode(byte[] bytes) {
    Objects.requireNonNull(bytes);
    if (bytes.length < 16 || bytes.length > MAX_BYTES) throw invalid();
    CRC32 crc = new CRC32();
    crc.update(bytes, 0, bytes.length - 4);
    ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    if (in.getInt(bytes.length - 4) != (int) crc.getValue()) throw invalid();
    in.limit(bytes.length - 4);
    if (in.getInt() != MAGIC || Short.toUnsignedInt(in.getShort()) != VERSION || in.getShort() != 0)
      throw invalid();
    int globals = Short.toUnsignedInt(in.getShort());
    int favorites = Short.toUnsignedInt(in.getShort());
    if (globals > LanServerList.GLOBAL_CAPACITY || favorites > LanServerList.OTHER_CAPACITY)
      throw invalid();
    try {
      List<LanServerList.Entry> global = read(in, globals);
      List<LanServerList.Entry> favorite = read(in, favorites);
      if (in.hasRemaining()) throw invalid();
      return new Snapshot(global, favorite);
    } catch (java.nio.BufferUnderflowException | UnknownHostException e) {
      throw new IllegalArgumentException("Invalid browser cache", e);
    }
  }

  /** Decode and validate the entire cache before committing either list. */
  public static void restore(LanServerList destination, byte[] bytes) {
    Objects.requireNonNull(destination);
    decode(bytes).applyTo(destination);
  }

  private static List<LanServerList.Entry> active(LanServerList source, int category) {
    List<LanServerList.Entry> entries = new ArrayList<>();
    for (int i = 0; i < source.count(category); i++) {
      LanServerList.Entry entry = source.entry(category, i).orElseThrow();
      if (entry.address() != null) entries.add(entry);
    }
    return entries;
  }

  private static List<LanServerList.Entry> validate(
      List<LanServerList.Entry> entries, int maximum) {
    Objects.requireNonNull(entries);
    if (entries.size() > maximum) throw invalid();
    List<LanServerList.Entry> copy = List.copyOf(entries);
    for (LanServerList.Entry entry : copy) {
      if (entry.address() == null || entry.address().isUnresolved()) throw invalid();
      InetAddress address = entry.address().getAddress();
      if (!(address instanceof Inet4Address) && !(address instanceof Inet6Address)) throw invalid();
      if (address instanceof Inet6Address v6 && v6.getScopeId() < 0) throw invalid();
    }
    return copy;
  }

  private static void replace(
      LanServerList destination, int source, List<LanServerList.Entry> entries) {
    for (int i = 0; i < destination.capacity(source); i++)
      destination.updateEntry(
          source, i, i < entries.size() ? entries.get(i) : LanServerList.Entry.empty());
    destination.setCount(source, entries.size());
  }

  private static void write(ByteBuffer out, LanServerList.Entry entry) {
    InetAddress address = entry.address().getAddress();
    out.put((byte) (address instanceof Inet6Address ? 6 : 4));
    out.put(address.getAddress());
    out.putInt(address instanceof Inet6Address v6 ? v6.getScopeId() : 0);
    out.putShort((short) entry.address().getPort());
    write(out, entry.hostName());
    write(out, entry.mapName());
    write(out, entry.game());
    out.putInt(entry.netType()).putInt(entry.gameType()).putInt(entry.clients());
    out.putInt(entry.maxClients()).putInt(entry.minPing()).putInt(entry.maxPing());
    out.putInt(entry.ping()).putInt(entry.visible()).putInt(entry.punkbuster());
    out.putInt(entry.humanPlayers()).putInt(entry.needPass());
  }

  private static void write(ByteBuffer out, String text) {
    out.put((byte) text.length()).put(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static List<LanServerList.Entry> read(ByteBuffer in, int count)
      throws UnknownHostException {
    List<LanServerList.Entry> entries = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      int family = Byte.toUnsignedInt(in.get());
      if (family != 4 && family != 6) throw invalid();
      byte[] raw = new byte[family == 4 ? 4 : 16];
      in.get(raw);
      int scope = in.getInt();
      if (scope < 0 || (family == 4 && scope != 0)) throw invalid();
      InetAddress address =
          family == 4 ? InetAddress.getByAddress(raw) : Inet6Address.getByAddress(null, raw, scope);
      InetSocketAddress peer = new InetSocketAddress(address, Short.toUnsignedInt(in.getShort()));
      String host = readText(in, 79);
      String map = readText(in, 31);
      String game = readText(in, 31);
      entries.add(
          new LanServerList.Entry(
              peer,
              host,
              map,
              game,
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt(),
              in.getInt()));
    }
    return entries;
  }

  private static String readText(ByteBuffer in, int maximum) {
    int length = Byte.toUnsignedInt(in.get());
    if (length > maximum) throw invalid();
    byte[] text = new byte[length];
    in.get(text);
    for (byte value : text) if (value == 0) throw invalid();
    return new String(text, StandardCharsets.ISO_8859_1);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid browser cache");
  }
}

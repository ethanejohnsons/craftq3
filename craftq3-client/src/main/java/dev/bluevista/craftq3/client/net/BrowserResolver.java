package dev.bluevista.craftq3.client.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/** Bounded asynchronous browser DNS policy; numeric literals never enter the DNS worker pool. */
public final class BrowserResolver implements AutoCloseable {
  public static final int MAX_ACTIVE = 64;
  public static final int MAX_CACHED = 256;
  public static final long TIMEOUT_MILLIS = 15_000;
  public static final long RETRY_MILLIS = 30_000;

  @FunctionalInterface
  public interface Resolver {
    InetAddress[] lookup(String host) throws UnknownHostException;
  }

  public enum State {
    UNKNOWN,
    PENDING,
    RESOLVED,
    FAILED,
    CLOSED
  }

  public record Status(State state, String diagnostic) {}

  private record Key(String host, int port) {}

  private static final class Entry {
    State state;
    String diagnostic = "";
    InetSocketAddress address;
    CompletableFuture<InetAddress[]> future;
    volatile Thread runner;
    long started;
    long failed;
  }

  private final Resolver resolver;
  private final LongSupplier clock;
  private final ExecutorService workers =
      Executors.newThreadPerTaskExecutor(
          Thread.ofVirtual().name("craftq3-browser-dns-", 0).factory());
  private final Semaphore active = new Semaphore(MAX_ACTIVE);
  private final LinkedHashMap<Key, Entry> cache = new LinkedHashMap<>(32, .75f, true);
  private boolean closed;

  public BrowserResolver() {
    this(InetAddress::getAllByName, () -> System.nanoTime() / 1_000_000L);
  }

  public BrowserResolver(Resolver resolver, LongSupplier clockMillis) {
    this.resolver = Objects.requireNonNull(resolver);
    this.clock = Objects.requireNonNull(clockMillis);
  }

  /**
   * Invalid syntax throws before starting work. Empty means pending or failed; inspect status for
   * the distinction. A failed lookup can retry after thirty seconds on a later resolve call.
   */
  public synchronized Optional<InetSocketAddress> resolve(String input, int defaultPort) {
    if (closed) return Optional.empty();
    RemoteAddress parsed = parse(input, defaultPort);
    Key key = key(parsed);
    long now = clock.getAsLong();
    Entry entry = cache.get(key);
    if (entry != null) {
      update(entry, key.port, now);
      if (entry.state == State.RESOLVED) return Optional.of(entry.address);
      if (entry.state != State.FAILED || now - entry.failed < RETRY_MILLIS) return Optional.empty();
    }
    Entry next = new Entry();
    next.started = now;
    cache.put(key, next);
    trim();
    if (numeric(parsed.host())) {
      try {
        next.address = new InetSocketAddress(literal(parsed.host()), parsed.port());
        next.state = State.RESOLVED;
        return Optional.of(next.address);
      } catch (IllegalArgumentException | UnknownHostException | SocketException failure) {
        fail(next, now, failure.getMessage());
        return Optional.empty();
      }
    }
    if (!active.tryAcquire()) {
      fail(next, now, "Too many active browser DNS lookups");
      return Optional.empty();
    }
    next.state = State.PENDING;
    var future = new CompletableFuture<InetAddress[]>();
    next.future = future;
    try {
      workers.execute(
          () -> {
            next.runner = Thread.currentThread();
            try {
              if (future.isCancelled()) return;
              InetAddress[] addresses = resolver.lookup(parsed.host());
              if (addresses == null || addresses.length == 0 || addresses.length > 256)
                throw new UnknownHostException("Resolver returned no bounded address set");
              future.complete(addresses.clone());
            } catch (Throwable failure) {
              future.completeExceptionally(failure);
            } finally {
              next.runner = null;
              active.release();
            }
          });
    } catch (RuntimeException rejected) {
      active.release();
      fail(next, now, rejected.getMessage());
    }
    return Optional.empty();
  }

  /** Observes completion/timeout without starting or retrying a DNS lookup. */
  public synchronized Status status(String input, int defaultPort) {
    if (closed) return new Status(State.CLOSED, "Browser resolver is closed");
    RemoteAddress parsed = parse(input, defaultPort);
    Key key = key(parsed);
    Entry entry = cache.get(key);
    if (entry == null) return new Status(State.UNKNOWN, "");
    update(entry, key.port, clock.getAsLong());
    return new Status(entry.state, entry.diagnostic);
  }

  public int activeLookups() {
    return MAX_ACTIVE - active.availablePermits();
  }

  public synchronized int cachedEntries() {
    return cache.size();
  }

  /**
   * Cancels futures and requests executor shutdown; it never waits for a blocked platform DNS call.
   */
  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    for (Entry entry : cache.values()) cancel(entry);
    cache.clear();
    workers.shutdownNow();
  }

  private void update(Entry entry, int port, long now) {
    if (entry.state != State.PENDING) return;
    if (now - entry.started >= TIMEOUT_MILLIS) {
      cancel(entry);
      fail(entry, now, "Browser DNS lookup timed out");
      return;
    }
    if (!entry.future.isDone()) return;
    try {
      InetAddress[] addresses = entry.future.get();
      InetAddress selected = null;
      for (InetAddress address : addresses) {
        if (address instanceof Inet4Address) {
          selected = address;
          break;
        }
        if (selected == null && address instanceof Inet6Address) selected = address;
      }
      if (selected == null) throw new UnknownHostException("Resolver returned no IP address");
      entry.address = new InetSocketAddress(selected, port);
      entry.state = State.RESOLVED;
      entry.future = null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(entry, now, "Browser DNS result interrupted");
    } catch (ExecutionException | CancellationException | UnknownHostException failure) {
      Throwable reason = failure.getCause() == null ? failure : failure.getCause();
      fail(entry, now, reason.getMessage());
    }
  }

  private static void fail(Entry entry, long now, String diagnostic) {
    entry.state = State.FAILED;
    entry.failed = now;
    var text = new StringBuilder();
    if (diagnostic != null) {
      for (int i = 0; i < diagnostic.length() && text.length() < 256; i++) {
        char c = diagnostic.charAt(i);
        text.append(c < 32 || c == 127 ? ' ' : c > 255 ? '?' : c);
      }
    }
    entry.diagnostic = text.isEmpty() ? "Browser DNS lookup failed" : text.toString();
  }

  private void trim() {
    while (cache.size() > MAX_CACHED) {
      Key remove = null;
      for (var entry : cache.entrySet()) {
        if (entry.getValue().state != State.PENDING) {
          remove = entry.getKey();
          break;
        }
      }
      if (remove == null) remove = cache.firstEntry().getKey();
      Entry removed = cache.remove(remove);
      cancel(removed);
    }
  }

  private static Key key(RemoteAddress address) {
    String host = address.host();
    return new Key(host.indexOf(':') >= 0 ? host : host.toLowerCase(Locale.ROOT), address.port());
  }

  private static void cancel(Entry entry) {
    if (entry.future != null) entry.future.cancel(true);
    Thread runner = entry.runner;
    if (runner != null) runner.interrupt();
  }

  private static RemoteAddress parse(String input, int defaultPort) {
    if (defaultPort < 1 || defaultPort > 65535)
      throw new IllegalArgumentException("Invalid browser default port");
    RemoteAddress parsed = RemoteAddress.parse(input);
    boolean explicit =
        input.startsWith("[")
            ? input.indexOf(']') < input.length() - 1
            : input.indexOf(':') >= 0 && input.indexOf(':') == input.lastIndexOf(':');
    return explicit ? parsed : new RemoteAddress(parsed.host(), defaultPort);
  }

  private static boolean numeric(String host) {
    return host.indexOf(':') >= 0
        || host.indexOf('.') >= 0 && host.chars().allMatch(c -> c == '.' || c >= '0' && c <= '9');
  }

  private static InetAddress literal(String host) throws UnknownHostException, SocketException {
    if (host.indexOf(':') < 0) {
      String[] parts = host.split("\\.");
      byte[] bytes = new byte[4];
      for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(parts[i]);
      return InetAddress.getByAddress(bytes);
    }
    int percent = host.indexOf('%');
    String base = percent < 0 ? host : host.substring(0, percent);
    String scope = percent < 0 ? "" : host.substring(percent + 1);
    int gap = base.indexOf("::");
    var left = groups(gap < 0 ? base : base.substring(0, gap));
    var right = gap < 0 ? new ArrayList<Integer>() : groups(base.substring(gap + 2));
    byte[] bytes = new byte[16];
    for (int i = 0; i < left.size(); i++) word(bytes, i, left.get(i));
    for (int i = 0; i < right.size(); i++) word(bytes, 8 - right.size() + i, right.get(i));
    if (scope.isEmpty()) return Inet6Address.getByAddress(null, bytes, 0);
    if (scope.chars().allMatch(c -> c >= '0' && c <= '9'))
      return Inet6Address.getByAddress(null, bytes, Integer.parseInt(scope));
    NetworkInterface network = NetworkInterface.getByName(scope);
    if (network == null) throw new UnknownHostException("Unknown IPv6 scope interface");
    return Inet6Address.getByAddress(null, bytes, network);
  }

  private static ArrayList<Integer> groups(String part) {
    var words = new ArrayList<Integer>();
    if (!part.isEmpty()) {
      for (String group : part.split(":")) {
        if (group.indexOf('.') < 0) words.add(Integer.parseInt(group, 16));
        else {
          String[] octets = group.split("\\.");
          words.add(Integer.parseInt(octets[0]) << 8 | Integer.parseInt(octets[1]));
          words.add(Integer.parseInt(octets[2]) << 8 | Integer.parseInt(octets[3]));
        }
      }
    }
    return words;
  }

  private static void word(byte[] bytes, int index, int value) {
    bytes[index * 2] = (byte) (value >>> 8);
    bytes[index * 2 + 1] = (byte) value;
  }
}

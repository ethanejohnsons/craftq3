package dev.bluevista.craftq3.client.net;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

final class BrowserResolverTest {
  @Test
  void numericLiteralsResolveImmediatelyWithExplicitPortsAndIpv6FamilyPreserved() throws Exception {
    var calls = new AtomicInteger();
    try (var resolver =
        new BrowserResolver(
            host -> {
              calls.incrementAndGet();
              throw new UnknownHostException();
            },
            () -> 0)) {
      assertEquals(27950, resolver.resolve("127.000.000.001", 27950).orElseThrow().getPort());
      assertEquals(27960, resolver.resolve("127.0.0.1:27960", 27950).orElseThrow().getPort());
      var mapped = resolver.resolve("[::ffff:127.0.0.1%7]:27960", 27950).orElseThrow();
      assertInstanceOf(Inet6Address.class, mapped.getAddress());
      assertEquals(7, ((Inet6Address) mapped.getAddress()).getScopeId());
      assertArrayEquals(
          HexFormat.of().parseHex("00000000000000000000ffff7f000001"),
          mapped.getAddress().getAddress());
      var ordinary = resolver.resolve("2001:db8::1", 27950).orElseThrow();
      assertEquals(27950, ordinary.getPort());
      assertArrayEquals(
          HexFormat.of().parseHex("20010db8000000000000000000000001"),
          ordinary.getAddress().getAddress());
      assertArrayEquals(
          HexFormat.of().parseHex("0001000200030004000500067f000001"),
          resolver.resolve("1:2:3:4:5:6:127.0.0.1", 1).orElseThrow().getAddress().getAddress());
      assertEquals(0, calls.get());
    }
  }

  @Test
  void hostnameWorkIsAsynchronousAndIpv4WinsWithoutRepeatingCachedLookups() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var resolver =
        new BrowserResolver(
            host -> {
              calls.incrementAndGet();
              entered.countDown();
              await(release);
              return new InetAddress[] {Inet6Address.getByAddress(null, new byte[16], 0), ipv4(9)};
            },
            () -> 0)) {
      assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () -> assertTrue(resolver.resolve("Example.invalid", 27960).isEmpty()));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(
          BrowserResolver.State.PENDING, resolver.status("example.invalid", 27960).state());
      release.countDown();
      until(
          () ->
              resolver.status("example.invalid", 27960).state() == BrowserResolver.State.RESOLVED);
      assertArrayEquals(
          new byte[] {127, 0, 0, 9},
          resolver.resolve("EXAMPLE.invalid", 27960).orElseThrow().getAddress().getAddress());
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void failuresHaveBoundedDiagnosticsAndRetryOnlyAfterThirtySeconds() throws Exception {
    var clock = new AtomicLong();
    var calls = new AtomicInteger();
    try (var resolver =
        new BrowserResolver(
            host -> {
              if (calls.incrementAndGet() == 1)
                throw new UnknownHostException("bad\n\0\u2603" + "x".repeat(300));
              return new InetAddress[] {ipv4(2)};
            },
            clock::get)) {
      assertEquals(BrowserResolver.State.UNKNOWN, resolver.status("retry.invalid", 1).state());
      resolver.resolve("retry.invalid", 1);
      until(() -> resolver.status("retry.invalid", 1).state() == BrowserResolver.State.FAILED);
      var diagnostic = resolver.status("retry.invalid", 1).diagnostic();
      assertEquals(256, diagnostic.length());
      assertTrue(diagnostic.startsWith("bad  ?"));
      clock.set(29_999);
      assertTrue(resolver.resolve("retry.invalid", 1).isEmpty());
      assertEquals(1, calls.get());
      clock.set(30_000);
      assertTrue(resolver.resolve("retry.invalid", 1).isEmpty());
      until(() -> resolver.status("retry.invalid", 1).state() == BrowserResolver.State.RESOLVED);
      assertEquals(2, calls.get());
    }
  }

  @Test
  void lateTimedOutResultCannotReplaceASecondSuccessfulAttempt() throws Exception {
    var clock = new AtomicLong();
    var calls = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var resolver =
        new BrowserResolver(
            host -> {
              if (calls.incrementAndGet() == 1) {
                entered.countDown();
                awaitIgnoringInterrupts(release);
                return new InetAddress[] {ipv4(1)};
              }
              return new InetAddress[] {ipv4(2)};
            },
            clock::get)) {
      resolver.resolve("late.invalid", 1);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      clock.set(14_999);
      assertEquals(BrowserResolver.State.PENDING, resolver.status("late.invalid", 1).state());
      clock.set(15_000);
      assertEquals(BrowserResolver.State.FAILED, resolver.status("late.invalid", 1).state());
      assertEquals(1, resolver.activeLookups());
      clock.set(45_000);
      resolver.resolve("late.invalid", 1);
      until(() -> resolver.status("late.invalid", 1).state() == BrowserResolver.State.RESOLVED);
      release.countDown();
      until(() -> resolver.activeLookups() == 0);
      assertEquals(ipv4(2), resolver.resolve("late.invalid", 1).orElseThrow().getAddress());
    } finally {
      release.countDown();
    }
  }

  @Test
  void uninterruptibleDnsKeepsTheActiveBoundAndCloseDoesNotWaitForIt() throws Exception {
    var clock = new AtomicLong();
    var entered = new CountDownLatch(64);
    var release = new CountDownLatch(1);
    var resolver =
        new BrowserResolver(
            host -> {
              entered.countDown();
              awaitIgnoringInterrupts(release);
              return new InetAddress[] {ipv4(1)};
            },
            clock::get);
    try {
      for (int i = 0; i < 64; i++) resolver.resolve("host" + i + ".invalid", 1);
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      clock.set(15_000);
      for (int i = 0; i < 64; i++)
        assertEquals(
            BrowserResolver.State.FAILED, resolver.status("host" + i + ".invalid", 1).state());
      assertTrue(resolver.resolve("overflow.invalid", 1).isEmpty());
      assertEquals(BrowserResolver.State.FAILED, resolver.status("overflow.invalid", 1).state());
      assertEquals(64, resolver.activeLookups());
      assertTimeoutPreemptively(Duration.ofSeconds(1), resolver::close);
      assertEquals(BrowserResolver.State.CLOSED, resolver.status("host0.invalid", 1).state());
      assertTrue(resolver.resolve("after-close.invalid", 1).isEmpty());
    } finally {
      release.countDown();
      resolver.close();
      until(() -> resolver.activeLookups() == 0);
    }
  }

  @Test
  void lruCacheRetainsActiveRequestsWhileEvictingCompletedNumericEntries() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var resolver =
        new BrowserResolver(
            host -> {
              entered.countDown();
              await(release);
              return new InetAddress[] {ipv4(1)};
            },
            () -> 0)) {
      resolver.resolve("pending.invalid", 1);
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      for (int i = 0; i < 300; i++) resolver.resolve("127.0.0.1:" + (28000 + i), 1);
      assertEquals(256, resolver.cachedEntries());
      assertEquals(BrowserResolver.State.UNKNOWN, resolver.status("127.0.0.1:28000", 1).state());
      assertEquals(BrowserResolver.State.PENDING, resolver.status("pending.invalid", 1).state());
      assertEquals(BrowserResolver.State.RESOLVED, resolver.status("127.0.0.1:28299", 1).state());
    } finally {
      release.countDown();
    }
  }

  @Test
  void malformedInputsDoNotStartWorkersOrPopulateTheCache() {
    var calls = new AtomicInteger();
    try (var resolver =
        new BrowserResolver(
            host -> {
              calls.incrementAndGet();
              return new InetAddress[0];
            },
            () -> 0)) {
      for (String input :
          new String[] {"", "bad/host", "127.0.0.1:0", "[::1", "x:65536", "x\nquit"})
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(input, 27960));
      assertThrows(IllegalArgumentException.class, () -> resolver.resolve("localhost", 0));
      assertEquals(0, resolver.activeLookups());
      assertEquals(0, resolver.cachedEntries());
      assertEquals(0, calls.get());
    }
  }

  private static InetAddress ipv4(int last) throws UnknownHostException {
    return InetAddress.getByAddress(new byte[] {127, 0, 0, (byte) last});
  }

  private static void await(CountDownLatch latch) throws UnknownHostException {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new UnknownHostException("Interrupted test lookup");
    }
  }

  private static void awaitIgnoringInterrupts(CountDownLatch latch) {
    boolean done = false;
    while (!done) {
      try {
        latch.await();
        done = true;
      } catch (InterruptedException ignored) {
        /* Models a platform resolver that cannot cancel promptly. */
      }
    }
  }

  private static void until(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
    assertTrue(condition.getAsBoolean(), "Asynchronous browser operation did not finish");
  }
}

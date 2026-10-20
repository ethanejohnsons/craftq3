package dev.bluevista.craftq3.engine.game.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * {@code SVC_RemoteCommand}'s authentication: the password is compared in constant time (digests of
 * both sides are compared with {@link MessageDigest#isEqual}, so neither the content nor the length
 * of the configured password leaks through timing), an empty configured password refuses
 * everything, and requests are limited to one per {@link #MIN_INTERVAL_MILLIS}.
 */
public final class RconAuthenticator {
  /** The original's minimum spacing of rcon requests, in milliseconds. */
  public static final int MIN_INTERVAL_MILLIS = 500;

  /** The outcome of one request. */
  public enum Result {
    ACCEPTED,
    /** {@code No rconpassword set on the server.} */
    NO_PASSWORD,
    /** {@code Bad rconpassword.} */
    BAD_PASSWORD,
    /** Too soon after the previous request; ignored without a reply. */
    RATE_LIMITED
  }

  /** A parsed request: the password and the command text after it. */
  public record Request(String password, String command) {}

  private long last = Long.MIN_VALUE;

  /**
   * Splits {@code rcon <password> <command>} the way the original walks the raw line: the command
   * keeps its own spacing and quoting.
   */
  public static Request parse(String line) {
    int i = 0, n = line.length();
    while (i < n && line.charAt(i) == ' ') i++;
    i = Math.min(n, i + 4); // "rcon"
    while (i < n && line.charAt(i) == ' ') i++;
    int start = i;
    String password;
    if (i < n && line.charAt(i) == '"') {
      int close = line.indexOf('"', i + 1);
      int end = close < 0 ? n : close;
      password = line.substring(i + 1, end);
      i = close < 0 ? n : close + 1;
    } else {
      while (i < n && line.charAt(i) != ' ') i++;
      password = line.substring(start, i);
    }
    while (i < n && line.charAt(i) == ' ') i++;
    return new Request(password, line.substring(i));
  }

  /** Checks one request against {@code configured} at {@code now}. */
  public Result check(String configured, String supplied, long now) {
    if (last != Long.MIN_VALUE && now - last < MIN_INTERVAL_MILLIS && now >= last)
      return Result.RATE_LIMITED;
    last = now;
    if (configured == null || configured.isEmpty()) return Result.NO_PASSWORD;
    return matches(configured, supplied) ? Result.ACCEPTED : Result.BAD_PASSWORD;
  }

  /** Constant-time password equality. */
  public static boolean matches(String configured, String supplied) {
    byte[] a = digest(configured), b = digest(supplied == null ? "" : supplied);
    return MessageDigest.isEqual(a, b);
  }

  private static byte[] digest(String text) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.ISO_8859_1));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("Java requires SHA-256", impossible);
    }
  }
}

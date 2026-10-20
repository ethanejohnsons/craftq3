package dev.bluevista.craftq3.server.net;

/** Socket-accepted packet pacing and a bounded, sequence-checked acknowledgement clock. */
public final class HostPacketTiming {
  private final int[] sequences = new int[32];
  private final long[] sent = new long[32], acknowledged = new long[32];
  private final boolean[] complete = new boolean[32], sampled = new boolean[32];

  public static int clientRate(String value) {
    return value == null || value.isEmpty() ? 3000 : Math.clamp(integer(value), 1000, 90000);
  }

  public static int snapshotInterval(String value, int fps) {
    return value == null || value.isEmpty()
        ? 50
        : 1000 / Math.clamp(integer(value), 1, Math.clamp(fps, 1, 125));
  }

  private static int integer(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException invalid) {
      return 0;
    }
  }

  private boolean transmitted;
  private long lastSent;
  private int lastBytes;

  public void sent(int sequence, int bytes, boolean finalFragment, long now) {
    if (sequence <= 0 || bytes <= 0)
      throw new IllegalArgumentException("Invalid transmitted packet");
    int slot = sequence & 31;
    if (sequences[slot] != sequence) {
      sequences[slot] = sequence;
      sent[slot] = now;
      complete[slot] = false;
      sampled[slot] = false;
    }
    complete[slot] |= finalFragment;
    transmitted = true;
    lastSent = now;
    lastBytes = bytes;
  }

  public void acknowledge(int sequence, long now) {
    int slot = sequence & 31;
    if (now <= 0
        || sequence <= 0
        || sequences[slot] != sequence
        || !complete[slot]
        || sampled[slot]
        || now < sent[slot]) return;
    acknowledged[slot] = now;
    sampled[slot] = true;
  }

  public int ping() {
    long total = 0;
    int count = 0;
    for (int i = 0; i < 32; i++)
      if (sampled[i]) {
        total += acknowledged[i] - sent[i];
        count++;
      }
    return count == 0 ? 999 : (int) Math.min(999, total / count);
  }

  public long delay(int rate, int minimum, int maximum, float scale, boolean ipv6, long now) {
    if (!transmitted) return 0;
    return Math.max(
        0, interval(lastBytes, rate, minimum, maximum, scale, ipv6) - Math.max(0, now - lastSent));
  }

  /** Native-observed rate delay includes UDP/IP overhead and truncates fractional milliseconds. */
  public static int interval(
      int bytes, int rate, int minimum, int maximum, float scale, boolean ipv6) {
    if (bytes < 0 || rate <= 0 || !Float.isFinite(scale) || scale <= 0)
      throw new IllegalArgumentException("Invalid packet timing inputs");
    if (maximum > 0) rate = Math.min(rate, Math.max(1000, maximum));
    if (minimum > 0) rate = Math.max(rate, Math.max(1000, minimum));
    return (bytes + (ipv6 ? 48 : 28)) * 1000 / Math.max(1, (int) (rate * scale));
  }
}

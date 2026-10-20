package dev.bluevista.craftq3.engine.common.io;

/** Overflow-safe range checks for untrusted offsets and lengths read from game data. */
public final class Ranges {
  private Ranges() {}

  /** True when {@code [offset, offset + length)} lies within {@code [0, size)}. */
  public static boolean contains(long size, long offset, long length) {
    return offset >= 0 && length >= 0 && offset <= size && length <= size - offset;
  }

  /** Throws {@code IllegalArgumentException} naming {@code what} unless the range is contained. */
  public static void check(String what, long size, long offset, long length) {
    if (!contains(size, offset, length))
      throw new IllegalArgumentException(
          what + " range [" + offset + ", +" + length + ") exceeds " + size + " bytes");
  }

  /** {@code count * elementSize} as an int, rejecting overflow and results above {@code limit}. */
  public static int product(String what, long count, long elementSize, long limit) {
    if (count < 0 || elementSize < 0) throw new IllegalArgumentException(what + " is negative");
    if (elementSize != 0 && count > limit / elementSize)
      throw new IllegalArgumentException(what + " exceeds " + limit + " bytes");
    return Math.toIntExact(count * elementSize);
  }
}

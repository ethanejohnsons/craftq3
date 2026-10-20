package dev.bluevista.craftq3.engine.render;

import java.util.Arrays;

/**
 * A refdef area mask: bit {@code area} set means the area is hidden (a closed door separates it).
 * Immutable; the bytes are copied in.
 */
public final class AreaMask {
  /** The original refdef carries a 32-byte mask (256 areas). */
  public static final int MAX_BYTES = 32;

  public static final AreaMask NONE = new AreaMask(new byte[0]);

  private final byte[] bits;

  private AreaMask(byte[] bits) {
    this.bits = bits;
  }

  public static AreaMask of(byte[] bits) {
    if (bits.length > MAX_BYTES) throw new IllegalArgumentException("Area mask exceeds 32 bytes");
    return bits.length == 0 ? NONE : new AreaMask(bits.clone());
  }

  /** True when {@code area} is masked out; negative or unlisted areas are visible. */
  public boolean hidden(int area) {
    if (area < 0 || (area >> 3) >= bits.length) return false;
    return (bits[area >> 3] & (1 << (area & 7))) != 0;
  }

  public int size() {
    return bits.length;
  }

  public int unsigned(int index) {
    return bits[index] & 255;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AreaMask mask && Arrays.equals(bits, mask.bits);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bits);
  }

  @Override
  public String toString() {
    return "AreaMask" + Arrays.toString(bits);
  }
}

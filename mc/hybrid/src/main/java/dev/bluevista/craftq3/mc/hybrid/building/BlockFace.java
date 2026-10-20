package dev.bluevista.craftq3.mc.hybrid.building;

/**
 * A block face in Minecraft coordinates, in the order of Minecraft's {@code Direction} (so the
 * adapter maps by ordinal). {@link #axis()} is 0, 1, 2 for X, Y, Z; the face's own 2D coordinates
 * {@code (u, v)} are the remaining axes in increasing order: Y/Z for X faces, X/Z for Y faces, X/Y
 * for Z faces.
 */
public enum BlockFace {
  DOWN(1, -1),
  UP(1, 1),
  NORTH(2, -1),
  SOUTH(2, 1),
  WEST(0, -1),
  EAST(0, 1);

  private static final BlockFace[] VALUES = values();

  private final int axis;
  private final int sign;

  BlockFace(int axis, int sign) {
    this.axis = axis;
    this.sign = sign;
  }

  /** 0, 1 or 2 for the X, Y or Z axis. */
  public int axis() {
    return axis;
  }

  /** +1 for UP, SOUTH and EAST, -1 for DOWN, NORTH and WEST. */
  public int sign() {
    return sign;
  }

  /** The first in-face axis. */
  public int uAxis() {
    return axis == 0 ? 1 : 0;
  }

  /** The second in-face axis. */
  public int vAxis() {
    return axis == 2 ? 1 : 2;
  }

  public int stepX() {
    return axis == 0 ? sign : 0;
  }

  public int stepY() {
    return axis == 1 ? sign : 0;
  }

  public int stepZ() {
    return axis == 2 ? sign : 0;
  }

  public BlockFace opposite() {
    return VALUES[ordinal() ^ 1];
  }

  public static BlockFace of(int axis, int sign) {
    for (BlockFace face : VALUES) if (face.axis == axis && face.sign == sign) return face;
    throw new IllegalArgumentException("Bad face " + axis + "/" + sign);
  }

  /** The face with the given Minecraft {@code Direction} ordinal. */
  public static BlockFace ofOrdinal(int ordinal) {
    return VALUES[ordinal];
  }
}

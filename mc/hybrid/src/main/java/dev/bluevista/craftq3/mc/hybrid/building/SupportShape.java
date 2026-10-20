package dev.bluevista.craftq3.mc.hybrid.building;

/**
 * What a face must cover to support a block, in the order of Minecraft's {@code SupportType}. The
 * required volumes are Minecraft 26.2's (block-local, in sixteenths); a face supports when its
 * shape covers each volume's projection onto the face.
 *
 * <ul>
 *   <li>{@link #FULL}: the whole face.
 *   <li>{@link #CENTER}: the column {@code (7, 0, 7) .. (9, 10, 9)}.
 *   <li>{@link #RIGID}: the block minus the column {@code (2, 0, 2) .. (14, 16, 14)}, a frame.
 * </ul>
 */
public enum SupportShape {
  FULL(16, 0, 0, 0, 16, 16, 16),
  CENTER(16, 7, 0, 7, 9, 10, 9),
  RIGID(16, 0, 0, 0, 2, 16, 16, 14, 0, 0, 16, 16, 16, 2, 0, 0, 14, 16, 2, 2, 0, 14, 14, 16, 16);

  private final double[] boxes;

  /** Boxes {@code minX, minY, minZ, maxX, maxY, maxZ} in units of {@code 1 / denominator}. */
  SupportShape(int denominator, int... sixteenths) {
    boxes = new double[sixteenths.length];
    for (int i = 0; i < sixteenths.length; i++) boxes[i] = sixteenths[i] / (double) denominator;
  }

  /** The number of required rectangles on a face. */
  public int rectangles() {
    return boxes.length / 6;
  }

  /**
   * Writes required rectangle {@code index} on {@code face} as {@code {u0, v0, u1, v1}}
   * (block-local).
   */
  public void rectangle(BlockFace face, int index, double[] out) {
    int b = index * 6;
    int u = face.uAxis();
    int v = face.vAxis();
    out[0] = boxes[b + u];
    out[1] = boxes[b + v];
    out[2] = boxes[b + 3 + u];
    out[3] = boxes[b + 3 + v];
  }
}

package dev.bluevista.craftq3.mc.hybrid.terrain;

import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.collision.SurfaceFlags;
import dev.bluevista.craftq3.engine.common.log.Log;
import java.util.Arrays;
import java.util.Objects;

/**
 * One cell's description as recorded from a {@link CellSink}: up to {@link #MAX_BOXES} solid boxes
 * (block-local floats), an optional fluid and the surface properties of its solids. Reusable: call
 * {@link #reset()} before each cell. Not thread-safe; each tracing thread owns its own.
 */
public final class CellShape implements CellSink {
  /** Solid boxes kept per cell; further boxes are dropped with a one-time warning. */
  public static final int MAX_BOXES = 32;

  private static final Log LOG = Log.get(CellShape.class);

  final float[] boxes = new float[MAX_BOXES * 6];
  int count;
  int fluidContents;
  float fluidHeight;
  boolean climbable;
  int surfaceFlags;
  private double maxRise = 1;

  /** Clears the recorded cell. */
  public CellShape reset() {
    count = 0;
    fluidContents = 0;
    fluidHeight = 0;
    climbable = false;
    surfaceFlags = 0;
    return this;
  }

  /** Boxes rising more than this above the cell are clipped to it (default one block). */
  CellShape maxRise(double value) {
    maxRise = value;
    return this;
  }

  @Override
  public void solid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) return;
    if (!(Double.isFinite(minX)
        && Double.isFinite(minY)
        && Double.isFinite(minZ)
        && Double.isFinite(maxX)
        && Double.isFinite(maxY)
        && Double.isFinite(maxZ))) return;
    if (count == MAX_BOXES) {
      LOG.warnOnce(
          "cell-boxes", "A terrain cell has more than " + MAX_BOXES + " boxes; extra ignored");
      return;
    }
    int b = count * 6;
    boxes[b] = (float) clamp(minX, 0, 1);
    boxes[b + 1] = (float) clamp(minY, 0, 1 + maxRise);
    boxes[b + 2] = (float) clamp(minZ, 0, 1);
    boxes[b + 3] = (float) clamp(maxX, 0, 1);
    boxes[b + 4] = (float) clamp(maxY, 0, 1 + maxRise);
    boxes[b + 5] = (float) clamp(maxZ, 0, 1);
    count++;
  }

  @Override
  public void fluid(FluidKind kind, double height) {
    Objects.requireNonNull(kind, "kind");
    if (!(height > 0)) return;
    fluidContents = kind.contents();
    fluidHeight = (float) Math.min(height, 1);
  }

  @Override
  public void climbable() {
    climbable = true;
  }

  @Override
  public void surfaceFlags(int flags) {
    surfaceFlags |= flags;
  }

  public int boxCount() {
    return count;
  }

  /** Coordinate {@code k} (minX, minY, minZ, maxX, maxY, maxZ) of box {@code index}. */
  public float box(int index, int k) {
    Objects.checkIndex(index, count);
    return boxes[index * 6 + k];
  }

  /** The fluid's {@code CONTENTS_*} bit, or 0. */
  public int fluidContents() {
    return fluidContents;
  }

  public float fluidHeight() {
    return fluidHeight;
  }

  public boolean isClimbable() {
    return climbable;
  }

  /** The {@code SURF_*} flags of the cell's solids, including {@code SURF_LADDER} if climbable. */
  public int solidSurfaceFlags() {
    return surfaceFlags | (climbable ? SurfaceFlags.LADDER : 0);
  }

  /** True when nothing was recorded. */
  public boolean isEmpty() {
    return count == 0 && fluidContents == 0;
  }

  /** The OR of every content the cell reports. */
  public int contents() {
    return (count > 0 ? Contents.SOLID : 0) | fluidContents;
  }

  /** Copies another cell's description. */
  public CellShape copyFrom(CellShape other) {
    System.arraycopy(other.boxes, 0, boxes, 0, other.count * 6);
    count = other.count;
    fluidContents = other.fluidContents;
    fluidHeight = other.fluidHeight;
    climbable = other.climbable;
    surfaceFlags = other.surfaceFlags;
    return this;
  }

  /** Replays the description into another sink. */
  public void replay(CellSink sink) {
    for (int i = 0; i < count; i++) {
      int b = i * 6;
      sink.solid(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3], boxes[b + 4], boxes[b + 5]);
    }
    FluidKind kind = FluidKind.ofContents(fluidContents);
    if (kind != null) sink.fluid(kind, fluidHeight);
    if (climbable) sink.climbable();
    if (surfaceFlags != 0) sink.surfaceFlags(surfaceFlags);
  }

  /** True when both describe the same cell. */
  public boolean sameAs(CellShape other) {
    return count == other.count
        && fluidContents == other.fluidContents
        && Float.compare(fluidHeight, other.fluidHeight) == 0
        && climbable == other.climbable
        && surfaceFlags == other.surfaceFlags
        && Arrays.equals(boxes, 0, count * 6, other.boxes, 0, count * 6);
  }

  /** A hash consistent with {@link #sameAs}. */
  public int shapeHash() {
    int h = count;
    for (int i = 0; i < count * 6; i++) h = h * 31 + Float.floatToIntBits(boxes[i]);
    h = h * 31 + fluidContents;
    h = h * 31 + Float.floatToIntBits(fluidHeight);
    h = h * 31 + (climbable ? 1 : 0);
    return h * 31 + surfaceFlags;
  }

  private static double clamp(double value, double lo, double hi) {
    return value < lo ? lo : value > hi ? hi : value;
  }
}

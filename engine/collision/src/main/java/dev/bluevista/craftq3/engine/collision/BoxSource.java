package dev.bluevista.craftq3.engine.collision;

import java.util.Arrays;

/**
 * A fixed set of axis-aligned boxes in world coordinates, as a {@link CollisionSource}. Boxes are
 * clipped in insertion order, so equally near hits resolve to the earlier box.
 */
public final class BoxSource implements CollisionSource {
  private final float[] boxes;
  private final int[] contents;
  private final int[] surfaceFlags;

  private BoxSource(float[] boxes, int[] contents, int[] surfaceFlags) {
    this.boxes = boxes;
    this.contents = contents;
    this.surfaceFlags = surfaceFlags;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int size() {
    return contents.length;
  }

  @Override
  public void clip(TraceClipper clipper) {
    for (int i = 0; i < contents.length; i++) {
      int b = i * 6;
      if (clipper.sweptMax(0) < boxes[b] - Clip.SURFACE_CLIP_EPSILON
          || clipper.sweptMax(1) < boxes[b + 1] - Clip.SURFACE_CLIP_EPSILON
          || clipper.sweptMax(2) < boxes[b + 2] - Clip.SURFACE_CLIP_EPSILON
          || clipper.sweptMin(0) > boxes[b + 3] + Clip.SURFACE_CLIP_EPSILON
          || clipper.sweptMin(1) > boxes[b + 4] + Clip.SURFACE_CLIP_EPSILON
          || clipper.sweptMin(2) > boxes[b + 5] + Clip.SURFACE_CLIP_EPSILON) continue;
      clipper.clipBox(
          boxes[b],
          boxes[b + 1],
          boxes[b + 2],
          boxes[b + 3],
          boxes[b + 4],
          boxes[b + 5],
          contents[i],
          surfaceFlags[i]);
    }
  }

  @Override
  public int pointContents(float x, float y, float z) {
    int result = 0;
    for (int i = 0; i < contents.length; i++) {
      int b = i * 6;
      if (x >= boxes[b]
          && y >= boxes[b + 1]
          && z >= boxes[b + 2]
          && x <= boxes[b + 3]
          && y <= boxes[b + 4]
          && z <= boxes[b + 5]) result |= contents[i];
    }
    return result;
  }

  public static final class Builder {
    private float[] boxes = new float[48];
    private int[] contents = new int[8];
    private int[] surfaceFlags = new int[8];
    private int count;

    public Builder add(float[] mins, float[] maxs, int boxContents, int boxSurfaceFlags) {
      for (int axis = 0; axis < 3; axis++)
        if (!(mins[axis] <= maxs[axis]))
          throw new IllegalArgumentException("Box mins exceed maxs on axis " + axis);
      if (count == contents.length) {
        boxes = Arrays.copyOf(boxes, boxes.length * 2);
        contents = Arrays.copyOf(contents, count * 2);
        surfaceFlags = Arrays.copyOf(surfaceFlags, count * 2);
      }
      System.arraycopy(mins, 0, boxes, count * 6, 3);
      System.arraycopy(maxs, 0, boxes, count * 6 + 3, 3);
      contents[count] = boxContents;
      surfaceFlags[count] = boxSurfaceFlags;
      count++;
      return this;
    }

    public BoxSource build() {
      return new BoxSource(
          Arrays.copyOf(boxes, count * 6),
          Arrays.copyOf(contents, count),
          Arrays.copyOf(surfaceFlags, count));
    }
  }
}

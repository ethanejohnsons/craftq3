package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Swept-box traversal over live block cells; narrow phase retains ordinary Quake trace semantics.
 */
public final class GridTraceWorld implements TraceWorld {
  public record Cell(int x, int y, int z) {}

  @FunctionalInterface
  public interface Cells {
    /**
     * Absolute Q3-space shapes owned by this cell, extending at most one cell beyond its bounds.
     */
    List<? extends TraceWorld> shapes(Cell cell);
  }

  private final double size;
  private final Cells cells;

  public GridTraceWorld(double cellSize, Cells cells) {
    if (!Double.isFinite(cellSize) || cellSize < 1 || cellSize > 4096)
      throw new IllegalArgumentException("Invalid block scale");
    this.size = cellSize;
    this.cells = Objects.requireNonNull(cells);
  }

  @Override
  public TraceResult trace(TraceRequest request) {
    var visited = new HashSet<Cell>();
    TraceResult closest = TraceResult.clear(request);
    boolean start = false, all = false;
    double[] a = {request.start().x(), request.start().y(), request.start().z()},
        b = {request.end().x(), request.end().y(), request.end().z()};
    double[] lo = {request.mins().x(), request.mins().y(), request.mins().z()},
        hi = {request.maxs().x(), request.maxs().y(), request.maxs().z()};
    int[] cell = new int[3],
        end = new int[3],
        step = new int[3],
        low = new int[3],
        high = new int[3];
    double[] next = new double[3], delta = new double[3];
    long neighbors = 1;
    for (int axis = 0; axis < 3; axis++) {
      cell[axis] = index(a[axis]);
      end[axis] = index(b[axis]);
      step[axis] = b[axis] > a[axis] ? 1 : b[axis] < a[axis] ? -1 : 0;
      low[axis] = index(lo[axis]) - 1;
      high[axis] = index(hi[axis]) + 1;
      neighbors *= high[axis] - low[axis] + 1L;
      delta[axis] = step[axis] == 0 ? Double.POSITIVE_INFINITY : size / Math.abs(b[axis] - a[axis]);
      next[axis] =
          step[axis] == 0
              ? Double.POSITIVE_INFINITY
              : ((cell[axis] + (step[axis] > 0 ? 1 : 0)) * size - a[axis]) / (b[axis] - a[axis]);
    }
    if (neighbors > 4096)
      throw new IllegalArgumentException("Trace box exceeds block query budget");
    int steps = 0;
    for (; ; ) {
      if (++steps > 8192)
        throw new IllegalArgumentException("Trace exceeds block traversal budget");
      for (int z = low[2]; z <= high[2]; z++)
        for (int y = low[1]; y <= high[1]; y++)
          for (int x = low[0]; x <= high[0]; x++) {
            var key = new Cell(cell[0] + x, cell[1] + y, cell[2] + z);
            if (!visited.add(key)) continue;
            if (visited.size() > 262144)
              throw new IllegalArgumentException("Trace exceeds block candidate budget");
            var shapes = cells.shapes(key);
            if (shapes.size() > 256)
              throw new IllegalArgumentException("Block shape budget exceeded");
            for (var shape : shapes) {
              var hit = shape.trace(request);
              start |= hit.startSolid();
              all |= hit.allSolid();
              if (hit.allSolid() && !closest.allSolid() || hit.fraction() < closest.fraction())
                closest = hit;
            }
          }
      if (cell[0] == end[0] && cell[1] == end[1] && cell[2] == end[2]) break;
      double crossing = Math.min(next[0], Math.min(next[1], next[2]));
      if (crossing > 1) break;
      // Visit every tied axis together; the padded neighbor region includes edge/corner contacts.
      for (int axis = 0; axis < 3; axis++)
        if (next[axis] <= crossing) {
          cell[axis] += step[axis];
          next[axis] += delta[axis];
        }
    }
    return new TraceResult(closest.fraction(), closest.endPosition(), start, all, closest.hit());
  }

  @Override
  public int pointContents(Vec3 point, int mask, int ignoreEntity) {
    int cx = index(point.x()), cy = index(point.y()), cz = index(point.z()), contents = 0;
    for (int z = cz - 1; z <= cz + 1; z++)
      for (int y = cy - 1; y <= cy + 1; y++)
        for (int x = cx - 1; x <= cx + 1; x++) {
          var shapes = cells.shapes(new Cell(x, y, z));
          if (shapes.size() > 256)
            throw new IllegalArgumentException("Block shape budget exceeded");
          for (var shape : shapes) contents |= shape.pointContents(point, mask, ignoreEntity);
        }
    return contents;
  }

  private int index(double value) {
    double index = Math.floor(value / size);
    if (index < Integer.MIN_VALUE + 65536.0 || index > Integer.MAX_VALUE - 65536.0)
      throw new IllegalArgumentException("Block coordinate exceeds range");
    return (int) index;
  }
}

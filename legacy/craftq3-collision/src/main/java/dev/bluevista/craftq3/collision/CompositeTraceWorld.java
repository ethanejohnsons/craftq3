package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;

/** Stable nearest-hit composition, without assumptions about whether an obstacle comes from Q3. */
public final class CompositeTraceWorld implements TraceWorld {
  private final List<TraceWorld> worlds;

  public CompositeTraceWorld(List<? extends TraceWorld> worlds) {
    this.worlds = List.copyOf(worlds);
  }

  public List<TraceWorld> worlds() {
    return worlds;
  }

  @Override
  public TraceResult trace(TraceRequest request) {
    TraceResult closest = TraceResult.clear(request);
    boolean start = false, all = false;
    for (TraceWorld world : worlds) {
      TraceResult next = world.trace(request);
      start |= next.startSolid();
      all |= next.allSolid();
      if ((next.allSolid() && !closest.allSolid()) || next.fraction() < closest.fraction())
        closest = next;
    }
    return new TraceResult(closest.fraction(), closest.endPosition(), start, all, closest.hit());
  }

  @Override
  public int pointContents(Vec3 point, int mask, int ignoreEntity) {
    int contents = 0;
    for (TraceWorld world : worlds) contents |= world.pointContents(point, mask, ignoreEntity);
    return contents;
  }
}

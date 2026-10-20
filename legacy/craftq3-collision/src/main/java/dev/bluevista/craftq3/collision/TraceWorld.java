package dev.bluevista.craftq3.collision;

import dev.bluevista.craftq3.core.math.Vec3;

/** Host-independent collision boundary shared by Q3 gameplay and future Minecraft adapters. */
public interface TraceWorld {
  TraceResult trace(TraceRequest request);

  int pointContents(Vec3 point, int contentsMask, int ignoreEntity);

  default int pointContents(Vec3 point, int contentsMask) {
    return pointContents(point, contentsMask, -1);
  }

  default int pointContents(Vec3 point) {
    return pointContents(point, -1, -1);
  }
}

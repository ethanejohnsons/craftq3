package dev.bluevista.craftq3.engine.botlib.testing;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.BspTrace;
import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * Test support: a {@link BotCollision} fake written against recorded {@link TraceRequest}s, so the
 * oracle tests keep asserting the exact queries botlib issued.
 */
public interface TraceWorld extends BotCollision {
  TraceResult trace(TraceRequest request);

  /**
   * Point contents; botlib always asks for every bit and no ignored entity (mask and ignore -1).
   */
  int pointContents(Vec3f point, int contentsMask, int ignoreEntity);

  @Override
  default BspTrace trace(
      Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int passEntity, int contentMask) {
    return trace(new TraceRequest(start, end, mins, maxs, contentMask, passEntity)).toBsp();
  }

  @Override
  default int pointContents(Vec3f point) {
    return pointContents(point, -1, -1);
  }
}

package dev.bluevista.craftq3.engine.botlib;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * The two collision imports most botlib services need: the swept-box world trace and point contents
 * ({@code botlib_import_t.Trace} and {@code PointContents}). {@link BotWorld} extends it; services
 * borrow only this narrower view.
 */
public interface BotCollision {
  /** Sweeps the box {@code mins..maxs} from {@code start} to {@code end}, ignoring passEntity. */
  BspTrace trace(Vec3f start, Vec3f mins, Vec3f maxs, Vec3f end, int passEntity, int contentMask);

  /** All {@link Contents} bits at {@code point}, including linked entities. */
  int pointContents(Vec3f point);

  /** A point trace. */
  default BspTrace trace(Vec3f start, Vec3f end, int passEntity, int contentMask) {
    return trace(start, Vec3f.ZERO, Vec3f.ZERO, end, passEntity, contentMask);
  }
}

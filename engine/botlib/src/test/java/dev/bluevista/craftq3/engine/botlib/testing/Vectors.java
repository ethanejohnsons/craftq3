package dev.bluevista.craftq3.engine.botlib.testing;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/** Test support: vector arithmetic in double precision, rounded once, as the oracle fakes did. */
public final class Vectors {
  private Vectors() {}

  /** {@code start + (end - start) * fraction} computed in double, then rounded to float. */
  public static Vec3f lerp(Vec3f start, Vec3f end, double fraction) {
    return new Vec3f(
        (float) (start.x() + ((double) end.x() - start.x()) * fraction),
        (float) (start.y() + ((double) end.y() - start.y()) * fraction),
        (float) (start.z() + ((double) end.z() - start.z()) * fraction));
  }
}

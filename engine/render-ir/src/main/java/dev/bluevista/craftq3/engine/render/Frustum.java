package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;

/**
 * A conservative view volume for box rejection: near, far and four side planes from the view axes
 * and half-angle tangents, plus an optional portal clip plane. Boxes that touch the volume are
 * kept.
 */
public final class Frustum {
  private static final double EPSILON = 1e-7;
  private final double ox, oy, oz;
  private final double[] planes; // [nx, ny, nz, offset] relative to the origin
  private final Plane clip;

  private Frustum(Vec3f origin, double[] planes, Plane clip) {
    this.ox = origin.x();
    this.oy = origin.y();
    this.oz = origin.z();
    this.planes = planes;
    this.clip = clip;
  }

  /** A yaw/pitch camera with horizontal field of view and {@code width / height} aspect. */
  public static Frustum of(Camera camera, double aspect, double near, double far) {
    if (!Double.isFinite(aspect) || aspect <= 0)
      throw new IllegalArgumentException("Invalid frustum");
    double tanX = Math.tan(Math.toRadians(camera.horizontalFov()) / 2);
    return of(camera.origin(), camera.basis(), tanX, tanX / aspect, near, far, null);
  }

  /**
   * A view from its axes and the tangents of its half fields of view. {@code clip} may be null;
   * otherwise boxes entirely behind it are rejected.
   */
  public static Frustum of(
      Vec3f origin,
      ViewBasis basis,
      double tanHalfX,
      double tanHalfY,
      double near,
      double far,
      Plane clip) {
    if (!Double.isFinite(tanHalfX)
        || tanHalfX <= 0
        || !Double.isFinite(tanHalfY)
        || tanHalfY <= 0
        || !Double.isFinite(near)
        || near < 0
        || !Double.isFinite(far)
        || far <= near) throw new IllegalArgumentException("Invalid frustum");
    Vec3f f = basis.forward(), r = basis.right(), u = basis.up();
    double[] planes = {
      f.x(),
      f.y(),
      f.z(),
      -near,
      -f.x(),
      -f.y(),
      -f.z(),
      far,
      f.x() * tanHalfX + r.x(),
      f.y() * tanHalfX + r.y(),
      f.z() * tanHalfX + r.z(),
      0,
      f.x() * tanHalfX - r.x(),
      f.y() * tanHalfX - r.y(),
      f.z() * tanHalfX - r.z(),
      0,
      f.x() * tanHalfY + u.x(),
      f.y() * tanHalfY + u.y(),
      f.z() * tanHalfY + u.z(),
      0,
      f.x() * tanHalfY - u.x(),
      f.y() * tanHalfY - u.y(),
      f.z() * tanHalfY - u.z(),
      0
    };
    return new Frustum(origin, planes, clip);
  }

  public boolean intersects(Vec3f min, Vec3f max) {
    return intersects(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
  }

  /** True unless the box lies entirely outside one plane. */
  public boolean intersects(
      float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    for (int i = 0; i < planes.length; i += 4) {
      double nx = planes[i], ny = planes[i + 1], nz = planes[i + 2];
      double x = (nx >= 0 ? maxX : minX) - ox;
      double y = (ny >= 0 ? maxY : minY) - oy;
      double z = (nz >= 0 ? maxZ : minZ) - oz;
      if (nx * x + ny * y + nz * z + planes[i + 3] < -EPSILON) return false;
    }
    if (clip != null) {
      Vec3f n = clip.normal();
      float x = n.x() >= 0 ? maxX : minX,
          y = n.y() >= 0 ? maxY : minY,
          z = n.z() >= 0 ? maxZ : minZ;
      return clip.signedDistance(x, y, z) >= -EPSILON;
    }
    return true;
  }
}

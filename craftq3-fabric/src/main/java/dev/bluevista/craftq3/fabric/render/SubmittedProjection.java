package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.CgameFrame;
import org.joml.Matrix4f;

/** Full cgame camera basis and pixel viewport, expressed entirely through Blaze3D matrices. */
final class SubmittedProjection {
  private SubmittedProjection() {}

  static Matrix4f matrix(CgameFrame.Refdef ref, int width, int height, boolean zeroToOne) {
    var basis = ref.basis();
    var eye = ref.origin();
    var r = basis.right();
    var u = basis.up();
    var f = basis.forward();
    var view =
        new Matrix4f()
            .m00((float) r.x())
            .m10((float) r.y())
            .m20((float) r.z())
            .m30((float) -dot(r, eye))
            .m01((float) u.x())
            .m11((float) u.y())
            .m21((float) u.z())
            .m31((float) -dot(u, eye))
            .m02((float) -f.x())
            .m12((float) -f.y())
            .m22((float) -f.z())
            .m32((float) dot(f, eye));
    float aspect =
        (float)
            (Math.tan(Math.toRadians(ref.fovX()) * .5) / Math.tan(Math.toRadians(ref.fovY()) * .5));
    var projection =
        new Matrix4f()
            .perspective((float) Math.toRadians(ref.fovY()), aspect, 1, 131072, zeroToOne)
            .mul(view);
    return new Matrix4f()
        .translation(
            (2f * ref.x() + ref.width()) / width - 1, 1 - (2f * ref.y() + ref.height()) / height, 0)
        .scale((float) ref.width() / width, (float) ref.height() / height, 1)
        .mul(projection);
  }

  static Matrix4f depthHack(Matrix4f projection, boolean zeroToOne) {
    return depthHack(projection, zeroToOne, false);
  }

  static Matrix4f depthHack(Matrix4f projection, boolean zeroToOne, boolean reversed) {
    return new Matrix4f().m22(.3f).m32(reversed ? .7f : zeroToOne ? 0 : -.7f).mul(projection);
  }

  static boolean intersects(
      CgameFrame.Refdef ref, dev.bluevista.craftq3.assets.bsp.BspMap.Bounds bounds) {
    var basis = ref.basis();
    var f = basis.forward();
    var r = basis.right();
    var u = basis.up();
    double h = Math.tan(Math.toRadians(ref.fovX()) * .5),
        v = Math.tan(Math.toRadians(ref.fovY()) * .5);
    Vec3[] axes = {
      f,
      f.scale(-1),
      f.scale(h).add(r),
      f.scale(h).add(r.scale(-1)),
      f.scale(v).add(u),
      f.scale(v).add(u.scale(-1))
    };
    double[] offsets = {-1, 131072, 0, 0, 0, 0};
    for (int i = 0; i < axes.length; i++) {
      var n = axes[i];
      Vec3 support =
          new Vec3(
              n.x() >= 0 ? bounds.max().x() : bounds.min().x(),
              n.y() >= 0 ? bounds.max().y() : bounds.min().y(),
              n.z() >= 0 ? bounds.max().z() : bounds.min().z());
      if (dot(n, support.add(ref.origin().scale(-1))) + offsets[i] < -1e-7) return false;
    }
    return true;
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }
}

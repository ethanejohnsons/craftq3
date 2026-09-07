package dev.bluevista.craftq3.fabric.render;

import dev.bluevista.craftq3.platform.CoordinateTransform;
import dev.bluevista.craftq3.render.CgameFrame;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Explicit Quake-to-Minecraft camera mapping and world/HUD submission separation. */
final class BridgeProjection {
  private BridgeProjection() {}

  static Matrix4f world(
      Matrix4fc projection, Matrix4fc rotation, Vec3 camera, CoordinateTransform transform) {
    var anchor = transform.minecraftOrigin();
    var mapping =
        new Matrix4f()
            .translation(
                (float) (anchor.x() - camera.x),
                (float) (anchor.y() - camera.y),
                (float) (anchor.z() - camera.z))
            .rotateX((float) -Math.PI / 2)
            .scale((float) (1 / transform.quakeUnitsPerBlock()));
    return new Matrix4f(projection).mul(rotation).mul(mapping);
  }

  static Matrix4f rotation(CgameFrame.Refdef ref, CoordinateTransform transform) {
    var basis = ref.basis();
    var r = transform.directionToMinecraft(basis.right()).scale(transform.quakeUnitsPerBlock());
    var u = transform.directionToMinecraft(basis.up()).scale(transform.quakeUnitsPerBlock());
    var f = transform.directionToMinecraft(basis.forward()).scale(transform.quakeUnitsPerBlock());
    return new Matrix4f()
        .m00((float) r.x())
        .m10((float) r.y())
        .m20((float) r.z())
        .m01((float) u.x())
        .m11((float) u.y())
        .m21((float) u.z())
        .m02((float) -f.x())
        .m12((float) -f.y())
        .m22((float) -f.z());
  }

  static CgameFrame partition(CgameFrame frame, boolean world) {
    return new CgameFrame(
        frame.commands().stream()
            .filter(
                command ->
                    (command instanceof CgameFrame.View view && view.refdef().worldModel())
                        == world)
            .toList(),
        frame.assets(),
        frame.timeMillis());
  }
}

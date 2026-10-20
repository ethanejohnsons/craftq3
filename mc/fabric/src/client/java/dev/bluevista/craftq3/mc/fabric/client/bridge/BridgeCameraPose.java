package dev.bluevista.craftq3.mc.fabric.client.bridge;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.render.frame.CgameFrame;
import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The cgame's view as Minecraft's camera: position, orientation (with roll), the forward, up and
 * left vectors Minecraft keeps, its yaw/pitch, and the vertical field of view. Built from the
 * frame's world {@link CgameFrame.Refdef}; {@code BridgeCameraMixin} applies it.
 */
public record BridgeCameraPose(
    Vec3 position,
    Quaternionf rotation,
    Vector3f forwards,
    Vector3f up,
    Vector3f left,
    float yRot,
    float xRot,
    float fovY) {

  /** The pose of {@code refdef} (its axes are forward, left, up in Quake coordinates). */
  public static BridgeCameraPose of(CgameFrame.Refdef refdef, CoordinateTransform transform) {
    Vec3f o = refdef.origin();
    double[] p = new double[3];
    transform.toMinecraft(o.x(), o.y(), o.z(), p);
    Vector3f forward = minecraft(refdef.axisX());
    Vector3f left = minecraft(refdef.axisY());
    Vector3f up = minecraft(refdef.axisZ());
    // Minecraft's camera looks down local -Z with +Y up and -X left: the rotation's columns are
    // right (= -left), up and -forward.
    Matrix3f basis =
        new Matrix3f(
            -left.x, -left.y, -left.z, up.x, up.y, up.z, -forward.x, -forward.y, -forward.z);
    Quaternionf rotation = new Quaternionf().setFromNormalized(basis);
    float yRot = (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
    float xRot = (float) Math.toDegrees(Math.asin(Math.clamp(-forward.y, -1f, 1f)));
    return new BridgeCameraPose(
        new Vec3(p[0], p[1], p[2]), rotation, forward, up, left, yRot, xRot, refdef.fovY());
  }

  private static Vector3f minecraft(Vec3f quake) {
    double[] n = new double[3];
    CoordinateTransform.normalToMinecraft(quake.x(), quake.y(), quake.z(), n);
    return new Vector3f((float) n[0], (float) n[1], (float) n[2]);
  }
}

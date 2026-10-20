package dev.bluevista.craftq3.mc.fabric.client.render;

import dev.bluevista.craftq3.mc.hybrid.CoordinateTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Quake III drawn inside Minecraft's level rather than instead of it (the bridge's entities, weapon
 * and HUD; a building region's BSP). Driven by {@code GameRendererMixin}: {@link #beforeCamera}
 * runs at the start of the frame, before Minecraft positions its camera, and {@link #renderInLevel}
 * after the level has been drawn and before the hand pass clears the depth buffer, so Quake
 * geometry and Minecraft blocks occlude each other.
 */
public interface Q3LevelOverlay {
  /** Start of the frame (render thread): run the frame's simulation, settle the camera pose. */
  default void beforeCamera(Minecraft minecraft) {}

  /**
   * Draws into the main target with Minecraft's camera: {@code projection} (this frame's, with view
   * bobbing), {@code viewRotation} and the camera position.
   */
  void renderInLevel(
      Minecraft minecraft, Matrix4fc projection, Matrix4fc viewRotation, Vec3 camera);

  /**
   * Quake world coordinates (units, Z up, placed by {@code transform}) to Minecraft clip space for
   * a camera at {@code camera}: {@code projection * viewRotation * translate(origin - camera) *
   * axes}, where the axes map Quake (x, y, z) to Minecraft (x, z, -y) and scale units to blocks.
   */
  static Matrix4f quakeToClip(
      Matrix4fc projection, Matrix4fc viewRotation, Vec3 camera, CoordinateTransform transform) {
    float s = (float) (1 / transform.unitsPerBlock());
    // Column-major: column 0 is where Quake X goes, column 1 Quake Y, column 2 Quake Z.
    Matrix4f axes =
        new Matrix4f(
            s,
            0,
            0,
            0,
            0,
            0,
            -s,
            0,
            0,
            s,
            0,
            0,
            (float) (transform.originX() - camera.x),
            (float) (transform.originY() - camera.y),
            (float) (transform.originZ() - camera.z),
            1);
    return new Matrix4f(projection).mul(viewRotation).mul(axes);
  }
}

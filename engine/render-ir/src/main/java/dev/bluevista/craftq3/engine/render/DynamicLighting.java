package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.render.material.VertexLighting;
import java.util.List;

/** Point-light contribution: Lambert response with a quadratic falloff to each light's radius. */
public final class DynamicLighting {
  private DynamicLighting() {}

  /**
   * Summed RGB of {@code lights} at a point with the given normal, written to {@code rgb[0..2]}.
   */
  public static void sample(
      List<DynamicLight> lights,
      float px,
      float py,
      float pz,
      float nx,
      float ny,
      float nz,
      float[] rgb) {
    rgb[0] = rgb[1] = rgb[2] = 0;
    if (lights.isEmpty()) return;
    double normalLength = Math.hypot(Math.hypot(nx, ny), nz);
    if (!Double.isFinite(normalLength) || normalLength < 1e-12) return;
    double red = 0, green = 0, blue = 0;
    for (int i = 0; i < lights.size(); i++) {
      DynamicLight light = lights.get(i);
      double x = (double) light.origin().x() - px;
      double y = (double) light.origin().y() - py;
      double z = (double) light.origin().z() - pz;
      double distance = Math.hypot(Math.hypot(x, y), z);
      if (!Double.isFinite(distance) || distance >= light.radius()) continue;
      double lambert =
          distance < 1e-12
              ? 1
              : Math.max(0, (x * nx + y * ny + z * nz) / (distance * normalLength));
      double ratio = distance / light.radius();
      double strength = lambert * (1 - ratio * ratio);
      red += light.color().x() * strength;
      green += light.color().y() * strength;
      blue += light.color().z() * strength;
    }
    rgb[0] = (float) red;
    rgb[1] = (float) green;
    rgb[2] = (float) blue;
  }

  public static Vec3f sample(List<DynamicLight> lights, Vec3f position, Vec3f normal) {
    float[] rgb = new float[3];
    sample(
        lights, position.x(), position.y(), position.z(), normal.x(), normal.y(), normal.z(), rgb);
    return Vec3f.of(rgb);
  }

  /**
   * Per-vertex lighting that adds {@code lights} to the draw's ambient term, leaving the directed
   * light and direction unchanged (the legacy entity lighting model).
   */
  public static VertexLighting addedToAmbient(List<DynamicLight> lights) {
    float[] rgb = new float[3];
    return (px, py, pz, nx, ny, nz, out) -> {
      sample(lights, px, py, pz, nx, ny, nz, rgb);
      out[0] += rgb[0];
      out[1] += rgb[1];
      out[2] += rgb[2];
    };
  }
}

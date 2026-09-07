package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;

/** Bounded point-light contribution for viewer diagnostics and future runtime light sources. */
public final class DynamicLighting {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  private DynamicLighting() {}

  /** Returns summed additive RGB using Lambert response and quadratic falloff to each radius. */
  public static Vec3 sample(List<RenderScene.DynamicLight> lights, BspMap.Vertex vertex) {
    if (lights.isEmpty()) return ZERO;
    Vec3 normal = vertex.normal();
    double normalLength = Math.hypot(Math.hypot(normal.x(), normal.y()), normal.z());
    if (!Double.isFinite(normalLength) || normalLength < 1e-12) return ZERO;
    double red = 0, green = 0, blue = 0;
    for (var light : lights) {
      double x = light.origin().x() - vertex.position().x();
      double y = light.origin().y() - vertex.position().y();
      double z = light.origin().z() - vertex.position().z();
      double distance = Math.hypot(Math.hypot(x, y), z);
      if (!Double.isFinite(distance) || distance >= light.radius()) continue;
      double lambert =
          distance < 1e-12
              ? 1
              : Math.max(
                  0,
                  (x * normal.x() + y * normal.y() + z * normal.z()) / (distance * normalLength));
      double ratio = distance / light.radius();
      double strength = lambert * (1 - ratio * ratio);
      red += light.color().x() * strength;
      green += light.color().y() * strength;
      blue += light.color().z() * strength;
    }
    return new Vec3(red, green, blue);
  }
}

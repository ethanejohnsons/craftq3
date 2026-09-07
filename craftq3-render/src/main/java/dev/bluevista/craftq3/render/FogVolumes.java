package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Convex BSP fog volumes evaluated along an eye-to-surface segment in Q3 units. */
public final class FogVolumes {
  private static final int MAX_VOLUMES = 256;
  private static final int MAX_PLANES = 32_768;
  private static final Sample CLEAR = new Sample(new Vec3(0, 0, 0), 0);
  private final List<Volume> volumes;

  private FogVolumes(List<Volume> volumes) {
    this.volumes = List.copyOf(volumes);
  }

  public record Volume(
      int effect, int brush, List<BspMap.Plane> planes, Vec3 color, float depthForOpaque) {
    public Volume {
      planes = List.copyOf(planes);
    }
  }

  /** RGB is unpremultiplied and opacity is ready for source-alpha blending. */
  public record Sample(Vec3 color, float opacity) {
    public Sample {
      if (color == null || !Float.isFinite(opacity) || opacity < 0 || opacity > 1)
        throw new IllegalArgumentException("Invalid fog sample");
    }
  }

  public static FogVolumes from(BspMap map, Map<String, ShaderDefinition> shaders) {
    if (map == null) return new FogVolumes(List.of());
    Map<String, ShaderDefinition> canonical = new HashMap<>();
    for (ShaderDefinition shader : shaders.values()) canonical.put(shader.name(), shader);
    List<Volume> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int totalPlanes = 0;
    for (int i = 0; i < map.effects().size(); i++) {
      BspMap.Effect effect = map.effects().get(i);
      if (effect.brush() < 0 || effect.brush() >= map.brushes().size()) continue;
      String name;
      try {
        name = ShaderDefinition.canonicalName(effect.name());
      } catch (IllegalArgumentException invalidName) {
        continue;
      }
      ShaderDefinition shader = canonical.get(name);
      if (shader == null || shader.fog().isEmpty()) continue;
      ShaderDefinition.Fog fog = shader.fog().orElseThrow();
      if (fog.color() == null || !Float.isFinite(fog.depthForOpaque())) continue;
      if (!seen.add(effect.brush() + ":" + name)) continue;
      BspMap.Brush brush = map.brushes().get(effect.brush());
      if (brush.sideCount() < 4
          || brush.firstSide() < 0
          || (long) brush.firstSide() + brush.sideCount() > map.brushSides().size()) continue;
      if (result.size() >= MAX_VOLUMES || (long) totalPlanes + brush.sideCount() > MAX_PLANES)
        throw new IllegalArgumentException("Fog volume budget exceeded");
      List<BspMap.Plane> planes = new ArrayList<>();
      boolean valid = true;
      for (int j = 0; j < brush.sideCount(); j++) {
        int planeIndex = map.brushSides().get(brush.firstSide() + j).plane();
        if (planeIndex < 0 || planeIndex >= map.planes().size()) {
          valid = false;
          break;
        }
        BspMap.Plane plane = map.planes().get(planeIndex);
        double length = Math.sqrt(dot(plane.normal(), plane.normal()));
        if (!Double.isFinite(length) || length < 1e-12 || !Float.isFinite(plane.distance())) {
          valid = false;
          break;
        }
        float normalizedDistance = (float) (plane.distance() / length);
        if (!Float.isFinite(normalizedDistance)) {
          valid = false;
          break;
        }
        planes.add(new BspMap.Plane(plane.normal().scale(1 / length), normalizedDistance));
      }
      if (!valid) continue;
      totalPlanes += planes.size();
      result.add(
          new Volume(
              i,
              effect.brush(),
              planes,
              new Vec3(clamp(fog.color().x()), clamp(fog.color().y()), clamp(fog.color().z())),
              Math.max(1, fog.depthForOpaque())));
    }
    return new FogVolumes(result);
  }

  public int count() {
    return volumes.size();
  }

  public List<Volume> volumes() {
    return volumes;
  }

  public Sample sample(Vec3 eye, Vec3 point) {
    if (volumes.isEmpty()) return CLEAR;
    double dx = point.x() - eye.x(), dy = point.y() - eye.y(), dz = point.z() - eye.z();
    double length = Math.hypot(Math.hypot(dx, dy), dz);
    if (!Double.isFinite(length) || length < 1e-12) return CLEAR;
    List<Interval> intersections = new ArrayList<>();
    for (Volume volume : volumes) {
      double begin = 0, end = 1;
      boolean hit = true;
      for (BspMap.Plane plane : volume.planes()) {
        Vec3 n = plane.normal();
        double distance = dot(eye, n) - plane.distance();
        double direction = dx * n.x() + dy * n.y() + dz * n.z();
        if (!Double.isFinite(distance) || !Double.isFinite(direction)) {
          hit = false;
          break;
        }
        if (Math.abs(direction) < 1e-12) {
          if (distance > 1e-7) {
            hit = false;
            break;
          }
          continue;
        }
        double crossing = -distance / direction;
        if (direction > 0) end = Math.min(end, crossing);
        else begin = Math.max(begin, crossing);
        if (begin >= end) {
          hit = false;
          break;
        }
      }
      if (hit && end > begin) intersections.add(new Interval(begin, end, volume));
    }
    if (intersections.isEmpty()) return CLEAR;
    intersections.sort(Comparator.comparingDouble(Interval::begin).reversed());
    double red = 0, green = 0, blue = 0, opacity = 0;
    for (Interval interval : intersections) {
      Volume volume = interval.volume();
      // Q3's fog ramp has a square-root density response and reaches full opacity at the
      // shader's depthForOpaque. We evaluate the continuous ramp, without texture-table rounding.
      double alpha =
          Math.sqrt(clamp((interval.end() - interval.begin()) * length / volume.depthForOpaque()));
      red = volume.color().x() * alpha + red * (1 - alpha);
      green = volume.color().y() * alpha + green * (1 - alpha);
      blue = volume.color().z() * alpha + blue * (1 - alpha);
      opacity = alpha + opacity * (1 - alpha);
    }
    if (opacity <= 0) return CLEAR;
    return new Sample(
        new Vec3(red / opacity, green / opacity, blue / opacity), (float) clamp(opacity));
  }

  private static double clamp(double value) {
    return Math.clamp(value, 0, 1);
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private record Interval(double begin, double end, Volume volume) {}
}

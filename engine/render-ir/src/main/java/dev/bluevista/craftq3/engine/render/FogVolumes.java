package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.render.material.VertexFog;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Convex BSP fog volumes, evaluated along the eye-to-point segment. Each volume keeps its BSP
 * effect index, so a surface's {@code fog} number (its face's effect) selects its volume; the
 * packed plane arrays let a GPU backend evaluate the same ray clipping per vertex.
 */
public final class FogVolumes {
  private static final int MAX_VOLUMES = 256;
  private static final int MAX_PLANES = 32_768;
  private static final int MIN_BRUSH_SIDES = 4;
  private static final Sample CLEAR = new Sample(Vec3f.ZERO, 0);
  private final List<Volume> volumes;
  private final int[] volumeForEffect;

  private FogVolumes(List<Volume> volumes, int effects) {
    this.volumes = List.copyOf(volumes);
    this.volumeForEffect = new int[effects];
    java.util.Arrays.fill(volumeForEffect, -1);
    for (int i = 0; i < this.volumes.size(); i++) {
      int effect = this.volumes.get(i).effect();
      if (volumeForEffect[effect] < 0) volumeForEffect[effect] = i;
    }
  }

  /**
   * A fog brush: unit plane normals and distances as {@code [nx, ny, nz, d]*}, colour and opaque
   * depth.
   */
  public record Volume(int effect, int brush, float[] planes, Vec3f color, float depthForOpaque) {
    public int planeCount() {
      return planes.length / 4;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Volume v
          && effect == v.effect
          && brush == v.brush
          && java.util.Arrays.equals(planes, v.planes)
          && color.equals(v.color)
          && depthForOpaque == v.depthForOpaque;
    }

    @Override
    public int hashCode() {
      return effect * 31 + java.util.Arrays.hashCode(planes);
    }

    @Override
    public String toString() {
      return "Volume[effect=" + effect + ", brush=" + brush + ", planes=" + planeCount() + "]";
    }
  }

  /** RGB is unpremultiplied and opacity is ready for source-alpha blending. */
  public record Sample(Vec3f color, float opacity) {
    public Sample {
      if (color == null || !Float.isFinite(opacity) || opacity < 0 || opacity > 1)
        throw new IllegalArgumentException("Invalid fog sample");
    }
  }

  public static FogVolumes none() {
    return new FogVolumes(List.of(), 0);
  }

  /**
   * The fog volumes of {@code map}: effects whose shader (looked up by name) has {@code fogparms}.
   * Effects with an invalid brush or shader are skipped.
   */
  public static FogVolumes from(BspMap map, Function<String, Optional<ShaderDefinition>> shaders) {
    List<Volume> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int totalPlanes = 0;
    BspMap.Brushes brushes = map.brushes();
    BspMap.BrushSides sides = map.brushSides();
    BspMap.Planes planes = map.planes();
    for (int i = 0; i < map.effects().size(); i++) {
      BspMap.Effect effect = map.effects().get(i);
      int brush = effect.brush();
      if (brush < 0 || brush >= brushes.count()) continue;
      Optional<ShaderDefinition> shader = shaders.apply(effect.name());
      if (shader.isEmpty() || shader.get().fog().isEmpty()) continue;
      ShaderDefinition.Fog fog = shader.get().fog().orElseThrow();
      if (fog.color() == null || !Float.isFinite(fog.depthForOpaque())) continue;
      if (!seen.add(brush + ":" + shader.get().name())) continue;
      int first = brushes.firstSide(brush), count = brushes.sideCount(brush);
      if (count < MIN_BRUSH_SIDES || first < 0 || (long) first + count > sides.count()) continue;
      if (result.size() >= MAX_VOLUMES || (long) totalPlanes + count > MAX_PLANES)
        throw new IllegalArgumentException("Fog volume budget exceeded");
      float[] packed = new float[count * 4];
      boolean valid = true;
      for (int j = 0; j < count && valid; j++) {
        int plane = sides.plane(first + j);
        if (plane < 0 || plane >= planes.count()) {
          valid = false;
          break;
        }
        Vec3f normal = planes.normal(plane);
        double length = Math.sqrt(normal.lengthSquared());
        float distance = (float) (planes.distance(plane) / length);
        if (!Double.isFinite(length) || length < 1e-12 || !Float.isFinite(distance)) {
          valid = false;
          break;
        }
        packed[j * 4] = (float) (normal.x() / length);
        packed[j * 4 + 1] = (float) (normal.y() / length);
        packed[j * 4 + 2] = (float) (normal.z() / length);
        packed[j * 4 + 3] = distance;
      }
      if (!valid) continue;
      totalPlanes += count;
      result.add(
          new Volume(
              i,
              brush,
              packed,
              new Vec3f(clamp(fog.color().x()), clamp(fog.color().y()), clamp(fog.color().z())),
              Math.max(1, fog.depthForOpaque())));
    }
    return new FogVolumes(result, map.effects().size());
  }

  public int count() {
    return volumes.size();
  }

  public List<Volume> volumes() {
    return volumes;
  }

  /** The volume of BSP effect {@code effect}, or -1. */
  public int volumeForEffect(int effect) {
    return effect >= 0 && effect < volumeForEffect.length ? volumeForEffect[effect] : -1;
  }

  /** The effect index of the first volume containing the box centre, or -1 (fogs entities). */
  public int volumeContaining(float[] bounds, int offset) {
    float cx = (bounds[offset] + bounds[offset + 3]) / 2;
    float cy = (bounds[offset + 1] + bounds[offset + 4]) / 2;
    float cz = (bounds[offset + 2] + bounds[offset + 5]) / 2;
    for (int v = 0; v < volumes.size(); v++) {
      float[] p = volumes.get(v).planes();
      boolean inside = true;
      for (int i = 0; i < p.length && inside; i += 4)
        inside = p[i] * cx + p[i + 1] * cy + p[i + 2] * cz - p[i + 3] <= 0;
      if (inside) return volumes.get(v).effect();
    }
    return -1;
  }

  public Sample sample(Vec3f eye, Vec3f point) {
    float[] out = new float[4];
    sample(eye.x(), eye.y(), eye.z(), point.x(), point.y(), point.z(), out, newScratch());
    if (out[3] <= 0) return CLEAR;
    return new Sample(new Vec3f(out[0], out[1], out[2]), out[3]);
  }

  /**
   * Composites every volume the segment crosses, nearest last: writes unpremultiplied RGB and
   * opacity to {@code out[0..3]}. Q3's fog ramp has a square-root density response reaching full
   * opacity at the shader's opaque depth; the ramp is evaluated continuously. {@code scratch} comes
   * from {@link #newScratch()} and is reused across calls.
   */
  public void sample(
      double ex,
      double ey,
      double ez,
      double px,
      double py,
      double pz,
      float[] out,
      double[] scratch) {
    out[0] = out[1] = out[2] = out[3] = 0;
    if (volumes.isEmpty()) return;
    double dx = px - ex, dy = py - ey, dz = pz - ez;
    double length = Math.hypot(Math.hypot(dx, dy), dz);
    if (!Double.isFinite(length) || length < 1e-12) return;
    // Intervals are composited farthest first; with few volumes a selection pass avoids sorting.
    int count = volumes.size();
    for (int v = 0; v < count; v++) {
      float[] planes = volumes.get(v).planes();
      double begin = 0, end = 1;
      boolean hit = true;
      for (int i = 0; i < planes.length; i += 4) {
        double nx = planes[i], ny = planes[i + 1], nz = planes[i + 2];
        double distance = ex * nx + ey * ny + ez * nz - planes[i + 3];
        double direction = dx * nx + dy * ny + dz * nz;
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
      scratch[v * 3] = hit && end > begin ? 1 : 0;
      scratch[v * 3 + 1] = begin;
      scratch[v * 3 + 2] = end;
    }
    double red = 0, green = 0, blue = 0, opacity = 0;
    double last = Double.POSITIVE_INFINITY;
    for (int pass = 0; pass < count; pass++) {
      int pick = -1;
      for (int v = 0; v < count; v++)
        if (scratch[v * 3] != 0
            && scratch[v * 3 + 1] <= last
            && (pick < 0 || scratch[v * 3 + 1] > scratch[pick * 3 + 1])) pick = v;
      if (pick < 0) break;
      scratch[pick * 3] = 0;
      last = scratch[pick * 3 + 1];
      Volume volume = volumes.get(pick);
      double alpha =
          Math.sqrt(
              clamp(
                  (scratch[pick * 3 + 2] - scratch[pick * 3 + 1])
                      * length
                      / volume.depthForOpaque()));
      red = volume.color().x() * alpha + red * (1 - alpha);
      green = volume.color().y() * alpha + green * (1 - alpha);
      blue = volume.color().z() * alpha + blue * (1 - alpha);
      opacity = alpha + opacity * (1 - alpha);
    }
    if (opacity <= 0) return;
    out[0] = (float) (red / opacity);
    out[1] = (float) (green / opacity);
    out[2] = (float) (blue / opacity);
    out[3] = (float) clamp(opacity);
  }

  /** Per-vertex fog colour seen from {@code eye}, for {@code rgbGen fog} on the CPU. */
  public VertexFog colorFrom(Vec3f eye) {
    float[] sample = new float[4];
    double[] scratch = newScratch();
    return (px, py, pz, rgb) -> {
      sample(eye.x(), eye.y(), eye.z(), px, py, pz, sample, scratch);
      rgb[0] = sample[0];
      rgb[1] = sample[1];
      rgb[2] = sample[2];
    };
  }

  /**
   * Scratch storage for {@link #sample(double, double, double, double, double, double, float[],
   * double[])}.
   */
  public double[] newScratch() {
    return new double[Math.max(1, volumes.size()) * 3];
  }

  private static double clamp(double value) {
    return Math.clamp(value, 0, 1);
  }

  private static float clamp(float value) {
    return Math.clamp(value, 0, 1);
  }
}

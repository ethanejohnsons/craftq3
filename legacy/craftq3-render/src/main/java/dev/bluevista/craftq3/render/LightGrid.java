package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Objects;

/** Static Q3 light-grid sampling for material lightingDiffuse and future model lighting. */
public final class LightGrid {
  private static final Vec3 DEFAULT_SIZE = new Vec3(64, 64, 128);
  private static final Sample FALLBACK =
      new Sample(new Vec3(1, 1, 1), new Vec3(0, 0, 0), new Vec3(0, 0, 1));
  private final Vec3 origin;
  private final Vec3 cellSize;
  private final Dimensions dimensions;
  private final List<BspMap.LightVolume> samples;

  public LightGrid(BspMap map) {
    cellSize = gridSize(map);
    if (map == null || map.models().isEmpty()) {
      origin = new Vec3(0, 0, 0);
      dimensions = new Dimensions(0, 0, 0);
      samples = List.of();
      return;
    }
    BspMap.Bounds bounds = map.models().getFirst().bounds();
    origin =
        new Vec3(
            Math.ceil(bounds.min().x() / cellSize.x()) * cellSize.x(),
            Math.ceil(bounds.min().y() / cellSize.y()) * cellSize.y(),
            Math.ceil(bounds.min().z() / cellSize.z()) * cellSize.z());
    int x = axisCount(origin.x(), bounds.max().x(), cellSize.x());
    int y = axisCount(origin.y(), bounds.max().y(), cellSize.y());
    int z = axisCount(origin.z(), bounds.max().z(), cellSize.z());
    long total = (long) x * y;
    boolean valid =
        total > 0
            && z > 0
            && total <= Integer.MAX_VALUE / z
            && total * z == map.lightVolumes().size();
    dimensions = new Dimensions(x, y, z);
    samples = valid ? map.lightVolumes() : List.of();
  }

  public record Dimensions(int x, int y, int z) {}

  /** Light colors are normalized 0..1 values; direction points toward the incoming light. */
  public record Sample(Vec3 ambient, Vec3 directed, Vec3 direction) {
    public Sample {
      Objects.requireNonNull(ambient, "ambient");
      Objects.requireNonNull(directed, "directed");
      Objects.requireNonNull(direction, "direction");
    }
  }

  public boolean available() {
    return !samples.isEmpty();
  }

  public Vec3 origin() {
    return origin;
  }

  public Vec3 cellSize() {
    return cellSize;
  }

  public Dimensions dimensions() {
    return dimensions;
  }

  public Sample sample(Vec3 point) {
    return sampleIfPresent(point).orElse(FALLBACK);
  }

  /** Distinguishes missing/solid grid cells from the renderer's legacy full-bright fallback. */
  public java.util.Optional<Sample> sampleIfPresent(Vec3 point) {
    if (!available()) return java.util.Optional.empty();
    double x = coordinate(point.x(), origin.x(), cellSize.x(), dimensions.x());
    double y = coordinate(point.y(), origin.y(), cellSize.y(), dimensions.y());
    double z = coordinate(point.z(), origin.z(), cellSize.z(), dimensions.z());
    int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
    double fx = x - ix, fy = y - iy, fz = z - iz;
    double ar = 0, ag = 0, ab = 0, dr = 0, dg = 0, db = 0;
    double nx = 0, ny = 0, nz = 0, weightSum = 0;
    for (int corner = 0; corner < 8; corner++) {
      int sx = ix + (corner & 1), sy = iy + ((corner >>> 1) & 1), sz = iz + ((corner >>> 2) & 1);
      if (sx >= dimensions.x() || sy >= dimensions.y() || sz >= dimensions.z()) continue;
      double weight =
          ((corner & 1) == 0 ? 1 - fx : fx)
              * ((corner & 2) == 0 ? 1 - fy : fy)
              * ((corner & 4) == 0 ? 1 - fz : fz);
      if (weight <= 0) continue;
      var cell = samples.get(sx + dimensions.x() * (sy + dimensions.y() * sz));
      if (cell.ambientRgb() == 0) continue;
      Vec3 ambient = restore(cell.ambientRgb()), directed = restore(cell.directionalRgb());
      Vec3 direction = decodeDirection(cell.latitude(), cell.longitude());
      ar += ambient.x() * weight;
      ag += ambient.y() * weight;
      ab += ambient.z() * weight;
      dr += directed.x() * weight;
      dg += directed.y() * weight;
      db += directed.z() * weight;
      nx += direction.x() * weight;
      ny += direction.y() * weight;
      nz += direction.z() * weight;
      weightSum += weight;
    }
    if (weightSum <= 1e-12) return java.util.Optional.empty();
    double normalLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
    Vec3 direction =
        normalLength > 1e-12
            ? new Vec3(nx / normalLength, ny / normalLength, nz / normalLength)
            : new Vec3(0, 0, 1);
    return java.util.Optional.of(
        new Sample(
            new Vec3(ar / weightSum, ag / weightSum, ab / weightSum),
            new Vec3(dr / weightSum, dg / weightSum, db / weightSum),
            direction));
  }

  /** BSP light-volume byte 6 is the polar angle; byte 7 is the azimuth, each in 256 steps. */
  public static Vec3 decodeDirection(int polar, int azimuth) {
    double inclination = (polar & 255) * (2 * Math.PI / 256);
    double bearing = (azimuth & 255) * (2 * Math.PI / 256);
    double radial = Math.sin(inclination);
    return new Vec3(Math.cos(bearing) * radial, Math.sin(bearing) * radial, Math.cos(inclination));
  }

  private static Vec3 restore(int rgb) {
    double red = ((rgb >>> 16) & 255) * (4.0 / 255);
    double green = ((rgb >>> 8) & 255) * (4.0 / 255);
    double blue = (rgb & 255) * (4.0 / 255);
    double scale = Math.max(1, Math.max(red, Math.max(green, blue)));
    return new Vec3(red / scale, green / scale, blue / scale);
  }

  private static double coordinate(double position, double origin, double size, int count) {
    return Math.clamp((position - origin) / size, 0, count - 1);
  }

  private static int axisCount(double origin, double maximum, double step) {
    double count = Math.floor(maximum / step) - Math.rint(origin / step) + 1;
    return !Double.isFinite(count) || count < 1 || count > Integer.MAX_VALUE ? 0 : (int) count;
  }

  private static Vec3 gridSize(BspMap map) {
    if (map == null) return DEFAULT_SIZE;
    for (var entity : map.entities()) {
      if (!entity.getOrDefault("classname", "").equals("worldspawn")) continue;
      String[] values = entity.getOrDefault("gridsize", "").trim().split("\\s+");
      if (values.length != 3) return DEFAULT_SIZE;
      try {
        Vec3 value =
            new Vec3(
                Double.parseDouble(values[0]),
                Double.parseDouble(values[1]),
                Double.parseDouble(values[2]));
        if (value.x() >= 1
            && value.y() >= 1
            && value.z() >= 1
            && value.x() <= 65536
            && value.y() <= 65536
            && value.z() <= 65536) return value;
      } catch (IllegalArgumentException invalid) {
        /* Malformed optional metadata uses defaults. */
      }
      return DEFAULT_SIZE;
    }
    return DEFAULT_SIZE;
  }
}

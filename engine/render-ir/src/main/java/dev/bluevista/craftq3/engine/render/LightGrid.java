package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.render.material.VertexLighting;
import java.util.Objects;
import java.util.Optional;

/**
 * The BSP light grid: ambient and directed light with a direction at points spaced {@link
 * #cellSize()} apart over the world bounds. Samples are decoded once into packed float arrays
 * ({@link #packed()}) that a GPU backend can upload as a 3D texture; brightness has the map's
 * overbright headroom restored with hue preservation.
 */
public final class LightGrid {
  private static final Vec3f DEFAULT_SIZE = new Vec3f(64, 64, 128);
  private static final float MAX_CELL = 65536;

  /** Full-bright fallback used when the grid is absent or the point lies in solid cells. */
  public static final Sample FALLBACK =
      new Sample(new Vec3f(1, 1, 1), new Vec3f(0, 0, 0), new Vec3f(0, 0, 1));

  /** Floats per packed cell: ambient RGB, directed RGB, direction XYZ, valid flag. */
  public static final int CELL_FLOATS = 10;

  private final Vec3f origin;
  private final Vec3f cellSize;
  private final Dimensions dimensions;
  private final float[] cells;

  public record Dimensions(int x, int y, int z) {}

  /** Light colours are normalised 0..1; direction points toward the incoming light. */
  public record Sample(Vec3f ambient, Vec3f directed, Vec3f direction) {
    public Sample {
      Objects.requireNonNull(ambient, "ambient");
      Objects.requireNonNull(directed, "directed");
      Objects.requireNonNull(direction, "direction");
    }
  }

  /** An empty grid; every sample is {@link #FALLBACK}. */
  public static LightGrid empty() {
    return new LightGrid(Vec3f.ZERO, DEFAULT_SIZE, new Dimensions(0, 0, 0), new float[0]);
  }

  private LightGrid(Vec3f origin, Vec3f cellSize, Dimensions dimensions, float[] cells) {
    this.origin = origin;
    this.cellSize = cellSize;
    this.dimensions = dimensions;
    this.cells = cells;
  }

  /**
   * Decodes the grid of {@code map}; a grid whose size disagrees with the world bounds is empty.
   */
  public static LightGrid of(BspMap map) {
    Vec3f size = gridSize(map);
    if (map.models().isEmpty())
      return new LightGrid(Vec3f.ZERO, size, new Dimensions(0, 0, 0), new float[0]);
    BspMap.Model world = map.models().getFirst();
    Vec3f origin =
        new Vec3f(
            (float) (Math.ceil(world.mins().x() / size.x()) * size.x()),
            (float) (Math.ceil(world.mins().y() / size.y()) * size.y()),
            (float) (Math.ceil(world.mins().z() / size.z()) * size.z()));
    int x = axisCount(origin.x(), world.maxs().x(), size.x());
    int y = axisCount(origin.y(), world.maxs().y(), size.y());
    int z = axisCount(origin.z(), world.maxs().z(), size.z());
    long total = (long) x * y * z;
    BspMap.LightGrid grid = map.lightGrid();
    Dimensions dimensions = new Dimensions(x, y, z);
    if (x <= 0 || y <= 0 || z <= 0 || total != grid.count())
      return new LightGrid(origin, size, dimensions, new float[0]);
    float[] cells = new float[(int) total * CELL_FLOATS];
    for (int i = 0; i < total; i++) {
      int at = i * CELL_FLOATS;
      int ambient = grid.ambient(i);
      restore(ambient, cells, at);
      restore(grid.directed(i), cells, at + 3);
      decodeDirection(grid.latitude(i), grid.longitude(i), cells, at + 6);
      cells[at + 9] = ambient == 0 ? 0 : 1;
    }
    return new LightGrid(origin, size, dimensions, cells);
  }

  public boolean available() {
    return cells.length > 0;
  }

  public Vec3f origin() {
    return origin;
  }

  public Vec3f cellSize() {
    return cellSize;
  }

  public Dimensions dimensions() {
    return dimensions;
  }

  /**
   * Cells x-fastest, {@link #CELL_FLOATS} floats each; a zero valid flag marks a cell inside solid
   * geometry that sampling skips. Shared storage; callers must not modify it.
   */
  public float[] packed() {
    return cells;
  }

  public Sample sample(Vec3f point) {
    return sampleIfPresent(point).orElse(FALLBACK);
  }

  /** Distinguishes missing or solid cells from the full-bright fallback. */
  public Optional<Sample> sampleIfPresent(Vec3f point) {
    float[] out = new float[9];
    if (!sample(point.x(), point.y(), point.z(), out)) return Optional.empty();
    return Optional.of(
        new Sample(
            new Vec3f(out[0], out[1], out[2]),
            new Vec3f(out[3], out[4], out[5]),
            new Vec3f(out[6], out[7], out[8])));
  }

  /**
   * Trilinear sample without allocation: writes ambient RGB, directed RGB and direction to {@code
   * out[0..8]}. Solid cells are skipped and the remaining weights renormalised. Returns false (and
   * leaves {@code out} alone) when no valid cell contributes.
   */
  public boolean sample(float px, float py, float pz, float[] out) {
    if (!available()) return false;
    double x = coordinate(px, origin.x(), cellSize.x(), dimensions.x());
    double y = coordinate(py, origin.y(), cellSize.y(), dimensions.y());
    double z = coordinate(pz, origin.z(), cellSize.z(), dimensions.z());
    int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
    double fx = x - ix, fy = y - iy, fz = z - iz;
    double ar = 0, ag = 0, ab = 0, dr = 0, dg = 0, db = 0, nx = 0, ny = 0, nz = 0, weights = 0;
    for (int corner = 0; corner < 8; corner++) {
      int sx = ix + (corner & 1), sy = iy + ((corner >>> 1) & 1), sz = iz + ((corner >>> 2) & 1);
      if (sx >= dimensions.x() || sy >= dimensions.y() || sz >= dimensions.z()) continue;
      double weight =
          ((corner & 1) == 0 ? 1 - fx : fx)
              * ((corner & 2) == 0 ? 1 - fy : fy)
              * ((corner & 4) == 0 ? 1 - fz : fz);
      if (weight <= 0) continue;
      int at = (sx + dimensions.x() * (sy + dimensions.y() * sz)) * CELL_FLOATS;
      if (cells[at + 9] == 0) continue;
      ar += cells[at] * weight;
      ag += cells[at + 1] * weight;
      ab += cells[at + 2] * weight;
      dr += cells[at + 3] * weight;
      dg += cells[at + 4] * weight;
      db += cells[at + 5] * weight;
      nx += cells[at + 6] * weight;
      ny += cells[at + 7] * weight;
      nz += cells[at + 8] * weight;
      weights += weight;
    }
    if (weights <= 1e-12) return false;
    out[0] = (float) (ar / weights);
    out[1] = (float) (ag / weights);
    out[2] = (float) (ab / weights);
    out[3] = (float) (dr / weights);
    out[4] = (float) (dg / weights);
    out[5] = (float) (db / weights);
    double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (length > 1e-12) {
      out[6] = (float) (nx / length);
      out[7] = (float) (ny / length);
      out[8] = (float) (nz / length);
    } else {
      out[6] = 0;
      out[7] = 0;
      out[8] = 1;
    }
    return true;
  }

  /** Per-vertex grid lighting for world surfaces with {@code rgbGen lightingDiffuse}. */
  public VertexLighting perVertex() {
    return (px, py, pz, nx, ny, nz, out) -> {
      if (!sample(px, py, pz, out)) {
        FALLBACK.ambient().copyTo(out);
        out[3] = out[4] = out[5] = 0;
        out[6] = 0;
        out[7] = 0;
        out[8] = 1;
      }
    };
  }

  /** BSP light-grid latitude is the polar angle, longitude the azimuth, each in 256 steps. */
  public static Vec3f decodeDirection(int latitude, int longitude) {
    float[] out = new float[3];
    decodeDirection(latitude, longitude, out, 0);
    return Vec3f.of(out);
  }

  private static void decodeDirection(int latitude, int longitude, float[] out, int at) {
    double inclination = (latitude & 255) * (2 * Math.PI / 256);
    double bearing = (longitude & 255) * (2 * Math.PI / 256);
    double radial = Math.sin(inclination);
    out[at] = (float) (Math.cos(bearing) * radial);
    out[at + 1] = (float) (Math.sin(bearing) * radial);
    out[at + 2] = (float) Math.cos(inclination);
  }

  private static void restore(int rgb, float[] out, int at) {
    double red = ((rgb >>> 16) & 255) * (4.0 / 255);
    double green = ((rgb >>> 8) & 255) * (4.0 / 255);
    double blue = (rgb & 255) * (4.0 / 255);
    double scale = Math.max(1, Math.max(red, Math.max(green, blue)));
    out[at] = (float) (red / scale);
    out[at + 1] = (float) (green / scale);
    out[at + 2] = (float) (blue / scale);
  }

  private static double coordinate(double position, double origin, double size, int count) {
    return Math.clamp((position - origin) / size, 0, count - 1);
  }

  private static int axisCount(double origin, double maximum, double step) {
    double count = Math.floor(maximum / step) - Math.rint(origin / step) + 1;
    return !Double.isFinite(count) || count < 1 || count > Integer.MAX_VALUE ? 0 : (int) count;
  }

  private static Vec3f gridSize(BspMap map) {
    for (BspEntity entity : map.entities()) {
      if (!entity.getOrDefault("classname", "").equals("worldspawn")) continue;
      String[] values = entity.getOrDefault("gridsize", "").trim().split("\\s+");
      if (values.length != 3) return DEFAULT_SIZE;
      try {
        float x = Float.parseFloat(values[0]);
        float y = Float.parseFloat(values[1]);
        float z = Float.parseFloat(values[2]);
        if (x >= 1 && y >= 1 && z >= 1 && x <= MAX_CELL && y <= MAX_CELL && z <= MAX_CELL)
          return new Vec3f(x, y, z);
      } catch (NumberFormatException malformed) {
        // Malformed optional metadata uses the defaults, as the original renderer does.
        return DEFAULT_SIZE;
      }
      return DEFAULT_SIZE;
    }
    return DEFAULT_SIZE;
  }
}

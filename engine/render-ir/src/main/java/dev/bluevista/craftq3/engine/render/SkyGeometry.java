package dev.bluevista.craftq3.engine.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;

/**
 * View-centred sky geometry, kept apart from map surfaces and fog. Built around an origin (pass
 * {@link Vec3f#ZERO} once and translate by the view origin on the GPU, or the view origin itself on
 * the CPU).
 */
public final class SkyGeometry {
  /** Box image suffixes, in face order: +X, -X, +Y, -Y, +Z, -Z. */
  public static final List<String> SIDES = List.of("rt", "lf", "bk", "ft", "up", "dn");

  /** Cloud faces per side: the bottom face has no clouds. */
  public static final int CLOUD_FACES = 5;

  public static final int CLOUD_SUBDIVISIONS = 8;
  private static final double WORLD_RADIUS = 4096;

  private SkyGeometry() {}

  /**
   * Appends the six inside-facing box faces: four vertices and six indices each, in {@link #SIDES}
   * order, so face {@code i} uses indices {@code [first + 6i, first + 6i + 6)}. {@code distance} is
   * the half-width. Triangles wind counterclockwise as seen from the centre.
   */
  public static void box(Vec3f origin, float distance, MeshBuffer out) {
    validate(distance);
    for (int side = 0; side < SIDES.size(); side++) {
      float nx = 0, ny = 0, nz = 0;
      switch (side) {
        case 0 -> nx = -1;
        case 1 -> nx = 1;
        case 2 -> ny = -1;
        case 3 -> ny = 1;
        case 4 -> nz = -1;
        default -> nz = 1;
      }
      int a = boxVertex(origin, distance, side, 0, 0, nx, ny, nz, out);
      int b = boxVertex(origin, distance, side, 1, 0, nx, ny, nz, out);
      int c = boxVertex(origin, distance, side, 1, 1, nx, ny, nz, out);
      int d = boxVertex(origin, distance, side, 0, 1, nx, ny, nz, out);
      quad(out, a, b, c, d);
    }
  }

  /**
   * Appends the five tessellated cloud faces. Texture coordinates depend only on ray direction and
   * cloud height, so moving through a map introduces no cloud parallax.
   */
  public static void clouds(Vec3f origin, float distance, float cloudHeight, MeshBuffer out) {
    validate(distance);
    if (!Float.isFinite(cloudHeight) || cloudHeight <= 0)
      throw new IllegalArgumentException("Cloud height must be finite and positive");
    int stride = CLOUD_SUBDIVISIONS + 1;
    float[] direction = new float[3];
    for (int side = 0; side < CLOUD_FACES; side++) {
      int base = out.vertexCount();
      for (int v = 0; v <= CLOUD_SUBDIVISIONS; v++)
        for (int u = 0; u <= CLOUD_SUBDIVISIONS; u++) {
          float s = (float) u / CLOUD_SUBDIVISIONS, t = (float) v / CLOUD_SUBDIVISIONS;
          direction(side, s, t, direction);
          double length =
              Math.sqrt(
                  direction[0] * direction[0]
                      + direction[1] * direction[1]
                      + direction[2] * direction[2]);
          double ux = direction[0] / length, uy = direction[1] / length, uz = direction[2] / length;
          double c = cloudHeight * (2 * WORLD_RADIUS + cloudHeight);
          double vertical = WORLD_RADIUS * uz;
          double discriminant = Math.sqrt(vertical * vertical + c);
          // The stable positive root of the ray/sphere intersection, accurate near the zenith.
          double rayDistance =
              vertical >= 0 ? c / (discriminant + vertical) : discriminant - vertical;
          double radius = WORLD_RADIUS + cloudHeight;
          double x = Math.clamp(ux * rayDistance / radius, -1, 1);
          double y = Math.clamp(uy * rayDistance / radius, -1, 1);
          out.addVertex(
              origin.x() + direction[0] * distance,
              origin.y() + direction[1] * distance,
              origin.z() + direction[2] * distance,
              (float) -ux,
              (float) -uy,
              (float) -uz,
              (float) Math.acos(x),
              (float) Math.acos(y),
              0,
              0,
              -1);
        }
      for (int v = 0; v < CLOUD_SUBDIVISIONS; v++)
        for (int u = 0; u < CLOUD_SUBDIVISIONS; u++) {
          int a = base + v * stride + u;
          quad(out, a, a + 1, a + stride + 1, a + stride);
        }
    }
  }

  private static int boxVertex(
      Vec3f origin,
      float distance,
      int side,
      float u,
      float v,
      float nx,
      float ny,
      float nz,
      MeshBuffer out) {
    float[] d = new float[3];
    direction(side, u, v, d);
    return out.addVertex(
        origin.x() + d[0] * distance,
        origin.y() + d[1] * distance,
        origin.z() + d[2] * distance,
        nx,
        ny,
        nz,
        u,
        v,
        0,
        0,
        -1);
  }

  private static void direction(int side, float u, float v, float[] out) {
    float horizontal = 2 * u - 1, vertical = 1 - 2 * v;
    switch (side) {
      case 0 -> set(out, 1, -horizontal, vertical);
      case 1 -> set(out, -1, horizontal, vertical);
      case 2 -> set(out, horizontal, 1, vertical);
      case 3 -> set(out, -horizontal, -1, vertical);
      case 4 -> set(out, -vertical, -horizontal, 1);
      default -> set(out, vertical, -horizontal, -1);
    }
  }

  private static void set(float[] out, float x, float y, float z) {
    out[0] = x;
    out[1] = y;
    out[2] = z;
  }

  private static void quad(MeshBuffer out, int a, int b, int c, int d) {
    out.addTriangle(a, c, b);
    out.addTriangle(a, d, c);
  }

  private static void validate(float distance) {
    if (!Float.isFinite(distance) || distance <= 0)
      throw new IllegalArgumentException("Sky distance must be finite and positive");
  }
}

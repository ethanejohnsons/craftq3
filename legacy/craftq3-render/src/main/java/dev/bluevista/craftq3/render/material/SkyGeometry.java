package dev.bluevista.craftq3.render.material;

import dev.bluevista.craftq3.assets.bsp.BspMap.Uv;
import dev.bluevista.craftq3.assets.bsp.BspMap.Vertex;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.RenderScene.Camera;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Camera-centered Q3 environment geometry, kept separate from real map surfaces and fog. */
public final class SkyGeometry {
  private static final double WORLD_RADIUS = 4096;
  private static final int CLOUD_SUBDIVISIONS = 8;
  private static final List<String> SIDES = List.of("rt", "lf", "bk", "ft", "up", "dn");
  private static final Uv UNUSED_LIGHTMAP = new Uv(0, 0);

  private SkyGeometry() {}

  /**
   * Six inside-facing triangle lists. Q3 suffixes correspond to +X,-X,+Y,-Y,+Z,-Z respectively;
   * distance is the cube half-width. Vertices wind counterclockwise when viewed from the camera.
   */
  public static Map<String, List<Vertex>> box(Camera camera, float distance) {
    validate(distance);
    Map<String, List<Vertex>> result = new LinkedHashMap<>();
    for (String side : SIDES) {
      List<Vertex> vertices = new ArrayList<>(6);
      quad(
          vertices,
          boxVertex(camera, distance, side, 0, 0),
          boxVertex(camera, distance, side, 1, 0),
          boxVertex(camera, distance, side, 1, 1),
          boxVertex(camera, distance, side, 0, 1));
      result.put(side, List.copyOf(vertices));
    }
    return Collections.unmodifiableMap(result);
  }

  /**
   * Five tessellated cloud faces (Q3 skies omit the bottom cloud face). Texture coordinates depend
   * on ray direction and cloud height, so walking through a map introduces no cloud parallax.
   */
  public static List<Vertex> clouds(Camera camera, float distance, float cloudHeight) {
    validate(distance);
    if (!Float.isFinite(cloudHeight) || cloudHeight <= 0) {
      throw new IllegalArgumentException("Cloud height must be finite and positive");
    }
    List<Vertex> result = new ArrayList<>(5 * CLOUD_SUBDIVISIONS * CLOUD_SUBDIVISIONS * 6);
    for (String side : SIDES) {
      if (side.equals("dn")) continue;
      Vertex[][] grid = new Vertex[CLOUD_SUBDIVISIONS + 1][CLOUD_SUBDIVISIONS + 1];
      for (int v = 0; v <= CLOUD_SUBDIVISIONS; v++) {
        for (int u = 0; u <= CLOUD_SUBDIVISIONS; u++) {
          float s = (float) u / CLOUD_SUBDIVISIONS;
          float t = (float) v / CLOUD_SUBDIVISIONS;
          Vec3 direction = direction(side, s, t);
          Vec3 unit = unit(direction);
          Uv uv = cloudUv(unit, cloudHeight);
          grid[v][u] =
              new Vertex(
                  camera.origin().add(direction.scale(distance)),
                  uv,
                  UNUSED_LIGHTMAP,
                  unit.scale(-1),
                  -1);
        }
      }
      for (int v = 0; v < CLOUD_SUBDIVISIONS; v++) {
        for (int u = 0; u < CLOUD_SUBDIVISIONS; u++) {
          quad(result, grid[v][u], grid[v][u + 1], grid[v + 1][u + 1], grid[v + 1][u]);
        }
      }
    }
    return List.copyOf(result);
  }

  private static Uv cloudUv(Vec3 ray, float height) {
    // Intersect a unit view ray with the sphere (x,y,z+R)^2=(R+h)^2. The stable positive
    // quadratic root is preferable near zenith, where subtracting two large values loses bits.
    double c = height * (2 * WORLD_RADIUS + height);
    double vertical = WORLD_RADIUS * ray.z();
    double discriminant = Math.sqrt(vertical * vertical + c);
    double rayDistance = vertical >= 0 ? c / (discriminant + vertical) : discriminant - vertical;
    double radius = WORLD_RADIUS + height;
    double x = Math.clamp(ray.x() * rayDistance / radius, -1, 1);
    double y = Math.clamp(ray.y() * rayDistance / radius, -1, 1);
    return new Uv((float) Math.acos(x), (float) Math.acos(y));
  }

  private static Vertex boxVertex(Camera camera, float distance, String side, float u, float v) {
    Vec3 position = direction(side, u, v);
    Vec3 inward =
        switch (side) {
          case "rt" -> new Vec3(-1, 0, 0);
          case "lf" -> new Vec3(1, 0, 0);
          case "bk" -> new Vec3(0, -1, 0);
          case "ft" -> new Vec3(0, 1, 0);
          case "up" -> new Vec3(0, 0, -1);
          case "dn" -> new Vec3(0, 0, 1);
          default -> throw new IllegalArgumentException("Invalid sky side " + side);
        };
    return new Vertex(
        camera.origin().add(position.scale(distance)), new Uv(u, v), UNUSED_LIGHTMAP, inward, -1);
  }

  private static Vec3 direction(String side, float u, float v) {
    double horizontal = 2 * u - 1;
    double vertical = 1 - 2 * v;
    return switch (side) {
      case "rt" -> new Vec3(1, -horizontal, vertical);
      case "lf" -> new Vec3(-1, horizontal, vertical);
      case "bk" -> new Vec3(horizontal, 1, vertical);
      case "ft" -> new Vec3(-horizontal, -1, vertical);
      case "up" -> new Vec3(-vertical, -horizontal, 1);
      case "dn" -> new Vec3(vertical, -horizontal, -1);
      default -> throw new IllegalArgumentException("Invalid sky side " + side);
    };
  }

  private static Vec3 unit(Vec3 vector) {
    double length =
        Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y() + vector.z() * vector.z());
    return vector.scale(1 / length);
  }

  private static void quad(List<Vertex> into, Vertex a, Vertex b, Vertex c, Vertex d) {
    into.add(a);
    into.add(c);
    into.add(b);
    into.add(a);
    into.add(d);
    into.add(c);
  }

  private static void validate(float distance) {
    if (!Float.isFinite(distance) || distance <= 0) {
      throw new IllegalArgumentException("Sky distance must be finite and positive");
    }
  }
}

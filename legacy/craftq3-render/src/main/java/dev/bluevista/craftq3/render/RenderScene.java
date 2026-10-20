package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.List;
import java.util.Objects;

/** Immutable Q3 world geometry and camera, independent of the host graphics API. */
public record RenderScene(
    String mapName,
    List<Triangle> triangles,
    Camera camera,
    List<BspMap.Vertex> vertices,
    List<Surface> surfaces,
    BspMap bsp,
    List<DynamicLight> lights) {
  public RenderScene {
    Objects.requireNonNull(mapName, "mapName");
    Objects.requireNonNull(camera, "camera");
    triangles = List.copyOf(triangles);
    vertices = List.copyOf(vertices);
    surfaces = List.copyOf(surfaces);
    lights = List.copyOf(lights);
    if (lights.size() > 128) throw new IllegalArgumentException("Scene exceeds 128 dynamic lights");
  }

  public RenderScene(
      String mapName,
      List<Triangle> triangles,
      Camera camera,
      List<BspMap.Vertex> vertices,
      List<Surface> surfaces,
      BspMap bsp) {
    this(mapName, triangles, camera, vertices, surfaces, bsp, List.of());
  }

  /** Compatibility constructor for diagnostic scenes without BSP material or visibility data. */
  public RenderScene(String mapName, List<Triangle> triangles, Camera camera) {
    this(mapName, triangles, camera, List.of(), List.of(), null);
  }

  public record Triangle(Vec3 a, Vec3 b, Vec3 c, int rgba, int surface) {}

  public record DynamicLight(Vec3 origin, float radius, Vec3 color) {
    public DynamicLight {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(color, "color");
      if (!Float.isFinite(radius)
          || radius <= 0
          || color.x() < 0
          || color.y() < 0
          || color.z() < 0
          || color.x() > 1
          || color.y() > 1
          || color.z() > 1) throw new IllegalArgumentException("Invalid dynamic light");
    }
  }

  /** A contiguous triangle-list range. The id is the original BSP face index. */
  public record Surface(
      int id,
      int texture,
      String shaderName,
      int lightmap,
      int type,
      int firstVertex,
      int vertexCount,
      BspMap.Bounds bounds) {
    public Surface {
      Objects.requireNonNull(shaderName, "shaderName");
      Objects.requireNonNull(bounds, "bounds");
      if (firstVertex < 0 || vertexCount < 0 || vertexCount % 3 != 0)
        throw new IllegalArgumentException("Invalid surface triangle range");
    }

    public int triangleCount() {
      return vertexCount / 3;
    }
  }

  public record Camera(Vec3 origin, float yaw, float pitch, float horizontalFov) {
    public Camera {
      if (origin == null
          || !Float.isFinite(yaw)
          || !Float.isFinite(pitch)
          || !Float.isFinite(horizontalFov)
          || horizontalFov <= 1
          || horizontalFov >= 179) throw new IllegalArgumentException("Invalid camera");
    }
  }

  public RenderScene withCamera(Camera next) {
    return new RenderScene(mapName, triangles, next, vertices, surfaces, bsp, lights);
  }

  public RenderScene withLights(List<DynamicLight> next) {
    return new RenderScene(mapName, triangles, camera, vertices, surfaces, bsp, next);
  }
}

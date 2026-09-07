package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.bsp.BspMap.Face;
import dev.bluevista.craftq3.assets.bsp.BspMap.Uv;
import dev.bluevista.craftq3.assets.bsp.BspMap.Vertex;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Produces triangle lists with complete Q3 vertex attributes and stable BSP surface identities. */
public final class BspSceneBuilder {
  private static final int MAX_TRIANGLES = 1_000_000;

  private BspSceneBuilder() {}

  public static RenderScene build(String name, BspMap map, int subdivisions) {
    if (subdivisions < 1 || subdivisions > 16)
      throw new IllegalArgumentException("Patch subdivisions must be 1..16");
    List<RenderScene.Triangle> triangles = new ArrayList<>();
    List<Vertex> vertices = new ArrayList<>();
    List<RenderScene.Surface> surfaces = new ArrayList<>();
    int first = map.models().isEmpty() ? 0 : map.models().getFirst().firstFace();
    int count = map.models().isEmpty() ? map.faces().size() : map.models().getFirst().faceCount();
    for (int index = first; index < first + count; index++) {
      Face face = map.faces().get(index);
      int start = vertices.size();
      int color = face.type() == 2 ? 0x65c5a8ff : (0x708090ff ^ ((index * 37 & 63) << 24));
      if (face.type() == 1 || face.type() == 3) {
        budget(triangles, face.meshVertexCount() / 3);
        for (int j = 0; j < face.meshVertexCount(); j += 3) {
          Vertex a = vertex(map, face, j);
          Vertex b = vertex(map, face, j + 1);
          Vertex c = vertex(map, face, j + 2);
          append(triangles, vertices, a, b, c, color, index);
        }
      } else if (face.type() == 2) {
        long blocks = (long) ((face.patchWidth() - 1) / 2) * ((face.patchHeight() - 1) / 2);
        budget(triangles, blocks * subdivisions * subdivisions * 2);
        // All blocks and faces use identical parameter intervals. Matching control-point edges
        // therefore produce identical boundary positions without a camera-dependent LOD seam.
        for (int y = 0; y < face.patchHeight() - 2; y += 2)
          for (int x = 0; x < face.patchWidth() - 2; x += 2) {
            Vertex[][] grid = new Vertex[subdivisions + 1][subdivisions + 1];
            for (int v = 0; v <= subdivisions; v++)
              for (int u = 0; u <= subdivisions; u++)
                grid[v][u] =
                    patch(map, face, x, y, u / (double) subdivisions, v / (double) subdivisions);
            // Q3 mesh indices wind opposite the vertex normal. Reverse the control-grid
            // traversal winding to give patches the same culling convention as indexed faces.
            for (int v = 0; v < subdivisions; v++)
              for (int u = 0; u < subdivisions; u++) {
                append(
                    triangles, vertices, grid[v][u], grid[v + 1][u], grid[v][u + 1], color, index);
                append(
                    triangles,
                    vertices,
                    grid[v][u + 1],
                    grid[v + 1][u],
                    grid[v + 1][u + 1],
                    color,
                    index);
              }
          }
      }
      // Flares carry their origin/color in face metadata rather than mesh vertices. Keep their
      // surface identity and bounds so the renderer can emit a camera-facing flare when enabled.
      surfaces.add(
          new RenderScene.Surface(
              index,
              face.texture(),
              map.textures().get(face.texture()).name(),
              face.lightmap(),
              face.type(),
              start,
              vertices.size() - start,
              bounds(vertices, start, face.lightmapOrigin())));
    }
    return new RenderScene(name, triangles, spawn(map), vertices, surfaces, map);
  }

  private static void append(
      List<RenderScene.Triangle> triangles,
      List<Vertex> vertices,
      Vertex a,
      Vertex b,
      Vertex c,
      int color,
      int surface) {
    vertices.add(a);
    vertices.add(b);
    vertices.add(c);
    triangles.add(
        new RenderScene.Triangle(a.position(), b.position(), c.position(), color, surface));
  }

  private static BspMap.Bounds bounds(List<Vertex> vertices, int start, Vec3 fallback) {
    if (start == vertices.size()) return new BspMap.Bounds(fallback, fallback);
    double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    for (int i = start; i < vertices.size(); i++) {
      Vec3 p = vertices.get(i).position();
      minX = Math.min(minX, p.x());
      minY = Math.min(minY, p.y());
      minZ = Math.min(minZ, p.z());
      maxX = Math.max(maxX, p.x());
      maxY = Math.max(maxY, p.y());
      maxZ = Math.max(maxZ, p.z());
    }
    return new BspMap.Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
  }

  private static void budget(List<?> triangles, long count) {
    if (count + triangles.size() > MAX_TRIANGLES)
      throw new IllegalArgumentException("Scene exceeds one million triangles");
  }

  private static Vertex vertex(BspMap map, Face face, int relativeIndex) {
    return map.vertices()
        .get(face.firstVertex() + map.meshVertices().get(face.firstMeshVertex() + relativeIndex));
  }

  private static Vertex patch(BspMap map, Face face, int x, int y, double u, double v) {
    double[] a = {(1 - u) * (1 - u), 2 * u * (1 - u), u * u};
    double[] b = {(1 - v) * (1 - v), 2 * v * (1 - v), v * v};
    Vec3 position = new Vec3(0, 0, 0);
    Vec3 normal = new Vec3(0, 0, 0);
    double textureU = 0, textureV = 0, lightmapU = 0, lightmapV = 0;
    double red = 0, green = 0, blue = 0, alpha = 0;
    for (int j = 0; j < 3; j++)
      for (int i = 0; i < 3; i++) {
        Vertex control =
            map.vertices().get(face.firstVertex() + (y + j) * face.patchWidth() + x + i);
        double weight = a[i] * b[j];
        position = position.add(control.position().scale(weight));
        normal = normal.add(control.normal().scale(weight));
        textureU += control.textureUv().u() * weight;
        textureV += control.textureUv().v() * weight;
        lightmapU += control.lightmapUv().u() * weight;
        lightmapV += control.lightmapUv().v() * weight;
        red += (control.rgba() >>> 24) * weight;
        green += ((control.rgba() >>> 16) & 255) * weight;
        blue += ((control.rgba() >>> 8) & 255) * weight;
        alpha += (control.rgba() & 255) * weight;
      }
    double length =
        Math.sqrt(normal.x() * normal.x() + normal.y() * normal.y() + normal.z() * normal.z());
    if (length > 1e-12) normal = normal.scale(1 / length);
    return new Vertex(
        position,
        new Uv((float) textureU, (float) textureV),
        new Uv((float) lightmapU, (float) lightmapV),
        normal,
        channel(red) << 24 | channel(green) << 16 | channel(blue) << 8 | channel(alpha));
  }

  private static int channel(double value) {
    return Math.clamp((int) Math.round(value), 0, 255);
  }

  public static RenderScene.Camera spawn(BspMap map) {
    for (var entity : map.entities()) {
      String kind = entity.getOrDefault("classname", "");
      if (!kind.equals("info_player_deathmatch") && !kind.equals("info_player_start")) continue;
      try {
        String[] xyz = entity.getOrDefault("origin", "").trim().split("\\s+");
        if (xyz.length != 3) continue;
        Vec3 origin =
            new Vec3(
                Double.parseDouble(xyz[0]),
                Double.parseDouble(xyz[1]),
                Double.parseDouble(xyz[2]) + 26);
        return new RenderScene.Camera(
            origin, Float.parseFloat(entity.getOrDefault("angle", "0")), 0, 90);
      } catch (IllegalArgumentException ignored) {
        /* Bad spawn metadata must not prevent diagnostics. */
      }
    }
    return new RenderScene.Camera(new Vec3(0, 0, 64), 0, 0, 90);
  }
}

package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.md3.Md3Animation;
import dev.bluevista.craftq3.assets.md3.Md3Model;
import dev.bluevista.craftq3.assets.md3.SkinParser;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MD3 frame submission in renderer-independent vertex/material form, ready for cgame ref entities.
 */
public final class ModelGeometry {
  private ModelGeometry() {}

  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  public static final Md3Model.Tag IDENTITY =
      new Md3Model.Tag("", ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));

  public record Surface(
      String name, String shader, List<BspMap.Vertex> vertices, BspMap.Bounds bounds) {
    public Surface {
      vertices = List.copyOf(vertices);
    }
  }

  /** Frames/backlerp come from cgame, never a replacement animation state machine. */
  public static List<Surface> build(
      Md3Model model,
      int oldFrame,
      int frame,
      float backlerp,
      boolean wrapFrames,
      Md3Model.Tag transform,
      Optional<SkinParser.Skin> skin,
      Optional<String> customShader,
      int skinNumber,
      int rgba) {
    if (!Float.isFinite(backlerp) || backlerp < 0 || backlerp > 1)
      throw new IllegalArgumentException("Invalid model backlerp");
    if (wrapFrames) {
      oldFrame = Math.floorMod(oldFrame, model.frames().size());
      frame = Math.floorMod(frame, model.frames().size());
    }
    if (oldFrame < 0
        || oldFrame >= model.frames().size()
        || frame < 0
        || frame >= model.frames().size())
      throw new IllegalArgumentException("Model frame outside loaded animation");
    List<Surface> surfaces = new ArrayList<>();
    NormalTransform normals = NormalTransform.from(transform);
    int vertices = 0;
    for (var source : model.surfaces()) {
      if (source.triangles().isEmpty()) continue;
      vertices = Math.addExact(vertices, Math.multiplyExact(source.triangles().size(), 3));
      if (vertices > 1_000_000)
        throw new IllegalArgumentException("Model submission vertex budget exceeded");
      String shader =
          customShader.orElseGet(
              () ->
                  skin.flatMap(s -> s.shaderForSurface(source.name()))
                      .orElseGet(
                          () ->
                              skin.isPresent() || source.shaders().isEmpty()
                                  ? "$missing"
                                  : source
                                      .shaders()
                                      .get(Math.floorMod(skinNumber, source.shaders().size()))
                                      .name()));
      var blended = Md3Animation.interpolateSurface(source, oldFrame, frame, 1 - backlerp);
      List<BspMap.Vertex> attributes = new ArrayList<>(blended.size());
      for (int i = 0; i < blended.size(); i++) {
        var vertex = blended.get(i);
        var uv = source.texCoords().get(i);
        attributes.add(
            new BspMap.Vertex(
                transform.transformPoint(vertex.position()),
                new BspMap.Uv(uv.s(), uv.t()),
                new BspMap.Uv(0, 0),
                normals.apply(vertex.normal()),
                rgba));
      }
      List<BspMap.Vertex> triangles = new ArrayList<>(source.triangles().size() * 3);
      for (var triangle : source.triangles()) {
        triangles.add(attributes.get(triangle.a()));
        triangles.add(attributes.get(normals.mirrored() ? triangle.c() : triangle.b()));
        triangles.add(attributes.get(normals.mirrored() ? triangle.b() : triangle.c()));
      }
      surfaces.add(new Surface(source.name(), shader, triangles, bounds(triangles)));
    }
    return List.copyOf(surfaces);
  }

  /**
   * Cofactors give normalized inverse-transpose normals without dividing by scale. They also
   * preserve planar singular geometry and allow the zero-scale entities submitted by cgame.
   */
  private record NormalTransform(Vec3 x, Vec3 y, Vec3 z, boolean mirrored) {
    static NormalTransform from(Md3Model.Tag matrix) {
      Vec3 yz = cross(matrix.axisY(), matrix.axisZ()),
          zx = cross(matrix.axisZ(), matrix.axisX()),
          xy = cross(matrix.axisX(), matrix.axisY());
      double determinant = dot(matrix.axisX(), yz);
      double orientation = determinant < 0 ? -1 : 1;
      return new NormalTransform(
          yz.scale(orientation), zx.scale(orientation), xy.scale(orientation), determinant < 0);
    }

    Vec3 apply(Vec3 source) {
      Vec3 result = x.scale(source.x()).add(y.scale(source.y())).add(z.scale(source.z()));
      double length = Math.hypot(Math.hypot(result.x(), result.y()), result.z());
      return length == 0 ? ZERO : result.scale(1 / length);
    }
  }

  private static BspMap.Bounds bounds(List<BspMap.Vertex> vertices) {
    double minX = Double.POSITIVE_INFINITY,
        minY = minX,
        minZ = minX,
        maxX = Double.NEGATIVE_INFINITY,
        maxY = maxX,
        maxZ = maxX;
    for (var vertex : vertices) {
      var p = vertex.position();
      minX = Math.min(minX, p.x());
      minY = Math.min(minY, p.y());
      minZ = Math.min(minZ, p.z());
      maxX = Math.max(maxX, p.x());
      maxY = Math.max(maxY, p.y());
      maxZ = Math.max(maxZ, p.z());
    }
    return new BspMap.Bounds(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
  }

  private static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  private static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }
}

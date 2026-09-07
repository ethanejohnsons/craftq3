package dev.bluevista.craftq3.render;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.md3.Md3Model;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dynamic cgame geometry has its own identity and never enters the static BSP face/PVS index. */
public final class SubmittedGeometry {
  private final Map<Integer, RenderScene> inline = new HashMap<>();
  private BspMap map;

  public record Surface(
      String shader,
      List<BspMap.Vertex> vertices,
      int lightmap,
      BspMap.Bounds bounds,
      CgameFrame.RefEntity entity) {
    public Surface {
      vertices = List.copyOf(vertices);
    }
  }

  public List<Surface> build(RenderScene world, CgameFrame.View view, boolean portalView) {
    if (map != world.bsp()) {
      map = world.bsp();
      inline.clear();
    }
    var result = new ArrayList<Surface>();
    long vertexBudget = 0;
    for (var entity : view.entities()) {
      if (!entity.visible(portalView)) continue;
      long required =
          entity.model() != null
              ? entity.model().surfaces().stream()
                  .mapToLong(s -> (long) s.triangles().size() * 3)
                  .sum()
              : entity.type() == CgameFrame.EntityType.RAIL_RINGS ? 6144 : 6;
      if (entity.type() == CgameFrame.EntityType.MODEL
          && entity.model() == null
          && map != null
          && entity.inlineModel() >= 0
          && entity.inlineModel() < map.models().size())
        required =
            inline.computeIfAbsent(entity.inlineModel(), this::inlineModel).vertices().size();
      vertexBudget += required;
      if (vertexBudget > 3_000_000)
        throw new IllegalArgumentException("Submitted geometry exceeds one million triangles");
      switch (entity.type()) {
        case MODEL -> {
          if (entity.model() != null) {
            for (var surface :
                ModelGeometry.build(
                    entity.model(),
                    entity.oldFrame(),
                    entity.frame(),
                    entity.backlerp(),
                    (entity.renderFx() & CgameFrame.RF_WRAP_FRAMES) != 0,
                    entity.transform(),
                    entity.skin(),
                    entity.customShader(),
                    entity.skinNumber(),
                    entity.rgba()))
              result.add(
                  new Surface(surface.shader(), surface.vertices(), -1, surface.bounds(), entity));
          } else if (map != null
              && entity.inlineModel() >= 0
              && entity.inlineModel() < map.models().size()) {
            var mesh = inline.computeIfAbsent(entity.inlineModel(), this::inlineModel);
            for (var surface : mesh.surfaces()) {
              if (surface.vertexCount() == 0) continue;
              var vertices = new ArrayList<BspMap.Vertex>();
              var transform = entity.transform();
              boolean mirrored =
                  dot(transform.axisX(), cross(transform.axisY(), transform.axisZ())) < 0;
              for (int i = 0; i < surface.vertexCount(); i++) {
                int j = mirrored ? i / 3 * 3 + (i % 3 == 0 ? 0 : 3 - i % 3) : i;
                var v = mesh.vertices().get(surface.firstVertex() + j);
                vertices.add(
                    new BspMap.Vertex(
                        transform.transformPoint(v.position()),
                        v.textureUv(),
                        v.lightmapUv(),
                        normal(transform, v.normal()),
                        dev.bluevista.craftq3.render.material.StageEvaluator.restoreMapLightColor(
                            v.rgba(), 2)));
              }
              result.add(
                  new Surface(
                      entity.customShader().orElse(surface.shaderName()),
                      vertices,
                      surface.lightmap(),
                      bounds(vertices),
                      entity));
            }
          }
        }
        case SPRITE -> result.add(sprite(entity, view.refdef()));
        case BEAM, RAIL_CORE, LIGHTNING -> result.add(ribbon(entity, view.refdef()));
        case RAIL_RINGS -> rings(result, entity, view.refdef());
        case POLY, PORTALSURFACE -> {
          /* Poly traps provide vertices separately; portal entities are linkage. */
        }
      }
    }
    for (var polygon : view.polygons()) {
      vertexBudget += (long) (polygon.vertices().size() - 2) * 3;
      if (vertexBudget > 3_000_000)
        throw new IllegalArgumentException("Submitted geometry exceeds one million triangles");
      var vertices = new ArrayList<BspMap.Vertex>();
      var p = polygon.vertices();
      Vec3 n =
          unit(
              cross(
                  sub(p.get(2).position(), p.get(0).position()),
                  sub(p.get(1).position(), p.get(0).position())));
      for (int i = 1; i < p.size() - 1; i++)
        for (int index : new int[] {0, i, i + 1}) {
          var v = p.get(index);
          vertices.add(new BspMap.Vertex(v.position(), v.uv(), new BspMap.Uv(0, 0), n, v.rgba()));
        }
      result.add(new Surface(polygon.shader(), vertices, -1, bounds(vertices), null));
    }
    if (result.stream().mapToLong(s -> s.vertices().size()).sum() > 3_000_000)
      throw new IllegalArgumentException("Submitted geometry exceeds one million triangles");
    return List.copyOf(result);
  }

  private RenderScene inlineModel(int index) {
    var model = map.models().get(index);
    var selected =
        new BspMap(
            map.entities(),
            map.textures(),
            map.planes(),
            map.nodes(),
            map.leaves(),
            map.leafFaces(),
            map.leafBrushes(),
            List.of(model),
            map.brushes(),
            map.brushSides(),
            map.vertices(),
            map.meshVertices(),
            map.effects(),
            map.faces(),
            map.lightmaps(),
            map.lightVolumes(),
            map.visibility());
    return BspSceneBuilder.build("inline" + index, selected, 8);
  }

  private static Surface sprite(CgameFrame.RefEntity entity, CgameFrame.Refdef view) {
    double angle = Math.toRadians(entity.rotation()), c = Math.cos(angle), s = Math.sin(angle);
    var basis = view.basis();
    Vec3 r = basis.right().scale(c).add(basis.up().scale(s)).scale(entity.radius());
    Vec3 u = basis.up().scale(c).add(basis.right().scale(-s)).scale(entity.radius());
    Vec3 origin = entity.transform().origin(), n = basis.forward().scale(-1);
    return quad(
        entity,
        origin.add(r.scale(-1)).add(u),
        origin.add(r).add(u),
        origin.add(r).add(u.scale(-1)),
        origin.add(r.scale(-1)).add(u.scale(-1)),
        n);
  }

  private static Surface ribbon(CgameFrame.RefEntity entity, CgameFrame.Refdef view) {
    Vec3 a = entity.transform().origin(), b = entity.oldOrigin();
    Vec3 direction = sub(b, a), eye = sub(view.origin(), a.add(b).scale(.5));
    Vec3 side = unit(cross(direction, eye));
    if (dot(side, side) < .5) side = view.basis().right();
    side = side.scale(entity.radius() > 0 ? entity.radius() : 4);
    return quad(
        entity, a.add(side.scale(-1)), a.add(side), b.add(side), b.add(side.scale(-1)), unit(eye));
  }

  private static void rings(
      List<Surface> output, CgameFrame.RefEntity entity, CgameFrame.Refdef view) {
    Vec3 a = entity.transform().origin(), delta = sub(entity.oldOrigin(), a);
    double distance = Math.sqrt(dot(delta, delta));
    int count = Math.min(1024, Math.max(1, (int) (distance / 32)));
    Vec3 direction = unit(delta), side = unit(cross(direction, view.axisZ()));
    if (dot(side, side) < .5) side = view.basis().right();
    Vec3 up = unit(cross(direction, side));
    double radius = entity.radius() > 0 ? entity.radius() : 4;
    side = side.scale(radius);
    up = up.scale(radius);
    for (int i = 0; i < count; i++) {
      Vec3 center = a.add(delta.scale((i + .5) / count));
      output.add(
          quad(
              entity,
              center.add(side.scale(-1)).add(up),
              center.add(side).add(up),
              center.add(side).add(up.scale(-1)),
              center.add(side.scale(-1)).add(up.scale(-1)),
              direction.scale(-1)));
    }
  }

  private static Surface quad(
      CgameFrame.RefEntity entity, Vec3 a, Vec3 b, Vec3 c, Vec3 d, Vec3 normal) {
    Vec3[] points = {a, b, c, d};
    float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    boolean reverse = dot(cross(sub(b, a), sub(c, a)), normal) > 0;
    int[] indexes = reverse ? new int[] {0, 2, 1, 0, 3, 2} : new int[] {0, 1, 2, 0, 2, 3};
    var vertices = new ArrayList<BspMap.Vertex>();
    for (int i : indexes)
      vertices.add(
          new BspMap.Vertex(
              points[i],
              new BspMap.Uv(uv[i][0], uv[i][1]),
              new BspMap.Uv(0, 0),
              normal,
              entity.rgba()));
    return new Surface(
        entity.customShader().orElse("$missing"), vertices, -1, bounds(vertices), entity);
  }

  public static BspMap.Bounds bounds(List<BspMap.Vertex> vertices) {
    double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
        hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    for (var vertex : vertices) {
      double[] p = {vertex.position().x(), vertex.position().y(), vertex.position().z()};
      for (int i = 0; i < 3; i++) {
        lo[i] = Math.min(lo[i], p[i]);
        hi[i] = Math.max(hi[i], p[i]);
      }
    }
    return new BspMap.Bounds(new Vec3(lo[0], lo[1], lo[2]), new Vec3(hi[0], hi[1], hi[2]));
  }

  private static Vec3 normal(Md3Model.Tag t, Vec3 n) {
    var yz = cross(t.axisY(), t.axisZ());
    double determinant = dot(t.axisX(), yz);
    if (Math.abs(determinant) < 1e-12)
      throw new IllegalArgumentException("Singular inline model axes");
    return unit(
        yz.scale(n.x())
            .add(cross(t.axisZ(), t.axisX()).scale(n.y()))
            .add(cross(t.axisX(), t.axisY()).scale(n.z()))
            .scale(1 / determinant));
  }

  static Vec3 sub(Vec3 a, Vec3 b) {
    return a.add(b.scale(-1));
  }

  static double dot(Vec3 a, Vec3 b) {
    return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
  }

  static Vec3 cross(Vec3 a, Vec3 b) {
    return new Vec3(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }

  static Vec3 unit(Vec3 n) {
    double length = Math.sqrt(dot(n, n));
    return length < 1e-12 ? new Vec3(0, 0, 0) : n.scale(1 / length);
  }
}

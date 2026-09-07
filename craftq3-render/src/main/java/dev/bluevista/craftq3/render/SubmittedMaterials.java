package dev.bluevista.craftq3.render;

import static dev.bluevista.craftq3.assets.shader.ShaderDefinition.*;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.material.StageEvaluator;
import dev.bluevista.craftq3.render.material.VertexDeformer;
import java.util.ArrayList;
import java.util.List;

/**
 * Material evaluation for submitted geometry, reusing the world stage evaluator and deform rules.
 */
public final class SubmittedMaterials {
  private final SubmittedGeometry geometry = new SubmittedGeometry();
  private static final Vec3 WHITE = new Vec3(1, 1, 1), ZERO = new Vec3(0, 0, 0);
  private static final Stage FOG_STAGE =
      new Stage(
          new TextureMap(List.of("$whiteimage"), 0, true),
          Blend.ALPHA,
          AlphaFunc.NONE,
          RgbGen.of(RgbGenType.EXACT_VERTEX),
          AlphaGen.of(AlphaGenType.VERTEX),
          TcGen.of(TcGenType.BASE),
          List.of(),
          DepthFunc.EQUAL,
          false,
          false);

  public record Batch(
      ShaderDefinition material,
      Stage stage,
      List<BspMap.Vertex> vertices,
      BspMap.Bounds bounds,
      int lightmap,
      boolean depthHack,
      int fogAdjustment,
      List<Float> fogAmounts,
      String texture,
      boolean overlay) {
    public Batch {
      vertices = List.copyOf(vertices);
      fogAmounts = List.copyOf(fogAmounts);
    }
  }

  public List<Batch> build(
      RenderScene world,
      CgameFrame.View view,
      SceneAssets assets,
      LightGrid lightGrid,
      FogVolumes fog,
      boolean portalView) {
    var batches = new ArrayList<Batch>();
    int count = 0;
    for (var surface : geometry.build(world, view, portalView)) {
      var material = assets.resolve(surface.shader());
      if (material.noDraw() || material.skySurface() || surface.vertices().isEmpty()) continue;
      var entity = surface.entity();
      double seconds =
          view.refdef().timeMillis() / 1000.0 - (entity == null ? 0 : entity.shaderTime());
      if (material.clampTime() > 0) seconds = Math.min(seconds, material.clampTime());
      var source =
          VertexDeformer.deform(
              surface.vertices(), material, seconds, view.refdef().camera(), view.refdef().basis());
      boolean reverse = (material.cull() == Cull.FRONT) ^ view.refdef().mirrored();
      var lighting =
          lightGrid == null
              ? null
              : lightGrid.sample(
                  entity == null
                      ? surface.bounds().min().add(surface.bounds().max()).scale(.5)
                      : entity.lightOrigin());
      Vec3 ambient = lighting == null ? new Vec3(.25, .25, .25) : lighting.ambient();
      if (entity != null && (entity.renderFx() & CgameFrame.RF_MINLIGHT) != 0)
        ambient =
            new Vec3(
                Math.max(ambient.x(), .125),
                Math.max(ambient.y(), .125),
                Math.max(ambient.z(), .125));
      int rgba = entity == null ? -1 : entity.rgba();
      Vec3 color =
          new Vec3(
              (rgba >>> 24) / 255.0, ((rgba >>> 16) & 255) / 255.0, ((rgba >>> 8) & 255) / 255.0);
      var context =
          new StageEvaluator.Context(
              view.refdef().camera(),
              seconds,
              color,
              (rgba & 255) / 255f,
              lighting == null ? new Vec3(0, 0, 1) : lighting.direction(),
              ambient,
              lighting == null ? new Vec3(.75, .75, .75) : lighting.directed(),
              ZERO,
              entity == null
                  ? ZERO
                  : new Vec3(entity.shaderTexCoord().u(), entity.shaderTexCoord().v(), 0),
              1);
      for (var stage : material.stages()) {
        count = Math.addExact(count, source.size());
        if (count > 9_000_000)
          throw new IllegalArgumentException("Submitted material vertex budget exceeded");
        int adjust = fog == null || opaque(material) ? 0 : fogAdjustment(stage.blend());
        var vertices = new ArrayList<BspMap.Vertex>();
        var fogAmounts = new ArrayList<Float>();
        for (int i = 0; i < source.size(); i++) {
          int index = reverse ? i / 3 * 3 + (i % 3 == 0 ? 0 : 3 - i % 3) : i;
          var vertex = source.get(index);
          var evaluation = context;
          if (stage.rgbGen().type() == RgbGenType.LIGHTING_DIFFUSE && !view.lights().isEmpty()) {
            var dynamic = DynamicLighting.sample(view.lights(), vertex);
            evaluation =
                new StageEvaluator.Context(
                    context.camera(),
                    seconds,
                    color,
                    context.entityAlpha(),
                    context.lightDirection(),
                    ambient.add(dynamic),
                    context.directedLight(),
                    context.fogColor(),
                    context.entityTexOffset(),
                    1);
          }
          if (stage.rgbGen().type() == RgbGenType.FOG && fog != null) {
            var colorFog = fog.sample(view.refdef().origin(), vertex.position()).color();
            evaluation =
                new StageEvaluator.Context(
                    context.camera(),
                    seconds,
                    color,
                    context.entityAlpha(),
                    context.lightDirection(),
                    ambient,
                    context.directedLight(),
                    colorFog,
                    context.entityTexOffset(),
                    1);
          }
          vertices.add(StageEvaluator.evaluate(vertex, stage, evaluation));
          fogAmounts.add(
              adjust == 0 ? 0 : fog.sample(view.refdef().origin(), vertex.position()).opacity());
        }
        batches.add(
            new Batch(
                material,
                stage,
                vertices,
                surface.bounds(),
                surface.lightmap(),
                entity != null && entity.depthHack(),
                adjust,
                fogAmounts,
                stage.texture().atTime(seconds),
                false));
      }
      if (fog != null && fog.count() > 0 && opaque(material)) {
        count = Math.addExact(count, source.size());
        if (count > 9_000_000)
          throw new IllegalArgumentException("Submitted material vertex budget exceeded");
        var vertices = new ArrayList<BspMap.Vertex>();
        for (int i = 0; i < source.size(); i++) {
          int index = reverse ? i / 3 * 3 + (i % 3 == 0 ? 0 : 3 - i % 3) : i;
          var vertex = source.get(index);
          var sample = fog.sample(view.refdef().origin(), vertex.position());
          int fogRgba = pack(sample.color(), sample.opacity());
          vertices.add(
              new BspMap.Vertex(
                  vertex.position(),
                  vertex.textureUv(),
                  vertex.lightmapUv(),
                  vertex.normal(),
                  fogRgba));
        }
        batches.add(
            new Batch(
                material,
                FOG_STAGE,
                vertices,
                surface.bounds(),
                -1,
                entity != null && entity.depthHack(),
                0,
                java.util.Collections.nCopies(vertices.size(), 0f),
                "$whiteimage",
                false));
      }
    }
    return List.copyOf(batches);
  }

  public static List<Batch> image(CgameFrame.Image image) {
    return quad(
        new CgameFrame.Quad(
            image.x(), image.y(), image.width(), image.height(), 0, 0, 1, 1, image.texture(), -1),
        SceneAssets.EMPTY,
        0);
  }

  public static List<Batch> quad(CgameFrame.Quad quad, SceneAssets assets, double seconds) {
    var material = assets.resolve(quad.shader());
    if (material.noDraw() || quad.width() == 0 || quad.height() == 0) return List.of();
    float[] x = {quad.x(), quad.x() + quad.width(), quad.x() + quad.width(), quad.x()};
    float[] y = {quad.y(), quad.y(), quad.y() + quad.height(), quad.y() + quad.height()};
    float[] s = {quad.s1(), quad.s2(), quad.s2(), quad.s1()},
        t = {quad.t1(), quad.t1(), quad.t2(), quad.t2()};
    var source = new ArrayList<BspMap.Vertex>();
    for (int i : new int[] {0, 1, 2, 0, 2, 3})
      source.add(
          new BspMap.Vertex(
              new Vec3(x[i], y[i], 0),
              new BspMap.Uv(s[i], t[i]),
              new BspMap.Uv(0, 0),
              new Vec3(0, 0, 1),
              quad.rgba()));
    var result = new ArrayList<Batch>();
    int rgba = quad.rgba();
    var context =
        new StageEvaluator.Context(
            new RenderScene.Camera(ZERO, 0, 0, 90),
            seconds,
            new Vec3(
                (rgba >>> 24) / 255.0, ((rgba >>> 16) & 255) / 255.0, ((rgba >>> 8) & 255) / 255.0),
            (rgba & 255) / 255f,
            new Vec3(0, 0, 1),
            WHITE,
            ZERO,
            ZERO,
            ZERO,
            1);
    for (var stage : material.stages()) {
      var vertices = source.stream().map(v -> StageEvaluator.evaluate(v, stage, context)).toList();
      result.add(
          new Batch(
              material,
              stage,
              vertices,
              SubmittedGeometry.bounds(vertices),
              -1,
              false,
              0,
              java.util.Collections.nCopies(6, 0f),
              stage.texture().atTime(seconds),
              true));
    }
    return result;
  }

  private static boolean opaque(ShaderDefinition material) {
    return !material.stages().isEmpty() && material.stages().getFirst().blend().opaque();
  }

  private static int fogAdjustment(Blend blend) {
    if (blend.equals(Blend.ADD)
        || blend.equals(new Blend(BlendFactor.ZERO, BlendFactor.ONE_MINUS_SRC_COLOR))) return 1;
    if (blend.equals(Blend.ALPHA)) return 2;
    if (blend.equals(new Blend(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA))) return 3;
    if (blend.equals(Blend.FILTER)
        || blend.equals(new Blend(BlendFactor.ZERO, BlendFactor.SRC_COLOR))) return 4;
    return 0;
  }

  private static int pack(Vec3 rgb, float alpha) {
    return ((int) Math.clamp(rgb.x() * 255, 0, 255) << 24)
        | ((int) Math.clamp(rgb.y() * 255, 0, 255) << 16)
        | ((int) Math.clamp(rgb.z() * 255, 0, 255) << 8)
        | (int) Math.clamp(alpha * 255, 0, 255);
  }
}

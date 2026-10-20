package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.DynamicLighting;
import dev.bluevista.craftq3.engine.render.FogVolumes;
import dev.bluevista.craftq3.engine.render.LightGrid;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.StageEvaluator;
import dev.bluevista.craftq3.engine.render.material.StageProgram;
import dev.bluevista.craftq3.engine.render.material.VertexArrays;
import dev.bluevista.craftq3.engine.render.material.VertexFog;
import dev.bluevista.craftq3.engine.render.material.VertexLighting;
import java.util.List;

/**
 * CPU stage evaluation for backends without shader-side stages: produces the colours and texture
 * coordinates of one stage of a {@link DynamicDraw} with {@link StageEvaluator}, using the same
 * lighting the GPU path receives (the draw's entity lighting, which already includes dynamic
 * lights; the grid per vertex for world surfaces) and per-vertex fog colour for {@code rgbGen fog}.
 */
public final class FallbackShading {
  private FallbackShading() {}

  /**
   * Evaluates {@code stage} for {@code draw}'s vertices; output index {@code i} is vertex {@code
   * draw.firstVertex() + i}. {@code fog} may be null.
   */
  public static void shade(
      FrameGeometry frame,
      DynamicDraw draw,
      MaterialProgram material,
      StageProgram stage,
      Vec3f viewOrigin,
      List<DynamicLight> lights,
      LightGrid grid,
      FogVolumes fog,
      float[] texCoordsOut,
      byte[] colorsOut) {
    // Entities already carry their dynamic lights in the draw's directed light; brush models take
    // them per vertex, standing in for the projected light pass.
    VertexLighting lighting =
        switch (draw.source()) {
          case WORLD -> grid.perVertex();
          case INLINE -> lights.isEmpty() ? null : DynamicLighting.addedToAmbient(lights);
          default -> null;
        };
    VertexFog fogColor = fog == null || fog.count() == 0 ? null : fog.colorFrom(viewOrigin);
    StageEvaluator.evaluate(
        stage.shading(),
        draw.inputs(viewOrigin, material.shaderTime(draw.shaderSeconds())),
        frame.mesh().arrays(),
        draw.firstVertex(),
        draw.vertexCount(),
        lighting,
        fogColor,
        texCoordsOut,
        colorsOut,
        0);
  }

  /**
   * The separate fog pass of an opaque material: per vertex, the fog colour seen from {@code eye}
   * with its opacity as alpha.
   */
  public static void fogPass(
      VertexArrays vertices, int first, int count, Vec3f eye, FogVolumes fog, byte[] colorsOut) {
    float[] sample = new float[4];
    double[] scratch = fog.newScratch();
    float[] p = vertices.positions();
    for (int i = 0; i < count; i++) {
      int v = first + i;
      fog.sample(eye.x(), eye.y(), eye.z(), p[v * 3], p[v * 3 + 1], p[v * 3 + 2], sample, scratch);
      int rgba =
          (int) Math.clamp(sample[0] * 255, 0, 255) << 24
              | (int) Math.clamp(sample[1] * 255, 0, 255) << 16
              | (int) Math.clamp(sample[2] * 255, 0, 255) << 8
              | (int) Math.clamp(sample[3] * 255, 0, 255);
      VertexArrays.putRgba(colorsOut, i, rgba);
    }
  }

  /** Fog opacity per vertex, the factor a stage's {@code FogMode} applies. */
  public static void fogOpacity(
      VertexArrays vertices, int first, int count, Vec3f eye, FogVolumes fog, float[] out) {
    float[] sample = new float[4];
    double[] scratch = fog.newScratch();
    float[] p = vertices.positions();
    for (int i = 0; i < count; i++) {
      int v = first + i;
      fog.sample(eye.x(), eye.y(), eye.z(), p[v * 3], p[v * 3 + 1], p[v * 3 + 2], sample, scratch);
      out[i] = sample[3];
    }
  }
}

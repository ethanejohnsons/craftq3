package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.render.material.ShadingInputs;
import java.util.Objects;

/**
 * One per-frame draw from {@link FrameGeometry#mesh()}: a material over a vertex and index range,
 * with the uniforms its stages read. Vertex colours are the entity colour (models, sprites) or the
 * submitted colours (polys, deformed world surfaces).
 *
 * @param source what produced the geometry
 * @param material index into the frame's {@code MaterialRegistry}
 * @param lightmap lightmap page for deformed world surfaces, else -1
 * @param fog fog volume (BSP effect index) the draw is inside, or -1
 * @param entity index into the view's entity list, or -1
 * @param surface the world surface (WORLD) or the model surface index (MODEL), else -1
 * @param shaderSeconds refdef time minus the entity's shader time, before the material clamp
 * @param rgba entity colour {@code 0xRRGGBBAA} (white without an entity)
 * @param shaderS entity texture offset read by {@code tcMod entityTranslate}
 * @param ambient light-grid ambient light at the entity's lighting origin (or the draw centre)
 * @param bounds min xyz then max xyz of the vertices
 */
public record DynamicDraw(
    Source source,
    int material,
    int lightmap,
    int fog,
    int firstVertex,
    int vertexCount,
    int firstIndex,
    int indexCount,
    int entity,
    int surface,
    boolean depthHack,
    double shaderSeconds,
    int rgba,
    float shaderS,
    float shaderT,
    Vec3f ambient,
    Vec3f directed,
    Vec3f lightDirection,
    float[] bounds) {
  /** Where a dynamic draw's geometry came from. */
  public enum Source {
    /** A visible world surface whose material deforms on the CPU (autosprite, text). */
    WORLD,
    MODEL,
    /** An inline BSP model copied on the CPU (see {@link FrameBuilder.Options}). */
    INLINE,
    SPRITE,
    BEAM,
    RAIL_RINGS,
    POLY
  }

  public DynamicDraw {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(ambient, "ambient");
    Objects.requireNonNull(directed, "directed");
    Objects.requireNonNull(lightDirection, "lightDirection");
    if (bounds.length != 6) throw new IllegalArgumentException("Bounds need six floats");
    bounds = bounds.clone();
  }

  @Override
  public float[] bounds() {
    return bounds.clone();
  }

  public float bound(int k) {
    return bounds[k];
  }

  /** The stage uniforms for this draw, seen from {@code viewOrigin}. */
  public ShadingInputs inputs(Vec3f viewOrigin, double materialSeconds) {
    return new ShadingInputs(
        viewOrigin,
        materialSeconds,
        new Vec3f((rgba >>> 24) / 255f, ((rgba >>> 16) & 255) / 255f, ((rgba >>> 8) & 255) / 255f),
        (rgba & 255) / 255f,
        lightDirection,
        ambient,
        directed,
        Vec3f.ZERO,
        new Vec3f(shaderS, shaderT, 0),
        1);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof DynamicDraw d
        && source == d.source
        && material == d.material
        && lightmap == d.lightmap
        && fog == d.fog
        && firstVertex == d.firstVertex
        && vertexCount == d.vertexCount
        && firstIndex == d.firstIndex
        && indexCount == d.indexCount
        && entity == d.entity
        && surface == d.surface
        && depthHack == d.depthHack
        && Double.compare(shaderSeconds, d.shaderSeconds) == 0
        && rgba == d.rgba
        && Float.compare(shaderS, d.shaderS) == 0
        && Float.compare(shaderT, d.shaderT) == 0
        && ambient.equals(d.ambient)
        && directed.equals(d.directed)
        && lightDirection.equals(d.lightDirection)
        && java.util.Arrays.equals(bounds, d.bounds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(source, material, firstVertex, firstIndex, entity, surface);
  }

  @Override
  public String toString() {
    return "DynamicDraw["
        + source
        + " material="
        + material
        + " vertices="
        + firstVertex
        + "+"
        + vertexCount
        + " indices="
        + firstIndex
        + "+"
        + indexCount
        + "]";
  }
}

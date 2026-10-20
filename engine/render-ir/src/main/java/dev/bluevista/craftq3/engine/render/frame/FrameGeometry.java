package dev.bluevista.craftq3.engine.render.frame;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.md3.Md3Model;
import dev.bluevista.craftq3.engine.render.MeshBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The per-frame output of {@link FrameBuilder} for one view: a reusable mesh with dynamic draws
 * (models, sprites, beams, polys, CPU-deformed world surfaces) and instanced inline BSP models,
 * which the backend draws from the static world batches with the entity transform.
 */
public final class FrameGeometry {
  /** Vertex budget of one view's dynamic geometry. */
  public static final int MAX_VERTICES = 3_000_000;

  public static final int MAX_INDICES = 9_000_000;

  private final MeshBuffer mesh = new MeshBuffer(MAX_VERTICES, MAX_INDICES);
  private final List<DynamicDraw> draws = new ArrayList<>();
  private final List<InlineInstance> inlineModels = new ArrayList<>();

  /**
   * An entity drawing BSP model {@code model}'s static batches with {@code transform}.
   *
   * @param mirrored true when the transform reflects, so the backend swaps front-face winding
   */
  public record InlineInstance(
      int entity,
      int model,
      Md3Model.Tag transform,
      boolean mirrored,
      int customMaterial,
      int rgba,
      double shaderSeconds,
      float shaderS,
      float shaderT,
      int fog,
      Vec3f ambient,
      Vec3f directed,
      Vec3f lightDirection) {
    public InlineInstance {
      Objects.requireNonNull(transform, "transform");
    }
  }

  public void clear() {
    mesh.clear();
    draws.clear();
    inlineModels.clear();
  }

  public MeshBuffer mesh() {
    return mesh;
  }

  public List<DynamicDraw> draws() {
    return Collections.unmodifiableList(draws);
  }

  public List<InlineInstance> inlineModels() {
    return Collections.unmodifiableList(inlineModels);
  }

  void add(DynamicDraw draw) {
    draws.add(draw);
  }

  void add(InlineInstance instance) {
    inlineModels.add(instance);
  }
}

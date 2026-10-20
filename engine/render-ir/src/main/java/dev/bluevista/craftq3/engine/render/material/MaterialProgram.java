package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A compiled material: ordered stages plus whole-material state. Produced once per shader by {@link
 * MaterialCompiler}; a GPU backend builds one pipeline per stage and evaluates {@link
 * StageProgram.Shading} and the GPU-capable {@link #deforms()} in shaders.
 *
 * @param sort the Q3 sort value (1 portal, 2 sky, 3 opaque, ... 16 nearest)
 * @param stages stages in draw order; empty for sky-only, fog-only or nodraw materials
 * @param sky sky box and cloud layer parameters; the stages are the cloud layers
 * @param fog fog volume parameters when this material defines a fog volume
 * @param clampTime seconds after which shader time stops advancing; 0 for none
 * @param fogPass true when an opaque material inside a fog volume receives a separate fog pass
 * @param dynamicLightPass true when dynamic lights are added in a separate additive pass
 * @param unexpressed features of the source shader that the program encoding could not keep (should
 *     be empty; listed so diagnostics can count them)
 */
public record MaterialProgram(
    String name,
    float sort,
    CullMode cull,
    boolean polygonOffset,
    List<StageProgram> stages,
    List<DeformProgram> deforms,
    Optional<SkyParameters> sky,
    Optional<FogParameters> fog,
    boolean portal,
    boolean noDraw,
    float clampTime,
    boolean fogPass,
    boolean dynamicLightPass,
    List<String> unexpressed) {

  /** Face culling, from the shader's {@code cull} directive. */
  public enum CullMode {
    /** Draw faces whose Q3 winding faces the viewer ({@code cull front}, the default). */
    FRONT_SIDED,
    /** Draw only the reverse side ({@code cull back}). */
    BACK_SIDED,
    /** Draw both sides ({@code cull none}). */
    TWO_SIDED;

    public int code() {
      return ordinal();
    }
  }

  /** {@code skyparms}: box image prefixes ({@code -} for none) and cloud height. */
  public record SkyParameters(String farBox, float cloudHeight, String nearBox) {
    public SkyParameters {
      Objects.requireNonNull(farBox, "farBox");
      Objects.requireNonNull(nearBox, "nearBox");
    }
  }

  /** {@code fogparms}: colour (0..1) and the distance at which fog becomes opaque. */
  public record FogParameters(Vec3f color, float depthForOpaque) {
    public FogParameters {
      Objects.requireNonNull(color, "color");
    }
  }

  public MaterialProgram {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(cull, "cull");
    stages = List.copyOf(stages);
    deforms = List.copyOf(deforms);
    Objects.requireNonNull(sky, "sky");
    Objects.requireNonNull(fog, "fog");
    unexpressed = List.copyOf(unexpressed);
  }

  public boolean isSky() {
    return sky.isPresent();
  }

  /** True when the first stage is opaque, so later passes can rely on depth equality. */
  public boolean opaque() {
    return !stages.isEmpty() && stages.getFirst().state().opaque();
  }

  /** True when any deform must be applied on the CPU (autosprite, text, projection shadow). */
  public boolean needsCpuDeform() {
    for (DeformProgram deform : deforms) if (!deform.op().gpu()) return true;
    return false;
  }

  /** True when deformed geometry moves, so bounds-based culling is not conservative. */
  public boolean deformsGeometry() {
    return !deforms.isEmpty();
  }

  /** True when any stage or deform changes with time, entity, view or lighting state. */
  public boolean dynamic() {
    for (StageProgram stage : stages) if (stage.shading().dynamic()) return true;
    for (DeformProgram deform : deforms) if (deform.dynamic()) return true;
    return false;
  }

  /** True when any stage reads entity colour, alpha or texture offset. */
  public boolean entityDependent() {
    for (StageProgram stage : stages) if (stage.shading().entityDependent()) return true;
    return false;
  }

  /** Shader time after {@link #clampTime}. */
  public double shaderTime(double seconds) {
    return clampTime > 0 ? Math.min(seconds, clampTime) : seconds;
  }

  /**
   * A 16-bit draw-order rank from {@link #sort}: materials draw in ascending rank, and translucent
   * ranks (above opaque) are further ordered back to front by the backend.
   */
  public int sortRank() {
    return Math.clamp(Math.round(sort * 64), 0, 0xffff);
  }
}

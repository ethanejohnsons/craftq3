package dev.bluevista.craftq3.mc.fabric.client.render;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.render.DynamicLight;
import dev.bluevista.craftq3.engine.render.FogVolumes;
import dev.bluevista.craftq3.engine.render.material.MaterialProgram;
import dev.bluevista.craftq3.engine.render.material.ProgramLayout;
import dev.bluevista.craftq3.engine.render.material.StageProgram;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * std140 writers for the uniform blocks of {@code q3_material} ({@code q3_material.glsl}). All
 * blocks are plain float arrays; positions are absolute within the given buffer.
 */
final class Q3Blocks {
  static final int VIEW_BYTES = 64 + 4 * 16 + 64 * 16;
  static final int DRAW_BYTES = 64 + 5 * 16;
  static final int STAGE_FLOATS = 32 * 4 + 10 * 4 + 4;
  static final int STAGE_BYTES = STAGE_FLOATS * 4;
  static final int FOG_BYTES = 16 + 32 * 16 + 128 * 16;
  static final int MAX_LIGHTS = 32;
  static final int MAX_FOG_VOLUMES = 16;
  static final int MAX_FOG_PLANES = 128;

  /** Pass flags in stage float 126 (see the shader's FLAG_* constants). */
  static final int FLAG_LIGHTMAP = 1, FLAG_SCREEN = 2, FLAG_FOG_PASS = 4, FLAG_DLIGHT = 8;

  private static final int FLAGS = 126;
  private static final int DEFORMS = 128;
  private static final int CLAMP_TIME = 168;

  /** Draw flag bits in {@code LightDir.w}. */
  static final int DRAW_DEPTH_HACK = 1;

  private Q3Blocks() {}

  /** The static block of one stage of {@code material}, or of a synthetic pass when null. */
  static float[] stage(MaterialProgram material, StageProgram stage, int flags) {
    float[] out = new float[STAGE_FLOATS];
    if (stage != null) ProgramLayout.packStage(stage, out, 0);
    out[FLAGS] = flags;
    ProgramLayout.packDeforms(
        material.deforms().size() > ProgramLayout.MAX_DEFORMS
            ? material.deforms().subList(0, ProgramLayout.MAX_DEFORMS)
            : material.deforms(),
        out,
        DEFORMS);
    out[CLAMP_TIME] = material.clampTime();
    return out;
  }

  static void putFloats(ByteBuffer out, int at, float[] values) {
    for (int i = 0; i < values.length; i++) out.putFloat(at + i * 4, values[i]);
  }

  /** {@code Q3View}. {@code viewProj} is column-major. */
  static void view(
      ByteBuffer out,
      int at,
      float[] viewProj,
      Vec3f eye,
      float seconds,
      float[] clipPlane,
      float invWidth,
      float invHeight,
      List<DynamicLight> lights) {
    for (int i = 0; i < 16; i++) out.putFloat(at + i * 4, viewProj[i]);
    int p = at + 64;
    vec4(out, p, eye.x(), eye.y(), eye.z(), seconds);
    if (clipPlane != null)
      vec4(out, p + 16, clipPlane[0], clipPlane[1], clipPlane[2], clipPlane[3]);
    else vec4(out, p + 16, 0, 0, 0, 0);
    vec4(out, p + 32, invWidth, invHeight, clipPlane != null ? 1 : 0, 0);
    int count = Math.min(MAX_LIGHTS, lights.size());
    vec4(out, p + 48, count, 0, 0, 0);
    int l = p + 64;
    for (int i = 0; i < MAX_LIGHTS; i++) {
      if (i < count) {
        DynamicLight light = lights.get(i);
        vec4(out, l, light.origin().x(), light.origin().y(), light.origin().z(), light.radius());
        vec4(out, l + 16, light.color().x(), light.color().y(), light.color().z(), 0);
      } else {
        vec4(out, l, 0, 0, 0, 0);
        vec4(out, l + 16, 0, 0, 0, 0);
      }
      l += 32;
    }
  }

  /**
   * {@code Q3Draw}. {@code model} is column-major or null for identity; colours are 0..1.
   *
   * @param timeOffset seconds subtracted from the view time for this draw's shaders
   */
  static void draw(
      ByteBuffer out,
      int at,
      float[] model,
      int rgba,
      Vec3f ambient,
      Vec3f directed,
      Vec3f lightDirection,
      float timeOffset,
      int fogVolume,
      int flags,
      float shaderS,
      float shaderT) {
    if (model == null) {
      for (int i = 0; i < 16; i++) out.putFloat(at + i * 4, i % 5 == 0 ? 1 : 0);
    } else {
      for (int i = 0; i < 16; i++) out.putFloat(at + i * 4, model[i]);
    }
    int p = at + 64;
    vec4(
        out,
        p,
        (rgba >>> 24) / 255f,
        ((rgba >>> 16) & 255) / 255f,
        ((rgba >>> 8) & 255) / 255f,
        (rgba & 255) / 255f);
    vec4(out, p + 16, ambient.x(), ambient.y(), ambient.z(), timeOffset);
    vec4(out, p + 32, directed.x(), directed.y(), directed.z(), fogVolume);
    vec4(out, p + 48, lightDirection.x(), lightDirection.y(), lightDirection.z(), flags);
    vec4(out, p + 64, shaderS, shaderT, 0, 0);
  }

  /** {@code Q3Fog}: the first {@link #MAX_FOG_VOLUMES} volumes whose planes fit. */
  static void fog(ByteBuffer out, int at, FogVolumes fog) {
    for (int i = 0; i < FOG_BYTES; i += 4) out.putFloat(at + i, 0);
    int volumes = 0, planes = 0;
    for (FogVolumes.Volume volume : fog.volumes()) {
      if (volumes == MAX_FOG_VOLUMES || planes + volume.planeCount() > MAX_FOG_PLANES) break;
      int v = at + 16 + volumes * 32;
      vec4(
          out,
          v,
          volume.color().x(),
          volume.color().y(),
          volume.color().z(),
          volume.depthForOpaque());
      vec4(out, v + 16, planes, volume.planeCount(), 0, 0);
      float[] packed = volume.planes();
      for (int i = 0; i < packed.length; i++)
        out.putFloat(at + 16 + 32 * 16 + planes * 16 + i * 4, packed[i]);
      planes += volume.planeCount();
      volumes++;
    }
    vec4(out, at, volumes, 0, 0, 0);
  }

  static void vec4(ByteBuffer out, int at, float x, float y, float z, float w) {
    out.putFloat(at, x);
    out.putFloat(at + 4, y);
    out.putFloat(at + 8, z);
    out.putFloat(at + 12, w);
  }
}

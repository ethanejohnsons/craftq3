package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.BlendFactor;
import java.util.Locale;

/**
 * The blend states a Q3 stage can select. Blend state is fixed per {@code RenderPipeline} in
 * Blaze3D, so each value is one registered pipeline variant of the same {@code q3_stage} program.
 */
public enum Q3Blend {
  /** No {@code blendFunc}: opaque replace. */
  OPAQUE(null),
  /** {@code blendFunc blend}: GL_SRC_ALPHA GL_ONE_MINUS_SRC_ALPHA. */
  BLEND(
      new BlendFunction(
          BlendFactor.SRC_ALPHA,
          BlendFactor.ONE_MINUS_SRC_ALPHA,
          BlendFactor.ONE,
          BlendFactor.ONE_MINUS_SRC_ALPHA)),
  /** {@code blendFunc add}: GL_ONE GL_ONE. */
  ADD(new BlendFunction(BlendFactor.ONE, BlendFactor.ONE)),
  /** {@code blendFunc filter}: GL_DST_COLOR GL_ZERO (lightmap-style modulate). */
  FILTER(
      new BlendFunction(
          BlendFactor.DST_COLOR, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ONE));

  private final BlendFunction function;

  Q3Blend(BlendFunction function) {
    this.function = function;
  }

  ColorTargetState colorTarget() {
    return function == null ? ColorTargetState.DEFAULT : new ColorTargetState(function);
  }

  String id() {
    return name().toLowerCase(Locale.ROOT);
  }
}

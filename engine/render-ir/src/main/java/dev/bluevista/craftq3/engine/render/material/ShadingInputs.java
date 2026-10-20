package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;

/**
 * The per-draw uniforms a stage program reads. Colours and light intensities are normalised to
 * 0..1; {@code lightDirection} points from the surface toward the light.
 *
 * @param viewOrigin the eye position (environment mapping, portal alpha, specular)
 * @param seconds shader time after the entity's shader-time offset and the material's clamp
 * @param entityTexOffset the entity's shader texture-coordinate offset in x (s) and y (t)
 * @param identityLight the overbright scale applied by {@code identityLighting} and {@code vertex}
 */
public record ShadingInputs(
    Vec3f viewOrigin,
    double seconds,
    Vec3f entityColor,
    float entityAlpha,
    Vec3f lightDirection,
    Vec3f ambientLight,
    Vec3f directedLight,
    Vec3f fogColor,
    Vec3f entityTexOffset,
    float identityLight) {
  public static final Vec3f DEFAULT_AMBIENT = new Vec3f(0.25f, 0.25f, 0.25f);
  public static final Vec3f DEFAULT_DIRECTED = new Vec3f(0.75f, 0.75f, 0.75f);
  public static final Vec3f UP = new Vec3f(0, 0, 1);
  public static final Vec3f WHITE = new Vec3f(1, 1, 1);

  public ShadingInputs {
    Objects.requireNonNull(viewOrigin, "viewOrigin");
    Objects.requireNonNull(entityColor, "entityColor");
    Objects.requireNonNull(lightDirection, "lightDirection");
    Objects.requireNonNull(ambientLight, "ambientLight");
    Objects.requireNonNull(directedLight, "directedLight");
    Objects.requireNonNull(fogColor, "fogColor");
    Objects.requireNonNull(entityTexOffset, "entityTexOffset");
    if (!Double.isFinite(seconds) || !Float.isFinite(entityAlpha) || !Float.isFinite(identityLight))
      throw new IllegalArgumentException("Non-finite shader evaluation inputs");
  }

  /** World-surface defaults: white entity, overhead light, no fog, identity light 1. */
  public static ShadingInputs defaults(Vec3f viewOrigin, double seconds) {
    return new ShadingInputs(
        viewOrigin,
        seconds,
        WHITE,
        1,
        UP,
        DEFAULT_AMBIENT,
        DEFAULT_DIRECTED,
        Vec3f.ZERO,
        Vec3f.ZERO,
        1);
  }

  public ShadingInputs withSeconds(double value) {
    return new ShadingInputs(
        viewOrigin,
        value,
        entityColor,
        entityAlpha,
        lightDirection,
        ambientLight,
        directedLight,
        fogColor,
        entityTexOffset,
        identityLight);
  }

  public ShadingInputs withLighting(Vec3f direction, Vec3f ambient, Vec3f directed) {
    return new ShadingInputs(
        viewOrigin,
        seconds,
        entityColor,
        entityAlpha,
        direction,
        ambient,
        directed,
        fogColor,
        entityTexOffset,
        identityLight);
  }
}

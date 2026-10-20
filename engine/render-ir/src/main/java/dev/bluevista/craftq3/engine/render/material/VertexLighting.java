package dev.bluevista.craftq3.engine.render.material;

/**
 * Per-vertex light for {@code rgbGen lightingDiffuse} and {@code alphaGen lightingSpecular}, used
 * by the CPU evaluator when lighting varies across a draw (light-grid world surfaces, dynamic
 * lights).
 */
@FunctionalInterface
public interface VertexLighting {
  /**
   * Writes ambient RGB to {@code out[0..2]}, directed RGB to {@code out[3..5]} and the unit
   * direction toward the light to {@code out[6..8]}; {@code out} arrives holding the draw's uniform
   * lighting, so an implementation may adjust it in place.
   */
  void sample(float px, float py, float pz, float nx, float ny, float nz, float[] out);
}

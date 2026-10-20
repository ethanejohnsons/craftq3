package dev.bluevista.craftq3.engine.render.material;

/** Per-vertex fog colour for {@code rgbGen fog}, used by the CPU evaluator. */
@FunctionalInterface
public interface VertexFog {
  /** Writes the fog colour (0..1) seen at the point to {@code rgbOut[0..2]}. */
  void color(float px, float py, float pz, float[] rgbOut);
}

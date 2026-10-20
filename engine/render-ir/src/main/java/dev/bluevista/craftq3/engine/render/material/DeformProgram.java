package dev.bluevista.craftq3.engine.render.material;

import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition.Wave;
import java.util.Objects;

/**
 * One {@code deformVertexes} operation as data. Per-vertex operations ({@link Op#gpu()}) can run in
 * a vertex shader from the time uniform; the others need whole quads or entity state and are
 * applied on the CPU by {@link VertexDeformer}.
 *
 * @param wave the waveform of {@link Op#WAVE} and {@link Op#MOVE}
 * @param p0 WAVE: divisor (the phase advances {@code (x+y+z)/divisor} cycles; 0 means a spread of
 *     100 cycles per unit); NORMAL: amplitude; BULGE: width; MOVE: x direction; TEXT: text slot
 *     (0..7)
 * @param p1 NORMAL: frequency; BULGE: height; MOVE: y direction
 * @param p2 BULGE: speed; MOVE: z direction
 */
public record DeformProgram(Op op, Wave wave, float p0, float p1, float p2) {
  /** Deform kinds, in their packed {@code code()} order. */
  public enum Op {
    WAVE(true),
    NORMAL(true),
    BULGE(true),
    MOVE(true),
    AUTOSPRITE(false),
    AUTOSPRITE2(false),
    PROJECTION_SHADOW(false),
    TEXT(false);

    private final boolean gpu;

    Op(boolean gpu) {
      this.gpu = gpu;
    }

    public int code() {
      return ordinal();
    }

    /** True when a vertex shader can evaluate the operation one vertex at a time. */
    public boolean gpu() {
      return gpu;
    }
  }

  public DeformProgram {
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(wave, "wave");
  }

  /** True when the deformed positions or normals change over time or with the view. */
  public boolean dynamic() {
    return switch (op) {
      case WAVE, MOVE -> wave.amplitude() != 0 && wave.frequency() != 0;
      case NORMAL -> p0 != 0 && p1 != 0;
      case BULGE -> p1 != 0 && p2 != 0;
      case AUTOSPRITE, AUTOSPRITE2, PROJECTION_SHADOW, TEXT -> true;
    };
  }
}

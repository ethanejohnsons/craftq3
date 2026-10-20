package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Objects;
import java.util.function.Function;

/** Measured type-5 run-up prediction request and hazardous-stop fallback. */
public final class JumpRunStart {
  private final Function<AasMovementPredictor.Request, AasMovementPredictor.Prediction> predictor;

  /** The provider must support gap event 64 and retain its own physics settings and budgets. */
  public JumpRunStart(
      Function<AasMovementPredictor.Request, AasMovementPredictor.Prediction> predictor) {
    this.predictor = Objects.requireNonNull(predictor);
  }

  public Vec3f calculate(Vec3f start, Vec3f end) {
    start = Objects.requireNonNull(start);
    end = Objects.requireNonNull(end);
    float x = start.x() - end.x();
    float y = start.y() - end.y();
    float squared = x * x + y * y;
    if (!Float.isFinite(squared))
      throw new IllegalArgumentException("Jump run-up direction exceeds float range");
    // A zero squared length also covers subnormal deltas; the native call retains that delta.
    if (squared != 0) {
      float inverse = 1 / (float) Math.sqrt(squared);
      x *= inverse;
      y *= inverse;
    }
    Vec3f origin = new Vec3f(start.x(), start.y(), start.z() + 1);
    var request =
        new AasMovementPredictor.Request(
            -1, origin, 2, true, Vec3f.ZERO, new Vec3f(x * 400, y * 400, 0), 1, 2, .1f, 124);
    var prediction = Objects.requireNonNull(predictor.apply(request));
    return (prediction.stopEvent() & 56) != 0
        ? origin
        : Objects.requireNonNull(prediction.endPosition());
  }
}

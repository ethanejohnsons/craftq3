package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.function.Function;

/** Measured type-5 run-up prediction request and hazardous-stop fallback. */
public final class JumpRunStart {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final Function<AasMovementPredictor.Request, AasMovementPredictor.Prediction> predictor;

  /** The provider must support gap event 64 and retain its own physics settings and budgets. */
  public JumpRunStart(
      Function<AasMovementPredictor.Request, AasMovementPredictor.Prediction> predictor) {
    this.predictor = Objects.requireNonNull(predictor);
  }

  public Vec3 calculate(Vec3 start, Vec3 end) {
    start = MovementAbi.vector(start);
    end = MovementAbi.vector(end);
    float x = (float) start.x() - (float) end.x();
    float y = (float) start.y() - (float) end.y();
    float squared = x * x + y * y;
    if (!Float.isFinite(squared))
      throw new IllegalArgumentException("Jump run-up direction exceeds float range");
    // A zero squared length also covers subnormal deltas; the native call retains that delta.
    if (squared != 0) {
      float inverse = 1 / (float) Math.sqrt(squared);
      x *= inverse;
      y *= inverse;
    }
    Vec3 origin = new Vec3(start.x(), start.y(), (float) start.z() + 1);
    var request =
        new AasMovementPredictor.Request(
            -1, origin, 2, true, ZERO, new Vec3(x * 400, y * 400, 0), 1, 2, .1f, 124);
    var prediction = Objects.requireNonNull(predictor.apply(request));
    return (prediction.stopEvent() & 56) != 0
        ? origin
        : MovementAbi.vector(prediction.endPosition());
  }
}

package dev.bluevista.craftq3.engine.botlib.movement;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class JumpRunStartTest {
  private static final Vec3f ZERO = new Vec3f(0, 0, 0);

  @Test
  void requestsOneBackwardCommandAndTwoPredictionFramesIncludingGapDetection() {
    var requests = new ArrayList<AasMovementPredictor.Request>();
    var target = new Vec3f(-17.25f, 12.125f, 33.5f);
    var run =
        new JumpRunStart(
            request -> {
              requests.add(request);
              return result(target, 0);
            });
    assertEquals(target, run.calculate(new Vec3f(3, 4, 5), new Vec3f(303, 404, 505)));
    assertEquals(
        new AasMovementPredictor.Request(
            -1,
            new Vec3f(3, 4, 6),
            2,
            true,
            ZERO,
            new Vec3f(-240.00001525878906f, -320, 0),
            1,
            2,
            .1f,
            124),
        requests.getFirst());
    assertEquals(1, requests.size());
  }

  @Test
  void rejectsSlimeLavaAndDamageWhileAcceptingWaterAndGapEndpoints() {
    Vec3f start = new Vec3f(3, 4, 5), target = new Vec3f(-17.25f, 12.125f, 33.5f);
    for (int event = 0; event < 256; event++) {
      int stop = event;
      var run = new JumpRunStart(request -> result(target, stop));
      assertEquals(
          (event & 56) == 0 ? target : new Vec3f(3, 4, 6),
          run.calculate(start, ZERO),
          "event " + event);
    }
  }

  @Test
  void verticalAndCoincidentReachesRetainZeroHorizontalCommand() {
    var requests = new ArrayList<AasMovementPredictor.Request>();
    var run =
        new JumpRunStart(
            request -> {
              requests.add(request);
              return result(request.origin(), 64);
            });
    assertEquals(new Vec3f(3, 4, 6), run.calculate(new Vec3f(3, 4, 5), new Vec3f(3, 4, 555)));
    run.calculate(ZERO, ZERO);
    for (var request : requests) assertEquals(ZERO, request.commandMove());
  }

  @Test
  void squaredLengthUnderflowRetainsTheNativeTinyCommand() {
    var commands = new ArrayList<Vec3f>();
    var run =
        new JumpRunStart(
            request -> {
              commands.add(request.commandMove());
              return result(ZERO, 0);
            });
    run.calculate(ZERO, new Vec3f(1e-30f, 0, 0));
    assertEquals(new Vec3f(-4.0000000126843074e-28f, 0, 0), commands.getFirst());
  }

  @Test
  void arithmeticAndProviderFailuresAreExplicit() {
    var run =
        new JumpRunStart(
            request -> {
              fail("Invalid input reached prediction");
              return null;
            });
    assertThrows(IllegalArgumentException.class, () -> run.calculate(ZERO, new Vec3f(1e30f, 0, 0)));
    assertThrows(
        IllegalArgumentException.class,
        () -> run.calculate(new Vec3f(-3e38f, 0, 0), new Vec3f(3e38f, 0, 0)));
    var unsupported = new UnsupportedOperationException("Gap prediction unavailable");
    var failing =
        new JumpRunStart(
            request -> {
              throw unsupported;
            });
    assertSame(
        unsupported,
        assertThrows(UnsupportedOperationException.class, () -> failing.calculate(ZERO, ZERO)));
  }

  private static AasMovementPredictor.Prediction result(Vec3f end, int event) {
    return new AasMovementPredictor.Prediction(end, 1, ZERO, 2, event, 0, 0, 0, Optional.empty());
  }
}

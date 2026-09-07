import dev.bluevista.craftq3.assets.aas.*;
import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.goal.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.*;
import java.util.*;
import java.util.zip.*;

/**
 * Captured retail type-5 request against native-measured expectations, with clear BSP callbacks.
 */
class AuditJumpCapture {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("AuditJumpCapture <pak0.pk3>");
    try (var zip = new ZipFile(args[0])) {
      var map =
          AasReader.read(zip.getInputStream(zip.getEntry("maps/q3tourney4.aas")).readAllBytes());
      var nav = new AasNavigation(map);
      int[] traces = {0};
      TraceWorld world =
          new TraceWorld() {
            public TraceResult trace(TraceRequest r) {
              traces[0]++;
              return TraceResult.clear(r);
            }

            public int pointContents(Vec3 p, int mask, int entity) {
              return 0;
            }
          };
      var aasWorld = new AasMovementWorld(nav, (e, r) -> TraceResult.clear(r), p -> 0);
      var predictor = new AasMovementPredictor(aasWorld, AasMovementPredictor.Settings.defaults());
      var ground =
          new GroundReachMovement(
              world,
              a -> map.areaSettings().get(a).presenceType(),
              a -> map.areaSettings().get(a).reachabilityCount(),
              new MovementObstacles(aasWorld)::gapDistance);
      var run =
          new JumpRunStart(
              r -> {
                var p = predictor.predict(r).orElseThrow();
                System.out.println("RUN " + r + " -> " + p);
                return p;
              });
      var jump = new JumpReachMovement(run::calculate, nav::pointArea);
      try (var states =
          new BotMovement(
              s -> {
                throw new AssertionError(s);
              })) {
        int h = states.allocate();
        states.initialize(
            h,
            new MovementInit(
                new Vec3(51.91644287109375, -695.3133544921875, 472.1252746582031),
                new Vec3(114, 98, 0),
                new Vec3(51.91644287109375, -695.3133544921875, 498.1252746582031),
                1,
                1,
                .1f,
                2,
                new Vec3(356.4569091796875, 113.4283447265625, 0),
                2));
        states.updateHistory(
            h,
            new BotMovement.History(
                675,
                675,
                666,
                584,
                675,
                0,
                51.85f,
                new Vec3(48.87639236450195, -706.1217651367188, 472.1252746582031)));
        states.recordReachAttempt(h, 545, 6, 45.35f);
        var service =
            new GroundMoveToGoal(
                states,
                nav,
                new AasReachabilityArea(nav, List.of(), world, e -> Optional.empty())::fuzzyArea,
                new AasMovementRoutes(new AasRouteTimes(map)),
                world,
                predictor::onGround,
                e -> false,
                ground,
                Map.of(),
                Map.of(),
                (i, f, a, g) -> {
                  throw new AssertionError("liquid");
                },
                Map.of(5, jump::execute),
                Map.of(5, jump::finish));
        var out =
            service.execute(
                h,
                new Goal(
                    new Vec3(0, -450, 492.25),
                    666,
                    new Vec3(-15, -15, -15),
                    new Vec3(15, 15, 15),
                    149,
                    9,
                    1,
                    11),
                TravelPolicy.ofFlags(18616254),
                47.15f);
        System.out.println("OUTPUT " + out);
        System.out.println("STATE " + states.snapshot(h));
        var direction = new Vec3(Float.parseFloat(".539073169"), Float.parseFloat(".842258930"), 0);
        var expected =
            new GroundMoveToGoal.Output(
                new MovementResult(0, 0, 0, 0, 5, 0, 0, direction, new Vec3(0, 0, 0)),
                52,
                Optional.of(new GroundMoveToGoal.Command(direction, 400, 0)),
                0);
        if (!out.equals(expected) || traces[0] != 1) throw new AssertionError(out);
        var snapshot = states.snapshot(h).orElseThrow();
        var expectedHistory =
            new BotMovement.History(
                671,
                671,
                666,
                579,
                671,
                0,
                52.15f,
                new Vec3(51.91644287109375, -695.3133544921875, 472.1252746582031));
        if (!snapshot.history().equals(expectedHistory)
            || snapshot.movementFlags() != 2
            || !snapshot.reachAvoidance().equals(new BotMovement.ReachAvoidance(545, 51.35f, 1)))
          throw new AssertionError(snapshot);
        System.out.println(
            "CAPTURE PASS q3tourney4 retail 47.15: native result, speed, action absence and route"
                + " history exact");
      }
    }
  }
}

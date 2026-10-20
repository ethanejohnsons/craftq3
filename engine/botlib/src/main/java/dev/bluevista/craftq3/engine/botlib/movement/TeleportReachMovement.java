package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
import java.util.Objects;

/** Native-observed approach commands for TELEPORT reaches; the original VM owns teleportation. */
public final class TeleportReachMovement {
  private final MovementObstruction obstruction;

  public TeleportReachMovement(MovementObstruction obstruction) {
    this.obstruction = Objects.requireNonNull(obstruction);
  }

  public GroundReachMovement.Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (!reach.is(TravelType.TELEPORT))
      throw new IllegalArgumentException("Non-teleport reach execution");
    if ((effectiveFlags & 32) != 0)
      throw new UnsupportedOperationException(
          "Teleported entry has no movement command; the caller must preserve absent actions");
    boolean swimming = (effectiveFlags & 4) != 0;
    float x = reach.start().x() - input.origin().x();
    float y = reach.start().y() - input.origin().y();
    float z = swimming ? reach.start().z() - input.origin().z() : 0;
    float squared = x * x + y * y + z * z;
    float root = (float) Math.sqrt(squared);
    if (!Float.isFinite(root))
      throw new IllegalArgumentException("Teleport direction exceeds float range");
    float inverse = root == 0 ? 0 : 1 / root;
    Vec3f direction = new Vec3f(x * inverse, y * inverse, z * inverse);
    var blocked = obstruction.check(input, sourceArea, direction);
    var result =
        new MovementResult(
            0,
            0,
            blocked.blocked(),
            blocked.blockEntity(),
            0,
            blocked.flags() | (swimming ? 2 : 0),
            0,
            direction,
            Vec3f.ZERO);
    return new GroundReachMovement.Output(result, direction, squared * inverse < 30 ? 200 : 400, 0);
  }
}

package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;

/** Native-observed approach commands for TELEPORT reaches; the original VM owns teleportation. */
public final class TeleportReachMovement {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private final MovementObstruction obstruction;

  public TeleportReachMovement(MovementObstruction obstruction) {
    this.obstruction = Objects.requireNonNull(obstruction);
  }

  public GroundReachMovement.Output execute(
      MovementInit input, int effectiveFlags, int sourceArea, Reachability reach) {
    Objects.requireNonNull(input);
    Objects.requireNonNull(reach);
    if (reach.baseTravelType() != 10)
      throw new UnsupportedOperationException("Non-teleport reach execution");
    if ((effectiveFlags & 32) != 0)
      throw new UnsupportedOperationException(
          "Teleported entry has no movement command; the caller must preserve absent actions");
    boolean swimming = (effectiveFlags & 4) != 0;
    float x = (float) reach.start().x() - (float) input.origin().x();
    float y = (float) reach.start().y() - (float) input.origin().y();
    float z = swimming ? (float) reach.start().z() - (float) input.origin().z() : 0;
    float squared = x * x + y * y + z * z;
    float root = (float) Math.sqrt(squared);
    if (!Float.isFinite(root))
      throw new IllegalArgumentException("Teleport direction exceeds float range");
    float inverse = root == 0 ? 0 : 1 / root;
    Vec3 direction = new Vec3(x * inverse, y * inverse, z * inverse);
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
            ZERO);
    return new GroundReachMovement.Output(result, direction, squared * inverse < 30 ? 200 : 400, 0);
  }
}

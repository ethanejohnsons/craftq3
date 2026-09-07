package dev.bluevista.craftq3.botlib.movement;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.botlib.aas.AasAreaTrace;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;

/** Native-observed airborne jump-pad contact lookup; performs no movement or state mutation. */
public final class JumpPadContact {
  public record Contact(int area, int reachability) {
    public Contact {
      if (area < 1 || reachability < 1)
        throw new IllegalArgumentException("Invalid jump-pad contact");
    }
  }

  private final AasMap map;
  private final int maxNodeVisits, maxReachVisits;

  public JumpPadContact(AasMap map) {
    this(map, 2_000_000, 2_000_000);
  }

  public JumpPadContact(AasMap map, int maxNodeVisits, int maxReachVisits) {
    this.map = Objects.requireNonNull(map);
    if (maxNodeVisits < 1
        || maxNodeVisits > 100_000_000
        || maxReachVisits < 1
        || maxReachVisits > 100_000_000)
      throw new IllegalArgumentException("Invalid jump-pad contact work limits");
    this.maxNodeVisits = maxNodeVisits;
    this.maxReachVisits = maxReachVisits;
  }

  /** First contacted pad with a usable link wins; its last matching link is selected. */
  public Optional<Contact> find(Vec3 origin, Vec3 velocity) {
    origin = MovementAbi.vector(origin);
    velocity = MovementAbi.vector(velocity);
    var previous =
        new Vec3(
            (float) origin.x() - .2f * (float) velocity.x(),
            (float) origin.y() - .2f * (float) velocity.y(),
            (float) origin.z() - .2f * (float) velocity.z());
    var areas = AasAreaTrace.trace(map, origin, previous, 16, maxNodeVisits);
    int work = 0;
    for (var entry : areas) {
      var settings = map.areaSettings().get(entry.area());
      if ((settings.contents() & 128) == 0 || settings.firstReachability() == 0) continue;
      int first = settings.firstReachability();
      // Native's public reach iterator emits a nonzero first index even for an empty count.
      long claimedEnd = (long) first + settings.reachabilityCount();
      if (first < 0 || settings.reachabilityCount() < 0 || claimedEnd > map.reachabilities().size())
        throw new IllegalArgumentException("Invalid jump-pad reachability range");
      // Empty ranges may start at the array end; the native lookup yields no usable link there.
      long end =
          Math.min(
              map.reachabilities().size(),
              (long) first + Math.max(1, settings.reachabilityCount()));
      int selected = 0;
      for (int index = first; index < end; index++) {
        if (work++ == maxReachVisits)
          throw new IllegalStateException("Jump-pad contact reach budget exceeded");
        if (map.reachabilities().get(index).baseTravelType() == 18) selected = index;
      }
      if (selected != 0) return Optional.of(new Contact(entry.area(), selected));
    }
    return Optional.empty();
  }
}

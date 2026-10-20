package dev.bluevista.craftq3.engine.botlib.movement;

import dev.bluevista.craftq3.engine.botlib.aas.AasAreaTrace;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.aas.TravelType;
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

  private final AasWorld map;
  private final int maxNodeVisits, maxReachVisits;

  public JumpPadContact(AasWorld map) {
    this(map, 2_000_000, 2_000_000);
  }

  public JumpPadContact(AasWorld map, int maxNodeVisits, int maxReachVisits) {
    this.map = Objects.requireNonNull(map);
    if (maxNodeVisits < 1
        || maxNodeVisits > 100_000_000
        || maxReachVisits < 1
        || maxReachVisits > 100_000_000)
      throw new IllegalArgumentException("Invalid jump-pad contact work limits");
    this.maxNodeVisits = maxNodeVisits;
    this.maxReachVisits = maxReachVisits;
  }

  /** Looks back over the default two think frames of 0.1 seconds. */
  public Optional<Contact> find(Vec3f origin, Vec3f velocity) {
    return find(origin, velocity, .2f);
  }

  /**
   * First contacted pad with a usable link wins; its last matching link is selected. The trace runs
   * from the origin back along the velocity for {@code span} seconds (twice the think time).
   */
  public Optional<Contact> find(Vec3f origin, Vec3f velocity, float span) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(velocity);
    var previous =
        new Vec3f(
            origin.x() - span * velocity.x(),
            origin.y() - span * velocity.y(),
            origin.z() - span * velocity.z());
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
        if (map.reachabilities().get(index).is(TravelType.JUMP_PAD)) selected = index;
      }
      if (selected != 0) return Optional.of(new Contact(entry.area(), selected));
    }
    return Optional.empty();
  }
}

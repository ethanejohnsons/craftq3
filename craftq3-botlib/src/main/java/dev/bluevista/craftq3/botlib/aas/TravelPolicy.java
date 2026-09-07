package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap.AreaSettings;
import dev.bluevista.craftq3.assets.aas.AasMap.Reachability;
import java.util.Objects;
import java.util.Set;

/** Immutable routing permission snapshot. A set TFL bit permits that travel or area category. */
public record TravelPolicy(
    int travelFlags, int presenceTypes, Team team, Set<Integer> disabledAreas) {
  public enum Team {
    ANY,
    ONE,
    TWO
  }

  public TravelPolicy {
    Objects.requireNonNull(team);
    disabledAreas = Set.copyOf(disabledAreas);
    if ((presenceTypes & ~6) != 0 || presenceTypes == 0)
      throw new IllegalArgumentException("Presence must contain NORMAL (2) and/or CROUCH (4)");
    if (disabledAreas.stream().anyMatch(area -> area <= 0))
      throw new IllegalArgumentException("Disabled areas must be positive");
  }

  public static TravelPolicy defaults() {
    return ofFlags(TravelFlags.DEFAULT);
  }

  public static TravelPolicy ofFlags(int travelFlags) {
    return new TravelPolicy(travelFlags, 6, Team.ANY, Set.of());
  }

  public boolean permitsArea(int area, AreaSettings settings) {
    if (area <= 0
        || disabledAreas.contains(area)
        || (settings.flags() & 8) != 0
        || (settings.presenceType() & presenceTypes) == 0) return false;
    int contents = settings.contents();
    int flags = 0;
    if ((contents & 1) != 0) flags |= TravelFlags.WATER;
    if ((contents & 2) != 0) flags |= TravelFlags.LAVA;
    if ((contents & 4) != 0) flags |= TravelFlags.SLIME;
    if ((contents & 7) == 0) flags |= TravelFlags.AIR;
    if ((contents & 256) != 0) flags |= TravelFlags.DO_NOT_ENTER;
    if ((settings.flags() & 16) != 0) flags |= TravelFlags.BRIDGE;
    if ((contents & 2048) != 0) flags |= TravelFlags.NOT_TEAM_1;
    if ((contents & 4096) != 0) flags |= TravelFlags.NOT_TEAM_2;
    return permitsFlags(flags);
  }

  public boolean permitsReachability(Reachability reachability) {
    int kind = TravelFlags.forTravelType(reachability.baseTravelType());
    // Do not interpret future metadata as permission to take an unknown route.
    if (kind == 0 || kind == TravelFlags.INVALID || (reachability.travelFlags() & 0xfc000000) != 0)
      return false;
    return permitsFlags(TravelFlags.forReachability(reachability));
  }

  private boolean permitsFlags(int required) {
    if (team == Team.ONE && (required & TravelFlags.NOT_TEAM_1) != 0) return false;
    if (team == Team.TWO && (required & TravelFlags.NOT_TEAM_2) != 0) return false;
    return (required & travelFlags) == required;
  }
}

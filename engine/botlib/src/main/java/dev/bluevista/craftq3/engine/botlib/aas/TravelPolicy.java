package dev.bluevista.craftq3.engine.botlib.aas;

import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.AreaSettings;
import dev.bluevista.craftq3.engine.botlib.aas.AasWorld.Reachability;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable routing permission snapshot. A set TFL bit permits that travel or area category,
 * including {@link TravelFlags#NOT_TEAM_1} and {@link TravelFlags#NOT_TEAM_2} for team-only
 * reachabilities and areas. {@code disabledAreas} and {@code enabledAreas} are the runtime
 * overrides of {@code AAS_EnableRoutingArea} on top of the file's disabled-area flag.
 */
public record TravelPolicy(
    int travelFlags,
    int presenceTypes,
    Team team,
    Set<Integer> disabledAreas,
    Set<Integer> enabledAreas) {
  public enum Team {
    ANY,
    ONE,
    TWO
  }

  public TravelPolicy {
    Objects.requireNonNull(team);
    disabledAreas = Set.copyOf(disabledAreas);
    enabledAreas = Set.copyOf(enabledAreas);
    if ((presenceTypes & ~6) != 0 || presenceTypes == 0)
      throw new IllegalArgumentException("Presence must contain NORMAL (2) and/or CROUCH (4)");
    if (disabledAreas.stream().anyMatch(area -> area <= 0)
        || enabledAreas.stream().anyMatch(area -> area <= 0))
      throw new IllegalArgumentException("Routing area overrides must be positive");
  }

  public TravelPolicy(int travelFlags, int presenceTypes, Team team, Set<Integer> disabledAreas) {
    this(travelFlags, presenceTypes, team, disabledAreas, Set.of());
  }

  public static TravelPolicy defaults() {
    return ofFlags(TravelFlags.DEFAULT);
  }

  public static TravelPolicy ofFlags(int travelFlags) {
    return new TravelPolicy(travelFlags, 6, Team.ANY, Set.of());
  }

  public TravelPolicy withTravelFlags(int flags) {
    return new TravelPolicy(flags, presenceTypes, team, disabledAreas, enabledAreas);
  }

  /** Whether {@code area} is disabled: by the file unless re-enabled, or at run time. */
  public boolean disabled(int area, AreaSettings settings) {
    if (disabledAreas.contains(area)) return true;
    return (settings.flags() & Contents.AREAFLAG_DISABLED) != 0 && !enabledAreas.contains(area);
  }

  public boolean permitsArea(int area, AreaSettings settings) {
    if (area <= 0 || disabled(area, settings) || (settings.presenceType() & presenceTypes) == 0)
      return false;
    return permitsFlags(TravelFlags.forArea(settings));
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

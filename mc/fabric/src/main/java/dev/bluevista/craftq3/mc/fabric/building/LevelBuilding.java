package dev.bluevista.craftq3.mc.fabric.building;

import dev.bluevista.craftq3.mc.hybrid.building.BuildingQueries;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegion;
import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The building regions of one level as its rules see them: an immutable snapshot of each region's
 * status, and the {@link BuildingQueries} of the enabled ones by slot. A new snapshot replaces the
 * old one when a region is placed or finishes loading, so readers on any thread see a consistent
 * set without locking. Levels outside the building dimension hold {@link #NONE}.
 */
public final class LevelBuilding {
  public static final LevelBuilding NONE = new LevelBuilding(List.of());

  private final List<BuildingRegions.Status> statuses;
  private final Map<Integer, BuildingRegions.Enabled> enabled = new HashMap<>();

  public LevelBuilding(List<BuildingRegions.Status> statuses) {
    this.statuses = List.copyOf(statuses);
    for (BuildingRegions.Status s : this.statuses)
      if (s instanceof BuildingRegions.Enabled e) enabled.put(e.region().slot(), e);
  }

  public List<BuildingRegions.Status> statuses() {
    return statuses;
  }

  public List<BuildingRegion> regions() {
    return statuses.stream().map(BuildingRegions.Status::region).toList();
  }

  /** Whether any region's BSP rules are on. */
  public boolean active() {
    return !enabled.isEmpty();
  }

  /** The queries of the enabled region containing Minecraft X {@code x}, or null. */
  public BuildingQueries at(double x) {
    if (enabled.isEmpty()) return null;
    BuildingRegions.Enabled e = enabled.get(BuildingDimension.LAYOUT.slotAt(x));
    return e == null ? null : e.queries();
  }

  /** The enabled region of {@code slot}, if any. */
  public Optional<BuildingRegions.Enabled> enabled(int slot) {
    return Optional.ofNullable(enabled.get(slot));
  }

  /** This snapshot with {@code status} added or replacing the region of the same slot. */
  public LevelBuilding with(BuildingRegions.Status status) {
    List<BuildingRegions.Status> next = new java.util.ArrayList<>();
    for (BuildingRegions.Status s : statuses)
      if (s.region().slot() != status.region().slot()) next.add(s);
    next.add(status);
    next.sort(java.util.Comparator.comparingInt(s -> s.region().slot()));
    return new LevelBuilding(next);
  }
}

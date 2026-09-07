package dev.bluevista.craftq3.botlib.goal;

import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/** Map-authored camp spots and named locations, with native cursor and lookup semantics. */
public final class BotMapGoals {
  public static final int MAX_GOALS = 4096;
  private static final Vec3 MINS = new Vec3(-8, -8, -8), MAXS = new Vec3(8, 8, 8);
  private static final Pattern COORDINATE =
      Pattern.compile("\\s*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");

  public record Location(String name, Goal goal) {
    public Location {
      Objects.requireNonNull(name);
      Objects.requireNonNull(goal);
    }
  }

  public record Camp(int nextCursor, Goal goal) {}

  private final ToIntFunction<Vec3> pointArea;
  private final Consumer<String> diagnostics;
  private List<Goal> camps = List.of();
  private List<Location> locations = List.of();

  public BotMapGoals(ToIntFunction<Vec3> pointArea, Consumer<String> diagnostics) {
    this.pointArea = Objects.requireNonNull(pointArea);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  public void initialize(List<Map<String, String>> entities) {
    if (entities.size() > 65536)
      throw new IllegalArgumentException("Bot BSP entity limit exceeded");
    var newCamps = new ArrayList<Goal>();
    var newLocations = new ArrayList<Location>();
    for (var entity : entities) {
      String type = entity.getOrDefault("classname", "");
      if (!type.equals("info_camp") && !type.equals("target_location")) continue;
      Vec3 origin = origin(entity.getOrDefault("origin", ""));
      int area = pointArea.applyAsInt(origin);
      if (area < 0) throw new IllegalArgumentException("Invalid bot map goal area");
      if (type.equals("info_camp") && area == 0) {
        diagnostics.accept("Camp spot at " + origin + " lies in solid");
        continue;
      }
      if (newCamps.size() + newLocations.size() == MAX_GOALS)
        throw new IllegalArgumentException("Bot map goal limit exceeded");
      Goal goal = new Goal(origin, area, MINS, MAXS, 0, 0, 0, 0);
      if (type.equals("info_camp")) newCamps.add(goal);
      else {
        String name = entity.getOrDefault("message", "");
        newLocations.add(new Location(name.substring(0, Math.min(name.length(), 127)), goal));
      }
    }
    Collections.reverse(newCamps);
    Collections.reverse(newLocations);
    camps = List.copyOf(newCamps);
    locations = List.copyOf(newLocations);
  }

  public List<Goal> camps() {
    return camps;
  }

  public List<Location> locations() {
    return locations;
  }

  /** Nonpositive cursors start at the first camp; success returns the next ordinal cursor. */
  public Optional<Camp> nextCamp(int cursor) {
    int index = Math.max(0, cursor);
    return index < camps.size()
        ? Optional.of(new Camp(index + 1, camps.get(index)))
        : Optional.empty();
  }

  public Optional<Goal> location(String name) {
    Objects.requireNonNull(name);
    return locations.stream()
        .filter(location -> location.name().equalsIgnoreCase(name))
        .map(Location::goal)
        .findFirst();
  }

  private static Vec3 origin(String text) {
    float[] coordinates = new float[3];
    var matcher = COORDINATE.matcher(text);
    int offset = 0;
    for (int i = 0; i < 3; i++) {
      matcher.region(offset, text.length());
      if (!matcher.lookingAt()) break;
      coordinates[i] = Float.parseFloat(matcher.group(1));
      offset = matcher.end();
    }
    return new Vec3(coordinates[0], coordinates[1], coordinates[2]);
  }
}

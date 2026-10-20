package dev.bluevista.craftq3.botlib.item;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.botlib.aas.AasAreaVolume;
import dev.bluevista.craftq3.botlib.aas.AasGoalLocator;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded, native-observed suspended-item approach selection through jump-pad trajectories. */
public final class JumpPadItemAreas implements ItemPlacement.JumpPadResolver {
  private static final Vec3 ZERO = new Vec3(0, 0, 0);
  private static final int MAX_PAD_QUERIES = 4096;
  private final AasNavigation navigation;
  private final AasGoalLocator areas;
  private final AasAreaVolume volumes;
  private final JumpPadLaunch launches;
  private final AasMovementPredictor.World world;
  private final Supplier<AasMovementPredictor.Settings> settings;
  private final List<Integer> triggers;

  public JumpPadItemAreas(
      AasNavigation navigation,
      BspMap bsp,
      AasMovementPredictor.World world,
      Supplier<AasMovementPredictor.Settings> settings,
      Consumer<String> diagnostics) {
    this.navigation = Objects.requireNonNull(navigation);
    this.world = Objects.requireNonNull(world);
    this.settings = Objects.requireNonNull(settings);
    areas = new AasGoalLocator(navigation);
    volumes = new AasAreaVolume(navigation.map());
    launches = new JumpPadLaunch(bsp, world, diagnostics);
    var selected = new ArrayList<Integer>();
    for (int index = 0; index < bsp.entities().size(); index++) {
      if ("trigger_push".equals(bsp.entities().get(index).get("classname"))) selected.add(index);
      if (selected.size() > MAX_PAD_QUERIES)
        throw new IllegalArgumentException("Too many BSP jump pads for item localization");
    }
    triggers = List.copyOf(selected);
  }

  @Override
  public OptionalInt bestArea(Vec3 origin, Vec3 mins, Vec3 maxs) {
    Vec3 minimum = add(origin, mins), maximum = add(origin, maxs);
    if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z())
      throw new IllegalArgumentException("Inverted suspended-item box");
    var configuration = Objects.requireNonNull(settings.get());
    var predictor = new AasMovementPredictor(world, configuration);
    for (int entity : triggers) {
      var possibleLaunch = launches.resolve(entity, configuration.gravity());
      if (possibleLaunch.isEmpty()) continue;
      var launch = possibleLaunch.get();
      var linked = areas.linkedAreas(launch.minimum(), launch.maximum(), 4);
      boolean containsPad = linked.stream().anyMatch(this::jumpPad);
      if (!containsPad) continue;
      var prediction =
          predictor.predictHitBox(
              new AasMovementPredictor.Request(
                  -1, launch.origin(), 2, false, launch.velocity(), ZERO, 0, 30, .1f, 0),
              minimum,
              maximum);
      if (prediction.isEmpty())
        throw new IllegalStateException("Jump-pad target prediction did not complete");
      if (prediction.get().frames() >= 30) continue;
      int best = 0;
      float largest = 0;
      for (int area : linked) {
        if (!jumpPad(area)) continue;
        float volume = volumes.volume(area);
        if (volume >= largest) {
          largest = volume;
          best = area;
        }
      }
      // The first completed early trajectory is decisive, including an all-negative volume set.
      return OptionalInt.of(best);
    }
    return OptionalInt.of(0);
  }

  private boolean jumpPad(int area) {
    return (navigation.map().areaSettings().get(area).contents() & 128) != 0;
  }

  private static Vec3 add(Vec3 origin, Vec3 value) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(value);
    return new Vec3(
        (float) origin.x() + (float) value.x(),
        (float) origin.y() + (float) value.y(),
        (float) origin.z() + (float) value.z());
  }
}

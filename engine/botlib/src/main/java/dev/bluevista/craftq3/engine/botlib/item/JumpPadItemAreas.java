package dev.bluevista.craftq3.engine.botlib.item;

import dev.bluevista.craftq3.engine.botlib.BotWorld;
import dev.bluevista.craftq3.engine.botlib.BspEntities;
import dev.bluevista.craftq3.engine.botlib.aas.AasAreaVolume;
import dev.bluevista.craftq3.engine.botlib.aas.AasGoalLocator;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.botlib.movement.AasMovementPredictor;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Bounded, native-observed suspended-item approach selection through jump-pad trajectories. */
public final class JumpPadItemAreas implements ItemPlacement.JumpPadResolver {
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
      BspEntities entities,
      IntFunction<Optional<BotWorld.ModelBounds>> models,
      AasMovementPredictor.World world,
      Supplier<AasMovementPredictor.Settings> settings,
      Consumer<String> diagnostics) {
    this.navigation = Objects.requireNonNull(navigation);
    this.world = Objects.requireNonNull(world);
    this.settings = Objects.requireNonNull(settings);
    areas = new AasGoalLocator(navigation);
    volumes = new AasAreaVolume(navigation.map());
    launches = new JumpPadLaunch(entities, models, world, diagnostics);
    var selected = new ArrayList<Integer>();
    for (int index = 0; index < entities.count() && selected.size() < MAX_PAD_QUERIES; index++)
      if ("trigger_push".equals(entities.list().get(index).get("classname"))) selected.add(index);
    triggers = List.copyOf(selected);
  }

  @Override
  public OptionalInt bestArea(Vec3f origin, Vec3f mins, Vec3f maxs) {
    Vec3f minimum = add(origin, mins), maximum = add(origin, maxs);
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
                  -1, launch.origin(), 2, false, launch.velocity(), Vec3f.ZERO, 0, 30, .1f, 0),
              minimum,
              maximum);
      // An unresolved trajectory (collision loop exhausted) cannot reach the item.
      if (prediction.isEmpty() || prediction.get().frames() >= 30) continue;
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

  private static Vec3f add(Vec3f origin, Vec3f value) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(value);
    return new Vec3f(origin.x() + value.x(), origin.y() + value.y(), origin.z() + value.z());
  }
}

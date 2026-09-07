package dev.bluevista.craftq3.botlib.item;

import dev.bluevista.craftq3.botlib.aas.AasGoalLocator;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.collision.TraceRequest;
import dev.bluevista.craftq3.collision.TraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/** Map/drop localization using original AAS geometry and a borrowed world collision provider. */
public final class ItemPlacement
    implements ItemRegistry.PlacementResolver, ItemRegistry.LivePlacementResolver {
  /** Empty means unsupported; zero means a completed query found no reachable jump-pad area. */
  @FunctionalInterface
  public interface JumpPadResolver {
    OptionalInt bestArea(Vec3 origin, Vec3 mins, Vec3 maxs);
  }

  private final AasNavigation navigation;
  private final AasGoalLocator goals;
  private final TraceWorld world;
  private final JumpPadResolver jumpPads;
  private final Consumer<String> diagnostics;

  /** Ordinary placement works; airborne suspended items report the unavailable trajectory query. */
  public ItemPlacement(AasNavigation navigation, TraceWorld world, Consumer<String> diagnostics) {
    this(navigation, world, (origin, mins, maxs) -> OptionalInt.empty(), diagnostics);
  }

  public ItemPlacement(
      AasNavigation navigation,
      TraceWorld world,
      JumpPadResolver jumpPads,
      Consumer<String> diagnostics) {
    this.navigation = Objects.requireNonNull(navigation);
    goals = new AasGoalLocator(navigation);
    this.world = Objects.requireNonNull(world);
    this.jumpPads = Objects.requireNonNull(jumpPads);
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  @Override
  public Optional<ItemRegistry.Placement> resolve(
      ItemInfo item, Vec3 mapOrigin, boolean suspended) {
    Objects.requireNonNull(item);
    Vec3 origin = floats(mapOrigin);
    if (!suspended) {
      var drop =
          world.trace(new TraceRequest(origin, down(origin, 100), item.mins(), item.maxs(), 1, 0));
      if (!drop.startSolid()) origin = floats(drop.endPosition());
    } else if ((world.pointContents(origin) & 32) == 0) {
      var below =
          world.trace(
              new TraceRequest(origin, down(origin, 32), item.mins(), item.maxs(), 65537, -1));
      if ((float) below.fraction() == 1) {
        OptionalInt area =
            Objects.requireNonNull(jumpPads.bestArea(origin, item.mins(), item.maxs()));
        if (area.isEmpty()) {
          diagnostics.accept(
              "Suspended bot item "
                  + item.classname()
                  + " at "
                  + origin
                  + " requires an unavailable jump-pad trajectory query");
          return Optional.empty();
        }
        if (area.getAsInt() < 0 || area.getAsInt() >= navigation.map().areas().size())
          throw new IllegalArgumentException("Jump-pad resolver returned an invalid AAS area");
        return area.getAsInt() == 0
            ? Optional.empty()
            : Optional.of(new ItemRegistry.Placement(origin, origin, area.getAsInt()));
      }
    }
    return Optional.of(resolve(item, origin));
  }

  /** Live entities have already been placed by qagame; they receive goal localization only. */
  @Override
  public ItemRegistry.Placement resolve(ItemInfo item, Vec3 origin) {
    Objects.requireNonNull(item);
    origin = floats(origin);
    var goal = goals.bestReachableArea(origin, item.mins(), item.maxs());
    return new ItemRegistry.Placement(origin, goal.origin(), goal.area());
  }

  @Override
  public boolean isJumpPadArea(int area) {
    return area > 0
        && area < navigation.map().areaSettings().size()
        && (navigation.map().areaSettings().get(area).contents() & 128) != 0;
  }

  private static Vec3 floats(Vec3 value) {
    Objects.requireNonNull(value);
    return new Vec3((float) value.x(), (float) value.y(), (float) value.z());
  }

  private static Vec3 down(Vec3 origin, float distance) {
    return new Vec3((float) origin.x(), (float) origin.y(), (float) origin.z() - distance);
  }
}

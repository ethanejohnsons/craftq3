package dev.bluevista.craftq3.engine.botlib.item;

import dev.bluevista.craftq3.engine.botlib.BotCollision;
import dev.bluevista.craftq3.engine.botlib.Contents;
import dev.bluevista.craftq3.engine.botlib.aas.AasGoalLocator;
import dev.bluevista.craftq3.engine.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.engine.common.math.Vec3f;
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
    OptionalInt bestArea(Vec3f origin, Vec3f mins, Vec3f maxs);
  }

  private final AasNavigation navigation;
  private final AasGoalLocator goals;
  private final BotCollision world;
  private final JumpPadResolver jumpPads;
  private final Consumer<String> diagnostics;

  /** Ordinary placement works; airborne suspended items report the unavailable trajectory query. */
  public ItemPlacement(AasNavigation navigation, BotCollision world, Consumer<String> diagnostics) {
    this(navigation, world, (origin, mins, maxs) -> OptionalInt.empty(), diagnostics);
  }

  public ItemPlacement(
      AasNavigation navigation,
      BotCollision world,
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
      ItemInfo item, Vec3f mapOrigin, boolean suspended) {
    Objects.requireNonNull(item);
    Vec3f origin = floats(mapOrigin);
    if (!suspended) {
      var drop =
          world.trace(origin, item.mins(), item.maxs(), down(origin, 100), 0, Contents.SOLID);
      if (!drop.startSolid()) origin = floats(drop.endPosition());
    } else if ((world.pointContents(origin) & Contents.WATER) == 0) {
      var below =
          world.trace(
              origin, item.mins(), item.maxs(), down(origin, 32), -1, Contents.PLAYER_SOLID);
      if (below.fraction() == 1) {
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
  public ItemRegistry.Placement resolve(ItemInfo item, Vec3f origin) {
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

  private static Vec3f floats(Vec3f value) {
    Objects.requireNonNull(value);
    return new Vec3f(value.x(), value.y(), value.z());
  }

  private static Vec3f down(Vec3f origin, float distance) {
    return new Vec3f(origin.x(), origin.y(), origin.z() - distance);
  }
}

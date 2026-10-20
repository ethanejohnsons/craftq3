package dev.bluevista.craftq3.mc.fabric.building;

import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The building dimension, {@code craftq3:building}: an empty void world (data pack {@code
 * data/craftq3/dimension/building.json} and {@code dimension_type/building.json}, -64..320, fixed
 * time) in which each placed BSP occupies its own region along X ({@link BuildingRegions.Layout}).
 */
public final class BuildingDimension {
  public static final ResourceKey<Level> KEY =
      ResourceKey.create(
          Registries.DIMENSION, Identifier.fromNamespaceAndPath("craftq3", "building"));

  /** How regions are laid out; must match the dimension type's height. */
  public static final BuildingRegions.Layout LAYOUT = BuildingRegions.Layout.DEFAULT;

  private BuildingDimension() {}

  public static boolean is(Level level) {
    return level != null && level.dimension().equals(KEY);
  }
}

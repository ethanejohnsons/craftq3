package dev.bluevista.craftq3.fabric.mixin;

import net.minecraft.world.level.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PathNavigationRegion.class)
public interface BuildingRegionAccessor {
  @Accessor("level")
  Level craftq3$level();
}

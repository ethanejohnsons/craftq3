package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.building.BuildingRules;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entities collide with the BSP of a building region: the colliders gathered for one movement (the
 * plain sweep and the step-up attempt) gain a {@code BspMovementShape}. Only movement resolution
 * sees it; general world queries keep their block-only colliders.
 */
@Mixin(Entity.class)
public abstract class BuildingEntityMixin {
  @WrapOperation(
      method = {
        "collide",
        "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"
      },
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/Entity;collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
  private static List<VoxelShape> craftq3$bspColliders(
      Entity entity,
      Level level,
      List<VoxelShape> entityColliders,
      AABB region,
      Operation<List<VoxelShape>> original) {
    return BuildingRules.withBsp(
        entity, level, region, original.call(entity, level, entityColliders, region));
  }
}

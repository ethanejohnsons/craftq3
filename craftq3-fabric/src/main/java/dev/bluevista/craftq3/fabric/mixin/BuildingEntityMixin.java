package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.fabric.building.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class BuildingEntityMixin {
  // Restrict the analytic shape to movement and step selection, never general world queries.
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
  private static List<VoxelShape> craftq3$bsp(
      Entity entity,
      Level level,
      List<VoxelShape> entities,
      AABB region,
      Operation<List<VoxelShape>> original) {
    var nativeShapes = original.call(entity, level, entities, region);
    if (entity == null || entity.noPhysics || !level.dimension().equals(BuildingSession.DIMENSION))
      return nativeShapes;
    var shapes = new ArrayList<>(nativeShapes);
    int first = (int) Math.clamp(Math.floor((region.minX + 2048) / 4096), 0, 4096);
    int last = Math.min(4095, (int) Math.floor((region.maxX + 2048) / 4096));
    for (int slot = first; slot <= last; slot++) {
      var environment = BuildingWorlds.at(level, slot * 4096.0);
      if (environment != null)
        shapes.add(new BuildingMovementShape(environment.geometry(), region, entity.maxUpStep()));
    }
    return shapes;
  }
}

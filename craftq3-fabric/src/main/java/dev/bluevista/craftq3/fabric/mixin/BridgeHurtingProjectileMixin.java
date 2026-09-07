package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Quake owns avatar motion, but its native body remains a projectile target. */
@Mixin(AbstractHurtingProjectile.class)
public abstract class BridgeHurtingProjectileMixin {
  @WrapOperation(
      method = "canHitEntity",
      at =
          @At(
              value = "FIELD",
              target = "Lnet/minecraft/world/entity/Entity;noPhysics:Z",
              opcode = Opcodes.GETFIELD))
  private boolean craftq3$target(Entity entity, Operation<Boolean> original) {
    boolean noPhysics = original.call(entity);
    if (noPhysics && entity instanceof ServerPlayer player) {
      var controller = BridgePlayerController.get(player);
      if (controller != null && controller.alive) return false;
    }
    return noPhysics;
  }
}

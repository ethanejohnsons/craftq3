package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import dev.bluevista.craftq3.fabric.bridge.BridgeRangedSmoke;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native arrow enchantments compute their push; original Quake movement consumes it. */
@Mixin(AbstractArrow.class)
public abstract class BridgeArrowMixin {
  @WrapOperation(
      method = "onHitEntity",
      at =
          @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;igniteForSeconds(F)V"))
  private void craftq3$ignition(
      Entity target,
      float seconds,
      Operation<Void> original,
      @Share("craftq3$fireTicks") LocalIntRef fireTicks) {
    original.call(target, seconds);
    fireTicks.set(Math.max(0, net.minecraft.util.Mth.floor(seconds * 20)));
  }

  @WrapOperation(
      method = "onHitEntity",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
  private boolean craftq3$burn(
      Entity target,
      DamageSource source,
      float amount,
      Operation<Boolean> original,
      @Share("craftq3$fireTicks") LocalIntRef fireTicks) {
    boolean accepted = original.call(target, source, amount);
    if (target instanceof ServerPlayer player && source.getEntity() instanceof Mob) {
      var controller = BridgePlayerController.get(player);
      if (controller != null) {
        if (accepted) controller.mobFire(fireTicks.get());
        dev.bluevista.craftq3.fabric.bridge.BridgeFireSmoke.arrow(
            player, accepted, fireTicks.get());
      }
    }
    return accepted;
  }

  @WrapMethod(method = "doKnockback")
  private void craftq3$impulse(LivingEntity target, DamageSource source, Operation<Void> original) {
    if (target instanceof ServerPlayer player && source.getEntity() instanceof Mob) {
      var controller = BridgePlayerController.get(player);
      if (controller != null) {
        var before = player.getDeltaMovement();
        var impulse = net.minecraft.world.phys.Vec3.ZERO;
        try {
          original.call(target, source);
          impulse = player.getDeltaMovement().subtract(before);
          controller.impulse(impulse);
        } finally {
          player.setDeltaMovement(before);
        }
        BridgeRangedSmoke.observe(player, impulse, player.getDeltaMovement().subtract(before));
        return;
      }
    }
    original.call(target, source);
  }
}

package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import dev.bluevista.craftq3.fabric.bridge.BridgeFireballSmoke;
import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.SmallFireball;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native small-fireball impact and ignition; accepted mob hits retain their burn attribution. */
@Mixin(SmallFireball.class)
public abstract class BridgeSmallFireballMixin {
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
                  "Lnet/minecraft/world/entity/Entity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
  private boolean craftq3$burn(
      Entity target,
      ServerLevel level,
      DamageSource source,
      float amount,
      Operation<Boolean> original,
      @Share("craftq3$fireTicks") LocalIntRef fireTicks) {
    boolean accepted = original.call(target, level, source, amount);
    if (target instanceof ServerPlayer player) {
      var controller = BridgePlayerController.get(player);
      if (controller != null) {
        if (accepted && source.getEntity() instanceof Mob) controller.mobFire(fireTicks.get());
        dev.bluevista.craftq3.fabric.bridge.BridgeHitboxSmoke.impact(
            (SmallFireball) (Object) this, player, accepted);
        dev.bluevista.craftq3.fabric.bridge.BridgeBlazeSmoke.impact(
            (SmallFireball) (Object) this, player, accepted);
        BridgeFireballSmoke.impact(
            (SmallFireball) (Object) this, player, accepted, fireTicks.get());
      }
    }
    return accepted;
  }

  @Inject(method = "onHitBlock", at = @At("RETURN"))
  private void craftq3$cover(BlockHitResult hit, CallbackInfo ci) {
    dev.bluevista.craftq3.fabric.bridge.BridgeHitboxSmoke.block(
        (SmallFireball) (Object) this, hit.getBlockPos());
    BridgeFireballSmoke.block((SmallFireball) (Object) this, hit.getBlockPos());
  }
}

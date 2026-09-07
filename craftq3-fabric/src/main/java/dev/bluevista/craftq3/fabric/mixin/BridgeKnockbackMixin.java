package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;

/** Preserve Minecraft's hit direction/resistance calculation, then hand motion to Quake. */
@Mixin(LivingEntity.class)
public abstract class BridgeKnockbackMixin {
  @WrapMethod(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V")
  private void craftq3$impulse(
      double strength,
      double x,
      double z,
      DamageSource source,
      float damage,
      boolean flag,
      Operation<Void> original) {
    if ((Object) this instanceof ServerPlayer player) {
      var controller = BridgePlayerController.get(player);
      if (controller != null && controller.alive && source.getEntity() instanceof Mob) {
        var before = player.getDeltaMovement();
        boolean grounded = player.onGround();
        player.setOnGround(controller.grounded);
        try {
          original.call(strength, x, z, source, damage, flag);
          controller.impulse(player.getDeltaMovement().subtract(before));
        } finally {
          player.setDeltaMovement(before);
          player.setOnGround(grounded);
        }
        return;
      }
    }
    original.call(strength, x, z, source, damage, flag);
  }
}

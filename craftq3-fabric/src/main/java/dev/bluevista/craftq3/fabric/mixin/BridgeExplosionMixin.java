package dev.bluevista.craftq3.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.fabric.bridge.BridgePlayerController;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Native explosion exposure/resistance computes the impulse; Quake owns the resulting motion. */
@Mixin(ServerExplosion.class)
public abstract class BridgeExplosionMixin {
  @Shadow @Final private DamageSource damageSource;

  @WrapOperation(
      method = "hurtEntities",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/entity/Entity;push(Lnet/minecraft/world/phys/Vec3;)V"))
  private void craftq3$impulse(Entity entity, Vec3 impulse, Operation<Void> original) {
    if (entity instanceof ServerPlayer player && damageSource.getEntity() instanceof Mob) {
      var controller = BridgePlayerController.get(player);
      if (controller != null) {
        var before = player.getDeltaMovement();
        try {
          original.call(entity, impulse);
          controller.impulse(player.getDeltaMovement().subtract(before));
        } finally {
          player.setDeltaMovement(before);
        }
        return;
      }
    }
    original.call(entity, impulse);
  }

  @WrapOperation(
      method = "hurtEntities",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
  private Object craftq3$packet(
      Map<Object, Object> hits, Object key, Object value, Operation<Object> original) {
    if (key instanceof ServerPlayer player
        && damageSource.getEntity() instanceof Mob
        && BridgePlayerController.get(player) != null) {
      var previous = original.call(hits, key, Vec3.ZERO);
      // The original explosion packet still carries its effects, without a second host impulse.
      dev.bluevista.craftq3.fabric.bridge.BridgeExplosionSmoke.observe(
          player, (Vec3) value, (Vec3) hits.get(key));
      return previous;
    }
    return original.call(hits, key, value);
  }
}

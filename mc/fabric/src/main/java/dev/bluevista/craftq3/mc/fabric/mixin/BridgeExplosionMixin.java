package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import java.util.Map;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * An explosion's push on a bridged player becomes a Quake impulse (Minecraft still computes
 * exposure, falloff and explosion knockback resistance), and the player's own client is not sent
 * the push in the explosion packet: Quake movement owns the body. Fabric has no explosion event.
 */
@Mixin(ServerExplosion.class)
public abstract class BridgeExplosionMixin {
  @WrapOperation(
      method = "hurtEntities",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/entity/Entity;push(Lnet/minecraft/world/phys/Vec3;)V"))
  private void craftq3$quakeImpulse(Entity entity, Vec3 push, Operation<Void> original) {
    BridgeSession session = BridgedPlayer.of(entity);
    if (session == null) original.call(entity, push);
    else session.impulse(push);
  }

  @WrapOperation(
      method = "hurtEntities",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
  private Object craftq3$noClientPush(
      Map<Object, Object> players, Object player, Object push, Operation<Object> original) {
    return BridgedPlayer.of(player) == null ? original.call(players, player, push) : null;
  }
}

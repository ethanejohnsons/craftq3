package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * An arrow's or trident's own push (Punch, a Knockback weapon: {@code AbstractArrow.doKnockback},
 * which pushes the target directly instead of through {@code LivingEntity.knockback}) on a bridged
 * player becomes a Quake impulse: Minecraft computes it with the target's knockback resistance, the
 * velocity change goes to the Quake player and the body's motion is put back. The hit's damage and
 * its ordinary knockback already arrive through {@code actuallyHurt} and {@code
 * BridgeKnockbackMixin}.
 */
@Mixin(AbstractArrow.class)
public abstract class BridgeArrowMixin {
  @WrapMethod(method = "doKnockback")
  private void craftq3$quakeImpulse(
      LivingEntity target, DamageSource source, Operation<Void> original) {
    BridgeSession session = BridgedPlayer.of(target);
    if (session == null) {
      original.call(target, source);
      return;
    }
    Vec3 before = target.getDeltaMovement();
    Vec3 quake = session.velocity();
    target.setDeltaMovement(quake);
    try {
      original.call(target, source);
      session.impulse(target.getDeltaMovement().subtract(quake));
    } finally {
      target.setDeltaMovement(before);
    }
  }
}

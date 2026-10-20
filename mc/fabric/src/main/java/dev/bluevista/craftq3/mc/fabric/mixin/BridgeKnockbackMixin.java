package dev.bluevista.craftq3.mc.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/**
 * A bridged player's knockback becomes a Quake impulse: Minecraft still works out direction,
 * strength and knockback resistance (from Quake's own motion and grounded state), the resulting
 * velocity change goes to the Quake player, and the Minecraft body's motion is put back.
 */
@Mixin(LivingEntity.class)
public abstract class BridgeKnockbackMixin {
  @WrapMethod(method = "knockback(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V")
  private void craftq3$quakeImpulse(
      double power,
      double xd,
      double zd,
      DamageSource source,
      float damage,
      boolean comesFromEffect,
      Operation<Void> original) {
    BridgeSession session = BridgedPlayer.of(this);
    if (session == null) {
      original.call(power, xd, zd, source, damage, comesFromEffect);
      return;
    }
    LivingEntity self = (LivingEntity) (Object) this;
    Vec3 before = self.getDeltaMovement();
    boolean ground = self.onGround();
    // Minecraft's knockback halves the current motion before pushing: give it Quake's motion so
    // repeated hits saturate as they do in Minecraft instead of adding up.
    Vec3 quake = session.velocity();
    self.setDeltaMovement(quake);
    self.setOnGround(session.grounded());
    try {
      original.call(power, xd, zd, source, damage, comesFromEffect);
      session.impulse(self.getDeltaMovement().subtract(quake));
    } finally {
      self.setDeltaMovement(before);
      self.setOnGround(ground);
    }
  }
}

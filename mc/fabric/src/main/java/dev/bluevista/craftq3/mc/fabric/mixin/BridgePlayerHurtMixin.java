package dev.bluevista.craftq3.mc.fabric.mixin;

import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the damage half of {@code BridgePlayerMixin}: damage Minecraft accepted for a bridged
 * player (after its invulnerability frames, difficulty scaling and attack cooldowns, before armor)
 * goes to the Quake player instead, and Minecraft health is left alone. {@code
 * ServerLivingEntityEvents.ALLOW_DAMAGE} fires before the invulnerability frames, so it is not the
 * same moment; this stays a cancelling HEAD inject.
 */
@Mixin(Player.class)
public abstract class BridgePlayerHurtMixin {
  @Inject(method = "actuallyHurt", at = @At("HEAD"), cancellable = true)
  private void craftq3$quakeDamage(
      ServerLevel level, DamageSource source, float damage, CallbackInfo ci) {
    BridgeSession session = BridgedPlayer.of(this);
    if (session == null) return;
    session.minecraftDamage(source, damage);
    ci.cancel();
  }
}

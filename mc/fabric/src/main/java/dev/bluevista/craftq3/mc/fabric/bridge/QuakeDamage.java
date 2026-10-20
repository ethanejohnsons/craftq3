package dev.bluevista.craftq3.mc.fabric.bridge;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * The {@code craftq3:quake} damage type (data pack: {@code data/craftq3/damage_type/quake.json},
 * tagged {@code minecraft:no_knockback}): Quake weapons hurting Minecraft entities. The shooter is
 * both the direct and the causing entity, so kills, loot, experience and anger are attributed to
 * the player; the Quake knockback is applied separately as a push.
 */
public final class QuakeDamage {
  public static final ResourceKey<DamageType> TYPE =
      ResourceKey.create(
          Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath("craftq3", "quake"));

  private QuakeDamage() {}

  /** A Quake hit by {@code shooter} (null: no attributable player). */
  public static DamageSource source(ServerLevel level, ServerPlayer shooter) {
    var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(TYPE);
    return new DamageSource(type, shooter, shooter);
  }
}

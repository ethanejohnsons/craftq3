package dev.bluevista.craftq3.mc.hybrid.bridge;

/**
 * Damage the original qagame dealt to a Minecraft mob's actor during one server frame, for the
 * Fabric side to apply with Minecraft's own defences, death and drops.
 *
 * @param target the mob's {@link ExternalActors.Target#id()}
 * @param quakeDamage health the actor lost, in Quake points
 * @param impulseX the knockback the game gave the actor, Quake units per second (convert with
 *     {@code CoordinateTransform.velocityToMinecraft})
 * @param attacker the client number that hurt it last ({@code PERS_ATTACKER}), or -1
 * @param killed the game killed the actor this frame (its obituary is being shown)
 */
public record MobDamage(
    long target,
    int quakeDamage,
    float impulseX,
    float impulseY,
    float impulseZ,
    int attacker,
    boolean killed) {
  /**
   * Minecraft damage for a mob with {@code maxHealth}: its full health is the actor's spawn health.
   */
  public float minecraftDamage(float maxHealth) {
    return quakeDamage * maxHealth / ExternalActors.QUAKE_HEALTH_AT_FULL;
  }
}

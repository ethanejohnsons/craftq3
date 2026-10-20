package dev.bluevista.craftq3.mc.fabric.bridge;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * What changes for a Minecraft player while Quake owns its body: Adventure mode (so mobs still
 * target it while it cannot break blocks with its clicks), no Minecraft gravity, physics or fall
 * damage, held flight, and invulnerability while the Quake player is dead. Movement packets are
 * ignored ({@code ServerGamePacketListenerMixin}); the server places the body every tick instead.
 */
public final class BridgeRules {
  private BridgeRules() {}

  /** Applies the bridged state on entry. */
  public static void enter(ServerPlayer player) {
    if (player.gameMode.getGameModeForPlayer() != GameType.ADVENTURE)
      player.setGameMode(GameType.ADVENTURE);
    hold(player, true);
    player.onUpdateAbilities();
  }

  /** Re-applies the bridged state (every tick). */
  public static void hold(ServerPlayer player, boolean alive) {
    player.noPhysics = true;
    player.setNoGravity(true);
    player.setDeltaMovement(Vec3.ZERO);
    player.resetFallDistance();
    // A dead Quake player stops burning, so its respawn does not inherit the fire.
    if (!alive) player.clearFire();
    var abilities = player.getAbilities();
    boolean changed = !abilities.mayfly || !abilities.flying || abilities.invulnerable == alive;
    abilities.mayfly = true;
    abilities.flying = true;
    abilities.invulnerable = !alive;
    if (changed) player.onUpdateAbilities();
  }

  /** Moves the player's body to Quake's position and aim, and tells the chunk map. */
  public static void place(
      ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
    player.absSnapTo(x, y, z, yaw, pitch);
    player.setYHeadRot(yaw);
    player.setYBodyRot(yaw);
    player.level().getChunkSource().move(player);
  }
}

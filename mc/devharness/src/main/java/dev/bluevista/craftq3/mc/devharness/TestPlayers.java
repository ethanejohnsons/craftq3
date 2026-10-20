package dev.bluevista.craftq3.mc.devharness;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;

/** Server-side players and mobs for the GameTests, without a real client. */
final class TestPlayers {
  private TestPlayers() {}

  /**
   * A server player in {@code level}'s player list on an in-memory connection (as {@code
   * GameTestHelper.makeMockServerPlayerInLevel}, but with the server's own game mode so mobs target
   * it and it can be hurt).
   */
  static ServerPlayer join(ServerLevel level, String name) {
    CommonListenerCookie cookie =
        CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
    ServerPlayer player =
        new ServerPlayer(
            level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
    Connection connection = new Connection(PacketFlow.SERVERBOUND);
    new EmbeddedChannel(new ChannelHandler[] {connection});
    level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
    player.setGameMode(GameType.SURVIVAL);
    return player;
  }

  static void remove(ServerPlayer player) {
    player.level().getServer().getPlayerList().remove(player);
  }

  /** A mob at an exact position, without AI unless {@code ai}. */
  static <T extends Mob> T mob(
      ServerLevel level, EntityType<T> type, double x, double y, double z, boolean ai) {
    T mob = type.create(level, EntitySpawnReason.COMMAND);
    if (mob == null) throw new IllegalStateException("Cannot create " + type);
    mob.snapTo(x, y, z, 0, 0);
    mob.setNoAi(!ai);
    mob.setPersistenceRequired();
    level.addFreshEntity(mob);
    return mob;
  }

  static void discard(Entity entity) {
    if (entity != null && !entity.isRemoved()) entity.discard();
  }
}

package dev.bluevista.craftq3.mc.fabric.mixin;

import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** The duck behind {@link BridgedPlayer}: the player's bridge session as a field. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements BridgedPlayer {
  @Unique private BridgeSession craftq3$bridgeSession;

  @Override
  public BridgeSession craftq3$bridgeSession() {
    return craftq3$bridgeSession;
  }

  @Override
  public void craftq3$setBridgeSession(BridgeSession session) {
    craftq3$bridgeSession = session;
  }
}

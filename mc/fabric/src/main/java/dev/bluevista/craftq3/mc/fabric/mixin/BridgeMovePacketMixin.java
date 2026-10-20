package dev.bluevista.craftq3.mc.fabric.mixin;

import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a player is bridged the server places its body from the Quake player state every tick, so
 * the client's own movement packets are ignored (after the handler has moved onto the server
 * thread, where the bridge state lives). New: the old bridge ran the game on the client and let the
 * integrated server trust its position.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BridgeMovePacketMixin {
  @Shadow public ServerPlayer player;

  @Inject(
      method = "handleMovePlayer",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
              shift = At.Shift.AFTER),
      cancellable = true)
  private void craftq3$quakeOwnsMovement(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
    if (BridgedPlayer.of(player) != null) ci.cancel();
  }
}

package dev.bluevista.craftq3.mc.fabric.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: the player entered ({@code active}) or left the bridge. On entry the client
 * starts its Quake session and connects to the server's bridge game over {@link
 * Q3DatagramPayload}s; {@code game} is the game directory the server runs and the origin and scale
 * are the bridge's coordinate mapping (client-side terrain collision and rendering use the same).
 *
 * @param message why the bridge ended (shown in chat), or empty
 */
public record BridgeStatePayload(
    boolean active,
    String game,
    int originX,
    int originY,
    int originZ,
    int unitsPerBlock,
    String message)
    implements CustomPacketPayload {
  public static final Type<BridgeStatePayload> TYPE =
      new Type<>(Identifier.fromNamespaceAndPath("craftq3", "bridge_state"));

  public static final StreamCodec<FriendlyByteBuf, BridgeStatePayload> CODEC =
      CustomPacketPayload.codec(BridgeStatePayload::write, BridgeStatePayload::read);

  /** The bridge ended. */
  public static BridgeStatePayload ended(String message) {
    return new BridgeStatePayload(false, "", 0, 0, 0, 1, message);
  }

  private static BridgeStatePayload read(FriendlyByteBuf in) {
    return new BridgeStatePayload(
        in.readBoolean(),
        in.readUtf(64),
        in.readInt(),
        in.readInt(),
        in.readInt(),
        in.readVarInt(),
        in.readUtf(512));
  }

  private void write(FriendlyByteBuf out) {
    out.writeBoolean(active);
    out.writeUtf(game, 64);
    out.writeInt(originX);
    out.writeInt(originY);
    out.writeInt(originZ);
    out.writeVarInt(unitsPerBlock);
    out.writeUtf(message.length() > 512 ? message.substring(0, 512) : message, 512);
  }

  @Override
  public Type<BridgeStatePayload> type() {
    return TYPE;
  }
}

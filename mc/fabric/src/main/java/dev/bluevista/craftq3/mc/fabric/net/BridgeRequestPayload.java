package dev.bluevista.craftq3.mc.fabric.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: the bridge client asks to leave ({@link Kind#LEAVE}, Escape or a session that
 * ended), or reports that its Quake session could not start ({@link Kind#FAILED}).
 */
public record BridgeRequestPayload(Kind kind, String detail) implements CustomPacketPayload {
  public enum Kind {
    LEAVE,
    FAILED
  }

  public static final Type<BridgeRequestPayload> TYPE =
      new Type<>(Identifier.fromNamespaceAndPath("craftq3", "bridge_request"));

  public static final StreamCodec<FriendlyByteBuf, BridgeRequestPayload> CODEC =
      CustomPacketPayload.codec(BridgeRequestPayload::write, BridgeRequestPayload::read);

  private static BridgeRequestPayload read(FriendlyByteBuf in) {
    return new BridgeRequestPayload(in.readEnum(Kind.class), in.readUtf(256));
  }

  private void write(FriendlyByteBuf out) {
    out.writeEnum(kind);
    out.writeUtf(detail.length() > 256 ? detail.substring(0, 256) : detail, 256);
  }

  @Override
  public Type<BridgeRequestPayload> type() {
    return TYPE;
  }
}

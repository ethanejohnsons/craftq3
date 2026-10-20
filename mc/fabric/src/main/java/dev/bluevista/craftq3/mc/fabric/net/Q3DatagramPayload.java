package dev.bluevista.craftq3.mc.fabric.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One protocol-68 datagram between a client's Quake session and the server's bridge game, carried
 * inside Minecraft's play connection in both directions. The Quake netchan keeps its own sequencing
 * and reliability on top; Minecraft only provides the ordered transport.
 */
public record Q3DatagramPayload(byte[] data) implements CustomPacketPayload {
  /** Larger than any protocol-68 datagram ({@code MAX_PACKETLEN} is 1400 plus headers). */
  public static final int MAX_BYTES = 16384;

  public static final Type<Q3DatagramPayload> TYPE =
      new Type<>(Identifier.fromNamespaceAndPath("craftq3", "q3_datagram"));

  public static final StreamCodec<FriendlyByteBuf, Q3DatagramPayload> CODEC =
      CustomPacketPayload.codec(Q3DatagramPayload::write, Q3DatagramPayload::read);

  public Q3DatagramPayload {
    if (data.length == 0 || data.length > MAX_BYTES)
      throw new IllegalArgumentException("Datagram of " + data.length + " bytes");
  }

  private static Q3DatagramPayload read(FriendlyByteBuf in) {
    return new Q3DatagramPayload(in.readByteArray(MAX_BYTES));
  }

  private void write(FriendlyByteBuf out) {
    out.writeByteArray(data);
  }

  @Override
  public Type<Q3DatagramPayload> type() {
    return TYPE;
  }
}

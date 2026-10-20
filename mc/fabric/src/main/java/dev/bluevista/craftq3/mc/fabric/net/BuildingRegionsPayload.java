package dev.bluevista.craftq3.mc.fabric.net;

import dev.bluevista.craftq3.mc.hybrid.building.BuildingRegionCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: the building regions of the dimension the player is in, as a {@link
 * BuildingRegionCodec} record (empty outside the building dimension). The client loads each BSP
 * from its own installation, checks the SHA-256 and uses it for movement prediction, picking and
 * rendering; a region whose BSP it lacks stays invisible to it while the server keeps its rules.
 */
public record BuildingRegionsPayload(byte[] regions) implements CustomPacketPayload {
  public static final Type<BuildingRegionsPayload> TYPE =
      new Type<>(Identifier.fromNamespaceAndPath("craftq3", "building_regions"));

  public static final StreamCodec<FriendlyByteBuf, BuildingRegionsPayload> CODEC =
      CustomPacketPayload.codec(BuildingRegionsPayload::write, BuildingRegionsPayload::read);

  private static BuildingRegionsPayload read(FriendlyByteBuf in) {
    return new BuildingRegionsPayload(in.readByteArray(BuildingRegionCodec.MAX_BYTES));
  }

  private void write(FriendlyByteBuf out) {
    out.writeByteArray(regions);
  }

  @Override
  public Type<BuildingRegionsPayload> type() {
    return TYPE;
  }
}

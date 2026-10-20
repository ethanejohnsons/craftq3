package dev.bluevista.craftq3.mc.fabric.bridge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.bluevista.craftq3.mc.hybrid.bridge.Loadout;
import dev.bluevista.craftq3.mc.hybrid.bridge.LoadoutCodec;
import dev.bluevista.craftq3.mc.hybrid.bridge.PickupPlacement;
import dev.bluevista.craftq3.mc.hybrid.bridge.PickupPlacementCodec;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * What the bridge saves, as persistent Fabric data attachments (no NBT hooks, no files of its own):
 *
 * <ul>
 *   <li>per player: the Quake loadout of each game directory ({@link LoadoutCodec} bytes, tied to
 *       the qagame module that wrote them), kept across deaths;
 *   <li>per player: the return marker, the Minecraft state to put back if the player was still
 *       bridged when the server stopped or crashed;
 *   <li>per level: the pickup placements of each game directory ({@link PickupPlacementCodec}).
 * </ul>
 *
 * Binary records are stored base64-encoded inside the attachment's map so Minecraft's own save
 * format carries them unchanged. Malformed records are reported and left as they are.
 */
public final class BridgeData {
  private BridgeData() {}

  private static final Codec<Map<String, String>> STRING_MAP =
      Codec.unboundedMap(Codec.STRING, Codec.STRING).xmap(HashMap::new, m -> m);

  /** Game directory to base64 {@link LoadoutCodec} record. */
  public static final AttachmentType<Map<String, String>> LOADOUTS =
      AttachmentRegistry.create(
          Identifier.fromNamespaceAndPath("craftq3", "bridge_loadouts"),
          builder -> builder.persistent(STRING_MAP).copyOnDeath());

  /** Game directory to base64 {@link PickupPlacementCodec} file. */
  public static final AttachmentType<Map<String, String>> PICKUPS =
      AttachmentRegistry.createPersistent(
          Identifier.fromNamespaceAndPath("craftq3", "bridge_pickups"), STRING_MAP);

  /**
   * The Minecraft state a bridged player returns to.
   *
   * @param gameMode {@link GameType#getId()}
   */
  public record ReturnState(
      int gameMode, boolean mayfly, boolean flying, boolean invulnerable, boolean noGravity) {
    public static final Codec<ReturnState> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.INT.fieldOf("game_mode").forGetter(ReturnState::gameMode),
                        Codec.BOOL.fieldOf("mayfly").forGetter(ReturnState::mayfly),
                        Codec.BOOL.fieldOf("flying").forGetter(ReturnState::flying),
                        Codec.BOOL.fieldOf("invulnerable").forGetter(ReturnState::invulnerable),
                        Codec.BOOL.fieldOf("no_gravity").forGetter(ReturnState::noGravity))
                    .apply(i, ReturnState::new));

    public static ReturnState capture(ServerPlayer player) {
      var abilities = player.getAbilities();
      return new ReturnState(
          player.gameMode.getGameModeForPlayer().getId(),
          abilities.mayfly,
          abilities.flying,
          abilities.invulnerable,
          player.isNoGravity());
    }

    /** Puts the player back as captured (game mode, abilities, gravity, physics). */
    public void apply(ServerPlayer player) {
      player.setGameMode(GameType.byId(gameMode));
      var abilities = player.getAbilities();
      abilities.mayfly = mayfly;
      abilities.flying = flying;
      abilities.invulnerable = invulnerable;
      player.setNoGravity(noGravity);
      player.noPhysics = false;
      player.onUpdateAbilities();
    }
  }

  public static final AttachmentType<ReturnState> RETURN_STATE =
      AttachmentRegistry.createPersistent(
          Identifier.fromNamespaceAndPath("craftq3", "bridge_return"), ReturnState.CODEC);

  /** Forces class initialisation (registration) from the mod initializer. */
  public static void register() {}

  // ---- loadouts -------------------------------------------------------------------------------

  /**
   * The loadout saved for {@code game} and the qagame {@code module}, empty when none was saved (or
   * by another module).
   *
   * @throws IOException when the saved record is malformed (it is left untouched)
   */
  public static Optional<Loadout> loadout(ServerPlayer player, String game, byte[] module)
      throws IOException {
    Map<String, String> all = player.getAttached(LOADOUTS);
    String encoded = all == null ? null : all.get(game);
    if (encoded == null || encoded.isEmpty()) return Optional.empty();
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException malformed) {
      throw new IOException("Saved loadout is not base64", malformed);
    }
    return LoadoutCodec.decode(bytes, module);
  }

  /** Saves {@code loadout} (or clears the checkpoint when empty) for {@code game}. */
  public static void saveLoadout(
      ServerPlayer player, String game, Optional<Loadout> loadout, byte[] module) {
    Map<String, String> all = new HashMap<>(player.getAttachedOrElse(LOADOUTS, Map.of()));
    if (loadout.isPresent())
      all.put(game, Base64.getEncoder().encodeToString(LoadoutCodec.encode(loadout.get(), module)));
    else all.remove(game);
    player.setAttached(LOADOUTS, all);
  }

  // ---- pickups --------------------------------------------------------------------------------

  /**
   * The level's pickup placements for {@code game}.
   *
   * @throws IOException when the saved file is malformed (it is left untouched)
   */
  public static List<PickupPlacement> pickups(ServerLevel level, String game) throws IOException {
    Map<String, String> all = level.getAttached(PICKUPS);
    String encoded = all == null ? null : all.get(game);
    if (encoded == null || encoded.isEmpty()) return List.of();
    try {
      return PickupPlacementCodec.decode(Base64.getDecoder().decode(encoded));
    } catch (IllegalArgumentException malformed) {
      throw new IOException("Saved pickups are not base64", malformed);
    }
  }

  public static void savePickups(ServerLevel level, String game, List<PickupPlacement> pickups) {
    Map<String, String> all = new HashMap<>(level.getAttachedOrElse(PICKUPS, Map.of()));
    if (pickups.isEmpty()) all.remove(game);
    else all.put(game, Base64.getEncoder().encodeToString(PickupPlacementCodec.encode(pickups)));
    level.setAttached(PICKUPS, all);
  }
}

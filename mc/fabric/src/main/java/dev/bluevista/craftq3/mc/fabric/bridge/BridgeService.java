package dev.bluevista.craftq3.mc.fabric.bridge;

import dev.bluevista.craftq3.mc.fabric.building.BuildingDimension;
import dev.bluevista.craftq3.mc.fabric.level.CraftQ3Level;
import dev.bluevista.craftq3.mc.fabric.net.BridgeStatePayload;
import dev.bluevista.craftq3.mc.fabric.net.Q3DatagramPayload;
import dev.bluevista.craftq3.mc.fabric.server.Q3Content;
import dev.bluevista.craftq3.mc.fabric.server.Q3ServerContent;
import dev.bluevista.craftq3.mc.hybrid.bridge.Loadout;
import dev.bluevista.craftq3.mc.hybrid.bridge.PickupPlacement;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bridge on one Minecraft server: entering ({@code /q3 bridge [fresh]}) and leaving ({@code /q3
 * leave}, the client's Escape, disconnecting, changing dimension), the per-level {@link
 * BridgeGame}s and their tick, the payload receivers, pickup placements and the loadout
 * checkpoints. Created at server start and closed at stop; everything runs on the server thread.
 */
public final class BridgeService implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(BridgeService.class);

  private final MinecraftServer server;
  private final Q3ServerContent content;

  public BridgeService(MinecraftServer server, Q3ServerContent content) {
    this.server = Objects.requireNonNull(server);
    this.content = Objects.requireNonNull(content);
  }

  public Q3ServerContent content() {
    return content;
  }

  // ---- entering and leaving -------------------------------------------------------------------

  /**
   * Starts the bridge for {@code player} once the installation is mounted; reports to {@code
   * feedback} (on the server thread). {@code fresh} ignores and later replaces the saved loadout.
   */
  public void enter(ServerPlayer player, boolean fresh, Consumer<Component> feedback) {
    if (BridgedPlayer.of(player) != null) {
      feedback.accept(Component.literal("CraftQ3: you are already in the bridge (/q3 leave)"));
      return;
    }
    if (!ServerPlayNetworking.canSend(player, Q3DatagramPayload.TYPE)) {
      feedback.accept(Component.literal("CraftQ3: the bridge needs CraftQ3 on your client too"));
      return;
    }
    if (BuildingDimension.is(player.level())) {
      feedback.accept(Component.literal("CraftQ3: the bridge is not available while building"));
      return;
    }
    feedback.accept(Component.literal("CraftQ3: entering the bridge..."));
    content
        .content()
        .whenCompleteAsync(
            (mounted, error) -> {
              if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                feedback.accept(Component.literal("CraftQ3: " + cause.getMessage()));
                return;
              }
              if (player.hasDisconnected() || BridgedPlayer.of(player) != null) return;
              enterWith(player, mounted, Optional.empty(), fresh, feedback);
            },
            server);
  }

  /**
   * Enters the bridge with mounted content, now (server thread): {@code carried} (a live loadout
   * brought from a Quake match) overrides the saved one; {@code fresh} ignores the saved one.
   * Returns the session, or empty when entry was refused (reported to {@code feedback}).
   */
  public Optional<BridgeSession> enterWith(
      ServerPlayer player,
      Q3Content mounted,
      Optional<Loadout> carried,
      boolean fresh,
      Consumer<Component> feedback) {
    ServerLevel level = player.level();
    CraftQ3Level state = CraftQ3Level.of(level);
    Optional<Loadout> loadout = carried;
    if (loadout.isEmpty() && !fresh) {
      try {
        loadout = BridgeData.loadout(player, mounted.game(), mounted.qagameSha256());
      } catch (IOException malformed) {
        feedback.accept(
            Component.literal(
                "CraftQ3: your saved loadout is unreadable ("
                    + malformed.getMessage()
                    + "); /q3 bridge fresh starts without it"));
        return Optional.empty();
      }
    }
    BridgeGame game = state.craftq3$bridge();
    if (game != null && (!game.running() || game.content() != mounted)) {
      endGame(level, game, "The bridge restarted");
      game = null;
    }
    if (game == null) {
      try {
        game = BridgeGame.start(level, mounted, player.blockPosition(), player.getYRot());
      } catch (IOException | RuntimeException failure) {
        LOGGER.error("CraftQ3: the bridge could not start", failure);
        feedback.accept(
            Component.literal("CraftQ3: the bridge could not start: " + failure.getMessage()));
        return Optional.empty();
      }
      state.craftq3$setBridge(game);
    }
    Optional<String> refusal = game.refusal(player.getX(), player.getZ());
    if (refusal.isPresent()) {
      feedback.accept(Component.literal("CraftQ3: " + refusal.get()));
      return Optional.empty();
    }
    if (player.getAttached(BridgeData.RETURN_STATE) == null)
      player.setAttached(BridgeData.RETURN_STATE, BridgeData.ReturnState.capture(player));
    BridgeSession session = game.join(player, loadout);
    ((BridgedPlayer) player).craftq3$setBridgeSession(session);
    BridgeRules.enter(player);
    var origin = game.anchor();
    if (ServerPlayNetworking.canSend(player, BridgeStatePayload.TYPE))
      ServerPlayNetworking.send(
          player,
          new BridgeStatePayload(
              true,
              mounted.game(),
              origin.getX(),
              origin.getY(),
              origin.getZ(),
              (int) game.transform().unitsPerBlock(),
              ""));
    feedback.accept(
        Component.literal(
            loadout.isPresent()
                ? "CraftQ3: bridge entered with your loadout"
                : "CraftQ3: bridge entered"));
    return Optional.of(session);
  }

  /**
   * Ends {@code player}'s bridge session: its loadout is saved (cleared when it left dead), the
   * Minecraft state is restored and the client is told. Does nothing for a player not bridged.
   */
  public void leave(ServerPlayer player, String reason) {
    BridgeSession session = BridgedPlayer.of(player);
    if (session == null) return;
    ((BridgedPlayer) player).craftq3$setBridgeSession(null);
    BridgeGame game = session.game();
    boolean connected = session.slot() >= 0 && game.running();
    boolean alive = session.alive();
    Optional<Loadout> loadout = game.leave(session, reason);
    if (connected) {
      Q3Content mounted = game.content();
      // Leaving dead clears the checkpoint, so the next entry starts fresh.
      BridgeData.saveLoadout(
          player, mounted.game(), alive ? loadout : Optional.empty(), mounted.qagameSha256());
    }
    restore(player);
    if (!player.hasDisconnected() && ServerPlayNetworking.canSend(player, BridgeStatePayload.TYPE))
      ServerPlayNetworking.send(player, BridgeStatePayload.ended(reason));
    if (game.sessions().isEmpty() && CraftQ3Level.of(game.level()).craftq3$bridge() == game)
      endGame(game.level(), game, "no players left");
  }

  /** Puts the player's Minecraft state back from its return marker and removes the marker. */
  public static void restore(ServerPlayer player) {
    ((BridgeAvatar) player).craftq3$setAvatar(null);
    BridgeData.ReturnState saved = player.getAttached(BridgeData.RETURN_STATE);
    if (saved != null) {
      saved.apply(player);
      player.removeAttached(BridgeData.RETURN_STATE);
    }
    if (!player.hasDisconnected())
      player.connection.teleport(
          player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
  }

  private void endGame(ServerLevel level, BridgeGame game, String reason) {
    CraftQ3Level state = CraftQ3Level.of(level);
    if (state.craftq3$bridge() == game) state.craftq3$setBridge(null);
    for (BridgeSession s : List.copyOf(game.sessions())) leave(s.player(), reason);
    game.close();
    LOGGER.info("CraftQ3: bridge in {} ended: {}", level.dimension(), reason);
  }

  // ---- tick and events ------------------------------------------------------------------------

  /** After {@code level}'s entities ticked: one bridge tick. */
  public void tick(ServerLevel level) {
    BridgeGame game = CraftQ3Level.of(level).craftq3$bridge();
    if (game == null) return;
    List<BridgeSession> lost;
    try {
      lost = game.tick();
    } catch (RuntimeException | LinkageError failure) {
      LOGGER.error("CraftQ3: the bridge failed", failure);
      endGame(level, game, "The bridge stopped: " + failure);
      return;
    }
    if (!game.running()) {
      endGame(level, game, "The Quake game ended: " + game.server().dropReason());
      return;
    }
    for (BridgeSession s : lost) leave(s.player(), "Your Quake session disconnected");
  }

  /** A protocol-68 datagram from a client. */
  public void datagram(ServerPlayer player, byte[] data) {
    BridgeSession session = BridgedPlayer.of(player);
    if (session != null) session.game().receive(player, data);
  }

  /** A player joined: a return marker left by a crash or stop is applied. */
  public void joined(ServerPlayer player) {
    if (player.getAttached(BridgeData.RETURN_STATE) != null && BridgedPlayer.of(player) == null) {
      LOGGER.info(
          "CraftQ3: restoring {} after an interrupted bridge", player.getName().getString());
      restore(player);
    }
  }

  // ---- pickups --------------------------------------------------------------------------------

  /** Adds a pickup at {@code player}'s feet; returns the message to show. */
  public void addPickup(ServerPlayer player, String classname, Consumer<Component> feedback) {
    String name = classname.trim().toLowerCase(java.util.Locale.ROOT);
    if (!PickupPlacement.isItemClassname(name)) {
      feedback.accept(Component.literal("CraftQ3: not an item classname: " + classname));
      return;
    }
    withGame(
        feedback,
        game -> {
          ServerLevel level = player.level();
          List<PickupPlacement> list;
          try {
            list = new ArrayList<>(BridgeData.pickups(level, game));
          } catch (IOException malformed) {
            feedback.accept(
                Component.literal("CraftQ3: pickups unreadable: " + malformed.getMessage()));
            return;
          }
          int id = PickupPlacement.nextId(list);
          if (id < 0) {
            feedback.accept(Component.literal("CraftQ3: this dimension already has 256 pickups"));
            return;
          }
          list.add(new PickupPlacement(id, name, player.getX(), player.getY(), player.getZ()));
          BridgeData.savePickups(level, game, list);
          feedback.accept(
              Component.literal(
                  "CraftQ3: pickup #"
                      + id
                      + " "
                      + name
                      + " placed; it appears the next time the bridge starts here"));
        });
  }

  public void listPickups(ServerPlayer player, Consumer<Component> feedback) {
    withGame(
        feedback,
        game -> {
          try {
            List<PickupPlacement> list = BridgeData.pickups(player.level(), game);
            if (list.isEmpty()) feedback.accept(Component.literal("CraftQ3: no pickups here"));
            for (PickupPlacement p : list)
              feedback.accept(
                  Component.literal(
                      String.format(
                          java.util.Locale.ROOT,
                          "#%d %s at %.1f %.1f %.1f",
                          p.id(),
                          p.classname(),
                          p.x(),
                          p.y(),
                          p.z())));
          } catch (IOException malformed) {
            feedback.accept(
                Component.literal("CraftQ3: pickups unreadable: " + malformed.getMessage()));
          }
        });
  }

  public void removePickup(ServerPlayer player, int id, Consumer<Component> feedback) {
    withGame(
        feedback,
        game -> {
          ServerLevel level = player.level();
          try {
            List<PickupPlacement> list = new ArrayList<>(BridgeData.pickups(level, game));
            if (!list.removeIf(p -> p.id() == id)) {
              feedback.accept(Component.literal("CraftQ3: no pickup #" + id));
              return;
            }
            BridgeData.savePickups(level, game, list);
            feedback.accept(Component.literal("CraftQ3: pickup #" + id + " removed"));
          } catch (IOException malformed) {
            feedback.accept(
                Component.literal("CraftQ3: pickups unreadable: " + malformed.getMessage()));
          }
        });
  }

  /** Runs {@code action} with the configured game directory name. */
  private void withGame(Consumer<Component> feedback, Consumer<String> action) {
    action.accept(content.gameName());
  }

  /** Every bridge session on the server (tests, stats). */
  public List<BridgeSession> sessions() {
    List<BridgeSession> out = new ArrayList<>();
    for (ServerLevel level : server.getAllLevels()) {
      BridgeGame game = CraftQ3Level.of(level).craftq3$bridge();
      if (game != null) out.addAll(game.sessions());
    }
    return out;
  }

  @Override
  public void close() {
    for (ServerLevel level : server.getAllLevels()) {
      BridgeGame game = CraftQ3Level.of(level).craftq3$bridge();
      if (game != null) endGame(level, game, "The server is stopping");
    }
  }
}

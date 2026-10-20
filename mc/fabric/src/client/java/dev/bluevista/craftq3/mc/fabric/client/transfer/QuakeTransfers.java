package dev.bluevista.craftq3.mc.fabric.client.transfer;

import com.mojang.logging.LogUtils;
import dev.bluevista.craftq3.engine.console.CommandArgs;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.game.world.GameEntities;
import dev.bluevista.craftq3.engine.session.EngineSession;
import dev.bluevista.craftq3.engine.session.GameLocalServer;
import dev.bluevista.craftq3.engine.session.LocalServer;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import dev.bluevista.craftq3.mc.fabric.CraftQ3Mod;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgeSession;
import dev.bluevista.craftq3.mc.fabric.bridge.BridgedPlayer;
import dev.bluevista.craftq3.mc.fabric.client.bridge.BridgeScreen;
import dev.bluevista.craftq3.mc.fabric.client.screen.Q3Launch;
import dev.bluevista.craftq3.mc.fabric.client.screen.Q3Screen;
import dev.bluevista.craftq3.mc.hybrid.bridge.BridgeHost;
import dev.bluevista.craftq3.mc.hybrid.bridge.HostTriggerMapSource;
import dev.bluevista.craftq3.mc.hybrid.bridge.Loadout;
import dev.bluevista.craftq3.mc.hybrid.bridge.LoadoutPolicy;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Carries a live Quake loadout between a local Quake match and the bridge (singleplayer): the
 * {@link LocalServer.Factory} of every pure-Quake session, which watches the local player of each
 * local server it creates.
 *
 * <ul>
 *   <li>{@link #latest()}: the local player's loadout as of its last think while alive, for the
 *       {@code minecraft} console command (match to bridge).
 *   <li>{@link #carry}: a loadout from the bridge ({@code quake <map>}), restored into the local
 *       player at its first think in the next local server: weapons, ammo, armor, the holdable
 *       item, timed powerups and exact health. The local servers load their maps through {@link
 *       HostTriggerMapSource} ({@link GameLocalServer#factory}), so a {@link BridgeHost} can reach
 *       the carried health with the game's own mega-health and hurt rules, as the bridge does; on a
 *       map with no room for the host triggers only the inventory is written.
 * </ul>
 *
 * The listener runs on the local server's thread; the two fields are volatile hand-overs.
 */
public final class QuakeTransfers implements LocalServer.Factory {
  private static final Logger LOGGER = LogUtils.getLogger();

  /** Local servers whose maps carry the host triggers (server-only, checksums unchanged). */
  private static final LocalServer.Factory SERVERS =
      GameLocalServer.factory(HostTriggerMapSource::new);

  private volatile Loadout pending;
  private volatile Loadout latest;

  @Override
  public LocalServer create(LocalServer.Context context) throws IOException {
    LocalServer server = SERVERS.create(context);
    latest = null;
    if (server instanceof GameLocalServer local) local.server().addListener(new Watcher(local));
    return server;
  }

  // ---- console commands (singleplayer) ---------------------------------------------------------

  /**
   * Registers {@code quake <map>} in a bridge session: the bridged player's live loadout (taken on
   * the integrated server) goes into a new local match of {@code map}; the bridge ends (saving the
   * loadout as usual). {@code open} opens the match screen.
   */
  public void registerQuake(
      Minecraft minecraft,
      BridgeScreen bridge,
      EngineSession session,
      BiConsumer<Minecraft, Q3Launch> open) {
    session
        .console()
        .commands()
        .register(
            "quake",
            args -> {
              String map = map(args);
              if (map == null) {
                session.output().print("usage: quake <map>");
                return;
              }
              if (!session.searchPath().exists(VirtualPath.of("maps/" + map + ".bsp"))) {
                session.output().print("Can't find map maps/" + map + ".bsp");
                return;
              }
              IntegratedServer server = minecraft.getSingleplayerServer();
              if (server == null || minecraft.player == null) {
                session.output().print("quake transfers need a singleplayer world");
                return;
              }
              UUID id = minecraft.player.getUUID();
              server
                  .submit(
                      () -> {
                        ServerPlayer player = server.getPlayerList().getPlayer(id);
                        BridgeSession s = player == null ? null : BridgedPlayer.of(player);
                        return s == null || s.host() == null
                            ? Optional.<Loadout>empty()
                            : s.host().captureLoadout();
                      })
                  .thenAcceptAsync(
                      loadout -> {
                        if (loadout.isEmpty()) {
                          if (BridgeScreen.current(minecraft) == bridge)
                            session.output().print("You must be alive in the bridge to transfer");
                          return;
                        }
                        carry(loadout.get());
                        bridge.onClose();
                        open.accept(minecraft, Q3Launch.map(map));
                      },
                      minecraft);
            });
  }

  /**
   * Registers {@code minecraft} in a pure-Quake session: while alive in a local match, the live
   * loadout enters the bridge at the player's position in the open singleplayer world (overriding
   * the saved one); the match closes.
   */
  public void registerMinecraft(Minecraft minecraft, Q3Screen screen, EngineSession session) {
    session
        .console()
        .commands()
        .register(
            "minecraft",
            args -> {
              IntegratedServer server = minecraft.getSingleplayerServer();
              if (server == null || minecraft.player == null) {
                session.output().print("Open a singleplayer Minecraft world first");
                return;
              }
              if (session.state() != SessionState.LOCAL
                  && session.state() != SessionState.HOSTING) {
                session.output().print("minecraft carries the loadout of a local match");
                return;
              }
              Loadout carried = latest;
              if (carried == null) {
                session.output().print("You must be alive to transfer");
                return;
              }
              UUID id = minecraft.player.getUUID();
              minecraft.schedule(screen::onClose);
              server.execute(
                  () -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player == null) return;
                    var services = CraftQ3Mod.instance().services();
                    services
                        .content()
                        .content()
                        .whenCompleteAsync(
                            (mounted, error) -> {
                              if (error != null) {
                                player.sendSystemMessage(
                                    Component.literal("CraftQ3: " + error.getMessage()));
                                return;
                              }
                              services
                                  .bridge()
                                  .enterWith(
                                      player,
                                      mounted,
                                      Optional.of(carried),
                                      false,
                                      player::sendSystemMessage);
                            },
                            server);
                  });
            });
  }

  private static String map(CommandArgs args) {
    if (args.argc() < 2) return null;
    String map = args.argv(1).trim().toLowerCase(Locale.ROOT);
    if (map.startsWith("maps/")) map = map.substring(5);
    if (map.endsWith(".bsp")) map = map.substring(0, map.length() - 4);
    return map.matches("[a-z0-9_.\\-]{1,64}") ? map : null;
  }

  /** Writes {@code loadout} into the local player of the next local server. */
  public void carry(Loadout loadout) {
    pending = loadout;
  }

  /** The local player's live loadout, or null when it is not alive in a local match. */
  public Loadout latest() {
    return latest;
  }

  /**
   * Watches the local player of one local server: restores a carried loadout through its own {@link
   * BridgeHost} (whose trigger links it forwards around the player's thinks) and publishes the
   * player's live loadout.
   */
  private final class Watcher implements GameServer.Listener {
    private final BridgeHost host;

    Watcher(GameLocalServer local) {
      host = new BridgeHost(local.server(), LoadoutPolicy.BASE_GAME, 0);
    }

    @Override
    public void levelSpawned(GameServer server) {
      host.levelSpawned(server);
    }

    @Override
    public void levelRestarted(GameServer server) {
      host.levelRestarted(server);
    }

    @Override
    public void levelEnded(GameServer server, String reason) {
      host.levelEnded(server, reason);
      latest = null;
    }

    @Override
    public void beforeClientThink(GameServer server, int client, UserCmd command) {
      if (localPlayer(server, client)) {
        host.setPlayer(client);
        Loadout carried = pending;
        if (carried != null && host.triggersReady() && alive(server, client)) {
          pending = null;
          host.restoreLoadout(carried);
          LOGGER.info("CraftQ3: restoring the carried loadout in the local match: {}", carried);
        }
      }
      host.beforeClientThink(server, client, command);
    }

    @Override
    public void afterClientThink(GameServer server, int client) {
      host.afterClientThink(server, client);
      if (server.state() != GameServer.State.RUNNING || !localPlayer(server, client)) return;
      GameEntities g = server.entities().orElse(null);
      if (g == null || !g.located()) return;
      if (!alive(server, client)) {
        latest = null;
        return;
      }
      Loadout carried = pending;
      if (carried != null) {
        // No host triggers on this map: the inventory only, health stays the game's.
        pending = null;
        LoadoutPolicy.BASE_GAME.writeInventory(
            g.memory(), g.playerState(client), carried, server.levelTime());
        LOGGER.info("CraftQ3: carried inventory applied (no host triggers): {}", carried);
      }
      if (host.restoring()) return;
      try {
        latest = Loadout.capture(g.canonicalPlayerState(client), server.levelTime());
      } catch (IllegalArgumentException odd) {
        latest = null;
      }
    }

    private static boolean localPlayer(GameServer server, int client) {
      if (server.state() != GameServer.State.RUNNING) return false;
      ServerClient slot = server.client(client);
      return !slot.isBot() && !slot.isPuppet() && slot.isLocal();
    }

    private static boolean alive(GameServer server, int client) {
      GameEntities g = server.entities().orElse(null);
      return g != null
          && g.located()
          && g.playerInt(client, PlayerStateLayout.STATS + 4 * Loadout.STAT_HEALTH) > 0;
    }
  }
}
